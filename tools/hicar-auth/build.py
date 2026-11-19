#!/usr/bin/env python3
"""Build Android HiChain using hash-pinned public source archives."""
import argparse
import fcntl
import hashlib
import json
import os
from pathlib import Path
import platform
import shutil
import subprocess
import tarfile
import tempfile
import urllib.request


ROOT = Path(__file__).resolve().parents[2]
SOURCE = ROOT / 'vendor/hicar-auth'
CACHE = ROOT / 'android/.hicar-native'
OUTPUT = ROOT / 'android/app/build/generated'
TARGETS = {
    'arm64-v8a': 'android-arm64', 'armeabi-v7a': 'android-arm',
    'x86_64': 'android-x86_64', 'x86': 'android-x86',
}
SDK_API = 29


def sha256(path):
    with path.open('rb') as data:
        return hashlib.file_digest(data, 'sha256').hexdigest()


def dependency(name, item, offline):
    digest = item['archive_sha256']
    extracted = CACHE / 'deps' / f'{name}-{digest[:16]}'
    if (extracted / '.verified').is_file():
        return extracted
    base = item['repository'].replace('https://github.com/', 'https://codeload.github.com/')
    url = item.get('archive', f"{base}/tar.gz/refs/tags/{item['tag']}")
    archive = extracted.with_suffix('.archive')
    old_archive = extracted.parent / f'{name}.tar.gz'
    if not archive.is_file() and old_archive.is_file() and sha256(old_archive) == digest:
        shutil.copyfile(old_archive, archive)
    if not archive.is_file():
        if offline:
            raise SystemExit(f'Missing {name}: run tools/hicar-auth/build.py --fetch-only before offline builds')
        download = archive.with_suffix('.partial')
        with urllib.request.urlopen(url, timeout=60) as response, download.open('wb') as out:
            shutil.copyfileobj(response, out)
        if sha256(download) != digest:
            download.unlink()
            raise SystemExit(f'{name}: source checksum mismatch')
        download.replace(archive)
    if sha256(archive) != digest:
        raise SystemExit(f'{name}: cached source checksum mismatch')
    with tempfile.TemporaryDirectory(dir=extracted.parent) as temporary:
        with tarfile.open(archive) as tf:
            tf.extractall(temporary, filter='data')
        roots = list(Path(temporary).iterdir())
        if len(roots) != 1 or not roots[0].is_dir():
            raise SystemExit(f'{name}: unexpected source archive layout')
        (roots[0] / '.verified').write_text(digest + '\n')
        roots[0].rename(extracted)
    return extracted


