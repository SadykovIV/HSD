# Build HSD

Use JDK 17 and the Android SDK with platform 37 (android-37.0) and Build Tools 36.0.0, NDK 27.2.12479018 and CMake 3.22.1. Set `JAVA_HOME`, `ANDROID_HOME` and `HSD_PHONE_IDENTITY` (see below), then run the Gradle wrapper.

```sh
cd android
./gradlew :app:assembleDebug :app:testDebugUnitTest
```

The debug APK is under `android/app/build/outputs/apk/debug/`. The Android Auto, CarPlay and CarLife implementations and the desktop panel SDK are all in this repository.

## Bundled Android Auto identity

`HSD_PHONE_IDENTITY` must point to a PKCS12 file outside source control with an empty password, exactly one private-key entry, and its matching TLS server certificate and intermediate chain. The leaf must be currently valid and chain to the Google Automotive Link authority in `android/app/src/main/res/raw/aa_google_automotive_link.der`. A self-signed certificate cannot replace this build input. The build checks the issuer, role, dates and key possession every time, then includes the package in the signed APK as `assets/projection/phone.p12`.

To package an existing valid PEM identity with PKCS12 algorithms supported by Android 10:

```sh
openssl pkcs12 -export -name phone -in phone-chain.pem -inkey phone-key.pem \
  -out phone.p12 -passout pass: -keypbe PBE-SHA1-3DES -certpbe PBE-SHA1-3DES -macalg sha1
export HSD_PHONE_IDENTITY=/absolute/path/outside/repository/phone.p12
```

The package is part of the APK and is not a device-specific secret or hardware attestation. HSD validates it again at runtime. Android Auto and Google Play Services are not required to load it. Automatic selects the valid identity with the latest expiry among the bundled package, a verified downloaded package, and the optional installed Android Auto APK. An absent or unreadable optional source cannot disable the others. If no valid source exists and a bounded refresh fails, Automatic reports an error instead of silently substituting a self-signed identity.

From rc4, `AaIdentityUpdates` checks the public Mgears HTTPS feed on app startup, connection and a persisted daily JobScheduler task. Updates default to enabled and can be disabled in the AA certificate settings. The encrypted envelope is only a container: `AaIdentityFeed` verifies the Google Automotive Link certificate path, current dates, TLS role, matching private key and exact expiry metadata before writing an atomic private cache under `noBackupFilesDir`. A downloaded package can replace a valid cached identity only with a later signed certificate expiry, regardless of its JSON version counter. Existing sessions retain their chosen key. A valid offline identity never waits for a download. Failures retain the cache; requests use a validated Internet network rather than the HU hotspot. Future renewal depends on the public feed continuing to publish compatible valid identities. Keep the build input current for fresh offline installations too; the build itself does not download credentials.

## Signed APKs

Set `HSD_KEYSTORE` to your keystore path and supply its password through `HSD_STORE_PASSWORD`. The default alias is `hsd-release`. Use `HSD_KEY_ALIAS` and `HSD_KEY_PASSWORD` for a different alias or key password. For the Gradle build you can instead set `HSD_SIGNING_PROPERTIES` to a file outside the repository with `storeFile`, `storePassword`, `keyAlias` and `keyPassword`. Run `./gradlew :app:assembleRelease` in `android/` and use the same key for the companion.

Both official APKs from 0.8.0 use the `CN=Ildar Sadykov` signing certificate. Its SHA-256 fingerprint is `6D:0F:08:8D:1A:84:77:71:CF:3C:B3:EB:83:64:E7:21:13:CB:1F:5C:FB:36:E2:DC:E7:1C:26:16:97:BA:8D:D8`. Run `apksigner verify --verbose --print-certs <file.apk>` on either download to check it.

Phone and computer activation use Android’s package-scoped `run-as`, so HSD’s release build enables the Android debuggable flag. HSD and its DriveDeck companion must be signed with the same certificate. [Build the DriveDeck companion](tools/drivedeck-companion/README.md)

## CarLife receiver tests

