# DriveDeck companion

HSD uses RotkivKiv’s DriveDeck 0.6.9 (upstream code331). The adaptation exposes embedded application windows and routes input to the HSD desktop. The resulting APK is **DriveDeck-HSD-0.6.9-code338.apk**. The launcher’s own package identity preserves existing layouts and app choices. [Original release](https://github.com/RotkivKiv/DriveDeck-Updates/releases/tag/stable-code331)

## Build

From the repository root, run the following with Python 3, JDK 17, Android SDK platform `android-37.0`, Build Tools 36.0.0 and Apktool 3.0.3.

```sh
python3 tools/drivedeck-companion/build.py \
  --original /path/to/DriveDeck-0.6.9-code331.apk \
  --apktool /path/to/apktool_3.0.3.jar \
  --jdk "$JAVA_HOME" --sdk "$ANDROID_HOME" \
  --keystore /path/to/signing.keystore --key-alias your-alias \
  --work /path/to/companion-build
```

Use the same signing key as HSD. Set `HSD_STORE_PASSWORD` and `HSD_KEY_PASSWORD` for a publisher keystore. The default alias and password support the standard Android development keystore. The build script identifies the upstream APK and Apktool by SHA-256 and writes its output to the specified work directory.

The adaptation sources and patches are part of HSD. The original DriveDeck application and its assets belong to RotkivKiv.