def build(deps, jobs, abis):
    sdk_path = os.environ.get('ANDROID_HOME') or os.environ.get('ANDROID_SDK_ROOT')
    if not sdk_path:
        raise SystemExit('Set ANDROID_HOME to the Android SDK directory')
    sdk = Path(sdk_path).resolve()
    ndk = Path(os.environ.get('ANDROID_NDK_ROOT', str(sdk / 'ndk/27.2.12479018'))).resolve()
    host = {'Darwin': 'darwin-x86_64', 'Linux': 'linux-x86_64'}[platform.system()]
    cmake = sdk / 'cmake/3.22.1/bin/cmake'
    if not cmake.is_file():
        cmake = shutil.which('cmake')
        if cmake is None:
            raise SystemExit('CMake 3.18+ is required')

    # OpenSSL makefiles require paths without spaces. Separate dependency build state
    # from JNI sources so touching the adapter never rebuilds libraries for every ABI.
    recipe = hashlib.sha256((
        str(ROOT) + str(ndk) + str(SDK_API) + sha256(ndk / 'source.properties') +
        sha256(Path(__file__)) + sha256(SOURCE / 'dependency-lock.json')
    ).encode()).hexdigest()[:20]
    work = Path(tempfile.gettempdir()) / f'hsd-hicar-{recipe}'
    work.mkdir(mode=0o700, exist_ok=True)
    openssl_source = work / 'openssl-source'
    if not openssl_source.is_dir():
        shutil.copytree(deps['openssl'], openssl_source)
    ndk_link = work / 'ndk'
    if not ndk_link.is_symlink():
        ndk_link.symlink_to(ndk, target_is_directory=True)
    if ndk_link.resolve() != ndk:
        raise SystemExit('Unexpected NDK work link')
    bin_dir = ndk_link / 'toolchains/llvm/prebuilt' / host / 'bin'
    env = dict(os.environ, ANDROID_NDK_ROOT=str(ndk_link),
               PATH=str(bin_dir) + os.pathsep + os.environ['PATH'])
    env.pop('CFLAGS', None)

    def run(command, cwd=None):
        subprocess.run([str(x) for x in command], cwd=cwd, env=env, check=True)

    for abi in abis:
        out = work / abi
        prefix = out / 'prefix'
        common = [
            '-DCMAKE_TOOLCHAIN_FILE=' + str(ndk_link / 'build/cmake/android.toolchain.cmake'),
            '-DANDROID_ABI=' + abi, '-DANDROID_PLATFORM=android-' + str(SDK_API),
            '-DCMAKE_BUILD_TYPE=Release', '-DCMAKE_POSITION_INDEPENDENT_CODE=ON',
            '-DCMAKE_INSTALL_PREFIX=' + str(prefix),
        ]
        stamp = prefix / '.built'
        if not stamp.is_file():
            ssl_build = out / 'openssl'
            ssl_build.mkdir(parents=True, exist_ok=True)
            if not (ssl_build / 'Makefile').exists():
                run([openssl_source / 'Configure', TARGETS[abi], 'no-shared', 'no-tests',
                     'no-apps', 'no-docs', f'-D__ANDROID_API__={SDK_API}',
                     '--prefix=/opt/hsd-hicar', '--openssldir=/opt/hsd-hicar/ssl',
                     '--libdir=lib'], ssl_build)
            run(['make', '-s', '-j' + str(jobs), 'build_sw'], ssl_build)
            staging = out / 'openssl-install'
            run(['make', '-s', 'install_sw', 'DESTDIR=' + str(staging)], ssl_build)
            shutil.copytree(staging / 'opt/hsd-hicar', prefix, dirs_exist_ok=True)

            for name, options, target, library in (
                ('mbedtls', ['-DENABLE_TESTING=OFF', '-DENABLE_PROGRAMS=OFF'], 'mbedcrypto', 'libmbedcrypto.a'),
                ('cjson', ['-DENABLE_CJSON_TEST=OFF', '-DBUILD_SHARED_LIBS=OFF'], 'cjson', 'libcjson.a'),
            ):
                build_dir = out / name
                run([cmake, '-S', deps[name], '-B', build_dir, *common, *options])
                run([cmake, '--build', build_dir, '--target', target, '-j', jobs])
                shutil.copyfile(next(build_dir.rglob(library)), prefix / 'lib' / library)
            stamp.touch()

        run([cmake, '-S', SOURCE / 'runtime', '-B', out / 'auth', *common,
             '-DSOURCE_ROOT=' + str(SOURCE / 'sources'),
             '-DOPENSSL_INCLUDE_DIR=' + str(prefix / 'include'),
             '-DOPENSSL_CRYPTO_LIBRARY=' + str(prefix / 'lib/libcrypto.a'),
             '-DMBEDTLS_INCLUDE=' + str(deps['mbedtls'] / 'include'),
             '-DCJSON_INCLUDE=' + str(deps['cjson']),
             '-DCJSON_LIB=' + str(prefix / 'lib/libcjson.a'),
             '-DMBEDCRYPTO_LIB=' + str(prefix / 'lib/libmbedcrypto.a')])
        run([cmake, '--build', out / 'auth', '-j', jobs])
        library = out / 'auth/libhsd_hicar_auth.so'
        installed = OUTPUT / 'hicar-libs' / abi / library.name
        installed.parent.mkdir(parents=True, exist_ok=True)
        if not installed.is_file() or library.stat().st_mtime_ns > installed.stat().st_mtime_ns:
            run([bin_dir / 'llvm-strip', '--strip-unneeded', '-o', installed, library])
        print(abi + ': HiCar authentication ready', flush=True)

    licenses = OUTPUT / 'hicar-assets/hicar-licenses'
    licenses.mkdir(parents=True, exist_ok=True)
    for name, path in {
        'receiver-MIT': SOURCE / 'LICENSE-MIT.txt', 'third-party': SOURCE / 'UPSTREAM-NOTICES.txt',
        'deviceauth': SOURCE / 'sources/deviceauth/LICENSE', 'huks': SOURCE / 'sources/huks/LICENSE',
        'securec': SOURCE / 'sources/securec/LICENSE', 'openssl': deps['openssl'] / 'LICENSE.txt',
        'mbedtls': deps['mbedtls'] / 'LICENSE', 'cjson': deps['cjson'] / 'LICENSE',
    }.items():
        shutil.copy2(path, licenses / (name + '.txt'))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--offline', action='store_true')
    parser.add_argument('--fetch-only', action='store_true')
    parser.add_argument('--abis', default=','.join(TARGETS))
    parser.add_argument('--jobs', type=int, default=4)
    args = parser.parse_args()
    abis = args.abis.split(',')
    if args.jobs < 1 or not set(abis) <= TARGETS.keys():
        parser.error('Provide a positive --jobs count and supported --abis')
    (CACHE / 'deps').mkdir(parents=True, exist_ok=True)
    with (CACHE / 'build.lock').open('w') as lockfile:
        fcntl.flock(lockfile, fcntl.LOCK_EX)
        lock = json.loads((SOURCE / 'dependency-lock.json').read_text())
        deps = {name: dependency(name, lock[name], args.offline) for name in ('openssl', 'mbedtls', 'cjson')}
        if not args.fetch_only:
            build(deps, args.jobs, abis)


if __name__ == '__main__':
    main()
