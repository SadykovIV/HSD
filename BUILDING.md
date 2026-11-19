# Build HSD

Use JDK 17 and the Android SDK with platform 37 (android-37.0) and Build Tools 36.0.0, NDK 27.2.12479018 and CMake 3.22.1. Set `JAVA_HOME` and `ANDROID_HOME`, then run the Gradle wrapper.

```sh
cd android
./gradlew :app:assembleDebug :app:testDebugUnitTest
```

The debug APK is under `android/app/build/outputs/apk/debug/`. The Android Auto, CarPlay, CarLife and HiCar implementations and the desktop panel SDK are all in this repository.

## Android Auto profiles

HSD creates a local phone profile on first use. A head unit that requires an issued identity must accept the profile selected in Android’s system credential picker. You can choose a default profile or a different one for an individual head unit.

## Signed APKs

Set `HSD_KEYSTORE` to your keystore path and supply its password through `HSD_STORE_PASSWORD`. The default alias is `hsd-release`. Use `HSD_KEY_ALIAS` and `HSD_KEY_PASSWORD` for a different alias or key password. For the Gradle build you can instead set `HSD_SIGNING_PROPERTIES` to a file outside the repository with `storeFile`, `storePassword`, `keyAlias` and `keyPassword`. For distribution run `./gradlew :app:clean :app:assembleRelease` in `android/` and use the same key for the companion. A clean package avoids gaps left by incremental APK updates. The universal APK contains four native ABIs and compresses DEX and native libraries for download.

Both official APKs from 0.8.0 use the `CN=Ildar Sadykov` signing certificate. Its SHA-256 fingerprint is `6D:0F:08:8D:1A:84:77:71:CF:3C:B3:EB:83:64:E7:21:13:CB:1F:5C:FB:36:E2:DC:E7:1C:26:16:97:BA:8D:D8`. Run `apksigner verify --verbose --print-certs <file.apk>` on either download to check it.

Phone and computer activation use Android’s package-scoped `run-as`, so HSD’s release build enables the Android debuggable flag. HSD and its DriveDeck companion must be signed with the same certificate. [Build the DriveDeck companion](tools/drivedeck-companion/README.md)

## CarLife receiver tests

Install the HSD build and its matching `assembleDebugAndroidTest` APK on an Android emulator. Put the [CarLife HeadUnit Emulator](https://github.com/SadykovIV/CarLife-HeadUnit-Emulator) checkout on `PYTHONPATH` for its wire decoder. Run `python3 tools/carlife_receiver_test.py --serial <adb-serial>` to check 2,000 consecutive 10 ms PCM packets during pinch gestures, 240 KB video bursts, a 4 MB/s bulk-write limit and a camera transition. It also checks camera return with Bluetooth audio and instrument cluster guidance. The synthetic bulk-write limit is enforced on Android; throttling only a TCP reader would measure ADB buffering instead of USB write completion.

For the phone-screen regression, start the unlocked emulator with its screen on and add `--screen-off`. This check decodes the CarLife video with FFmpeg and compares the launcher before and after Android turns the phone display off. It wakes the emulator afterward. An existing PIN still requires manual unlock.

With the Clock and Settings split profile in DriveDeck, use `--decoder-restart` to discard the head unit's decoder state while the phone display is off. The test decodes the current desktop in a fresh FFmpeg process both during uninterrupted streaming and after a CarLife keyframe request on the same connection.

## Independent Android Auto DISCOVERY receiver

`tools/aap_discovery_peer.py` uses Python's OpenSSL bindings and its own AAP/protobuf parser, independently of the Kotlin transport and head-unit simulator. It verifies the phone's Google Automotive Link chain, three PNG icons, name and instance ID; exercises plaintext/encrypted AUTH_COMPLETE, GCM/CBC, fragmented and coalesced frames, ping and clean shutdown. This is a TCP-based protocol check, not a physical USB/HU compatibility test. The stricter icon presentation contract follows both investigated phone-side senders; optional protobuf fields alone do not prove a real head unit requires them.

For this interop check, select an Android phone profile issued for your device that chains to Google Automotive Link. The fixture requires that chain during TLS.

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

Set the version name and increment the version code in `android/app/build.gradle.kts`. Use a tag of the form `vX.Y.Z`. For v0.8.3 attach the signed DriveDeck APK, using its own version and build number. HSD is distributed as source in this release. GitHub supplies the source archives (ZIP and tar.gz) from the tag. Write short release notes in English and Russian and link to the system diagrams. [Release notes](docs/releases/) · [System diagrams and project map](docs/architecture.md)
