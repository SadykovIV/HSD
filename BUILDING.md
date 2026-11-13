# Build HSD

Use JDK 17 and the Android SDK with platform 37 (android-37.0) and Build Tools 36.0.0. Set `JAVA_HOME` and `ANDROID_HOME`, then run the Gradle wrapper.

```sh
cd android
./gradlew :app:assembleDebug :app:testDebugUnitTest
```

The debug APK is under `android/app/build/outputs/apk/debug/`. The Android Auto, CarPlay and CarLife implementations and the desktop panel SDK are all in this repository.

## Signed APKs

Set `HSD_SIGNING_PROPERTIES` to the absolute path of a local Java properties file containing `storeFile`, `storePassword`, `keyAlias` and `keyPassword`. Use an absolute keystore path. Run `./gradlew :app:assembleRelease` in `android/`. The signed APK is under `app/build/outputs/apk/release/`.

Phone and computer activation use Android’s package-scoped `run-as`, so HSD’s release build enables the Android debuggable flag. HSD and its DriveDeck companion must be signed with the same certificate. [Build the DriveDeck companion](tools/drivedeck-companion/README.md)

## Publish a release

Set the version name and increment the version code in `android/app/build.gradle.kts`. Use a tag of the form `vX.Y.Z`. Attach exactly two signed APKs, `HSD-X.Y.Z.apk` and `DriveDeck-HSD-X.Y.Z-codeNNN.apk`, using each app’s own version. GitHub supplies the source archives (ZIP and tar.gz) from the tag. Write short release notes in English and Russian and link to the system diagrams. [Release notes](docs/releases/) · [System diagrams and project map](docs/architecture.md)
