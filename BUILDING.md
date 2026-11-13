# Build HSD

Use JDK 17 and the Android SDK with platform 37 (android-37.0) and Build Tools 36.0.0, NDK 27.2.12479018 and CMake 3.22.1. Set `JAVA_HOME` and `ANDROID_HOME`, then run the Gradle wrapper.

```sh
cd android
./gradlew :app:assembleDebug :app:testDebugUnitTest
```

The debug APK is under `android/app/build/outputs/apk/debug/`. The Android Auto, CarPlay and CarLife implementations and the desktop panel SDK are all in this repository.

## Signed APKs

Set `HSD_KEYSTORE` to your keystore path and supply its password through `HSD_STORE_PASSWORD`. The default alias is `hsd-release`. Use `HSD_KEY_ALIAS` and `HSD_KEY_PASSWORD` for a different alias or key password. For the Gradle build you can instead set `HSD_SIGNING_PROPERTIES` to a file outside the repository with `storeFile`, `storePassword`, `keyAlias` and `keyPassword`. Run `./gradlew :app:assembleRelease` in `android/` and use the same key for the companion.

Both official APKs from 0.8.0 use the `CN=Ildar Sadykov` signing certificate. Its SHA-256 fingerprint is `6D:0F:08:8D:1A:84:77:71:CF:3C:B3:EB:83:64:E7:21:13:CB:1F:5C:FB:36:E2:DC:E7:1C:26:16:97:BA:8D:D8`. Run `apksigner verify --verbose --print-certs <file.apk>` on either download to check it.

Phone and computer activation use Android’s package-scoped `run-as`, so HSD’s release build enables the Android debuggable flag. HSD and its DriveDeck companion must be signed with the same certificate. [Build the DriveDeck companion](tools/drivedeck-companion/README.md)

## CarLife receiver tests

Install the HSD build and its matching `assembleDebugAndroidTest` APK on an Android emulator. Put the [CarLife HeadUnit Emulator](https://github.com/SadykovIV/CarLife-HeadUnit-Emulator) checkout on `PYTHONPATH` for its wire decoder. Run `python3 tools/carlife_receiver_test.py --serial <adb-serial>` to check the audio sequence during pinch gestures, camera return with Bluetooth audio and instrument cluster guidance.

## Publish a release

Set the version name and increment the version code in `android/app/build.gradle.kts`. Use a tag of the form `vX.Y.Z`. Attach exactly two signed APKs, `HSD-X.Y.Z.apk` and `DriveDeck-HSD-X.Y.Z-codeNNN.apk`, using each app’s own version. GitHub supplies the source archives (ZIP and tar.gz) from the tag. Write short release notes in English and Russian and link to the system diagrams. [Release notes](docs/releases/) · [System diagrams and project map](docs/architecture.md)