Install the HSD build and its matching `assembleDebugAndroidTest` APK on an Android emulator. Put the [CarLife HeadUnit Emulator](https://github.com/SadykovIV/CarLife-HeadUnit-Emulator) checkout on `PYTHONPATH` for its wire decoder. Run `python3 tools/carlife_receiver_test.py --serial <adb-serial>` to check 2,000 consecutive 10 ms PCM packets during pinch gestures, 240 KB video bursts, a 4 MB/s bulk-write limit and a camera transition. It also checks camera return with Bluetooth audio and instrument cluster guidance. The synthetic bulk-write limit is enforced on Android; throttling only a TCP reader would measure ADB buffering instead of USB write completion.

For the phone-screen regression, start the unlocked emulator with its screen on and add `--screen-off`. This check decodes the CarLife video with FFmpeg and compares the launcher before and after Android turns the phone display off. It wakes the emulator afterward. An existing PIN still requires manual unlock.

With the Clock and Settings split profile in DriveDeck, use `--decoder-restart` to discard the head unit's decoder state while the phone display is off. The test decodes the current desktop in a fresh FFmpeg process both during uninterrupted streaming and after a CarLife keyframe request on the same connection.

## Android Auto identity tests

`AaApkIdentityTest` verifies an independent KDF vector, synthetic certificate chains, key possession, dates and invalid identities. `AutomaticAaIdentityTest` also checks the actual bundled build input and optional-provider failures. Set `HSD_AA_APK` to a locally supplied Android Auto base APK to enable the additional real-APK tests; no private key is checked into the repository.

`AaIdentityFeedTest` uses `HSD_AA_FEED`, a locally downloaded public Mgears JSON package, to test the actual envelope and rejection of damaged, oversized, expired and inconsistent data. Instrumentation `-e protocol identity-update` reads `qa-identity-feed.json` from the test installation's private cache directory and checks Android crypto, offline reload, rejected replacements and interrupted AtomicFile recovery in an isolated cache. `-e musicFocus true` checks initial receiver confirmation, local playback state, native-camera transitions, PCM ducking and restoration, and repeated transient focus loss/gain. With `-e externalMusic http://10.0.2.2:<port>/file.wav` (or `.mp4`), an unmodified separately installed VLC owns the real media session and audio focus; complete its normal first-run permission setup before the probe. `-e embedExternalMusic true` moves VLC's launcher into the real DriveDeck full-screen child display during playback.

The Android instrumentation supports `-e protocol standalone-identity` with Android Auto completely absent, or `-e protocol installed-identity` for the optional installed package, cache recovery, explicit selection and expiry. With `-e phoneTrust google`, the head-unit simulator verifies the phone's chain and TLS key possession before authorizing projection. Combine it with `-e protocol 1.3`, `1.5` or `6.1`, and `-e link usb` or `tcp`. Adding `-e authRefusal -3` sends an independent self-signed identity and checks that the receiver refuses it. Install the unmodified Android Auto APK/splits only for the optional-provider tests; disabling the app also verifies it need not run. Google API emulator images may contain a stub signed with an emulator-only key; use a disposable writable-system overlay to remove or replace that stub. Run instrumentation with ordinary shell ADB, not root adbd.

## Independent Android Auto DISCOVERY receiver

`tools/aap_discovery_peer.py` uses Python's OpenSSL bindings and its own AAP/protobuf parser, independently of the Kotlin transport and head-unit simulator. It verifies the phone's Google Automotive Link chain, three PNG icons, name and instance ID; exercises plaintext/encrypted AUTH_COMPLETE, GCM/CBC, fragmented and coalesced frames, ping and clean shutdown. This is a TCP-based protocol check, not a physical USB/HU compatibility test. The stricter icon presentation contract follows both investigated phone-side senders; optional protobuf fields alone do not prove a real head unit requires them.

The receiver certificate is a synthetic test fixture pinned only by instrumentation. Its private key is kept outside source control. To generate your own matching pair, run from the repository root **before building the test APK**:

```sh
umask 077
openssl req -x509 -newkey rsa:2048 -nodes -days 30 \
  -subj '/CN=HSD independent test receiver' \
  -keyout /tmp/hsd-discovery-receiver-key.pem \
  -out android/app/src/androidTest/assets/aap-interop/receiver.pem
```

Build/install the app and `:app:assembleDebugAndroidTest` with matching signatures on a disposable emulator, then run the peer in one terminal and instrumentation in another:

```sh
python3 tools/aap_discovery_peer.py --key /tmp/hsd-discovery-receiver-key.pem
adb -s <emulator-serial> shell am instrument --no-hidden-api-checks -w -r \
  -e protocol discovery-interop \
  com.hsd.universal.test/com.hsd.universal.DriveDeckAapProbe
```

The peer binds loopback port 5883, reached as `10.0.2.2` from the emulator. `--port` and instrumentation `-e fixturePort` can override it. Use a Python/OpenSSL installation supporting TLS 1.2 with `ECDHE-RSA-AES128-GCM-SHA256` and `ECDHE-RSA-AES128-SHA`. The test key is never used for production AA or APK signing; the certificate is excluded from the production APK.

## Publish a release

For a fix after a published release, increment the patch number. Name successive unpublished candidates `X.Y.Z-rc1`, `X.Y.Z-rc2` and so on. Give each distributed candidate and the final `X.Y.Z` a higher Android version code so updates install in order.

Set the version name and increment the version code in `android/app/build.gradle.kts`. Use a tag of the form `vX.Y.Z`. Attach exactly two signed APKs, `HSD-X.Y.Z.apk` and `DriveDeck-HSD-X.Y.Z-codeNNN.apk`, using each app’s own version. GitHub supplies the source archives (ZIP and tar.gz) from the tag. Write short release notes in English and Russian and link to the system diagrams. [Release notes](docs/releases/) · [System diagrams and project map](docs/architecture.md)
