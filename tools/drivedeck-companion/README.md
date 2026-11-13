# DriveDeck companion

DriveDeck-HSD 0.7.0 (code349) uses the author’s HeadSwap edition of DriveDeck 0.7.0 (code348). It preserves the embedded renderer, saved layouts and package identity. HSD and HeadSwap Desktop can open separate desktops. Install the companion over the HSD release signed by the same publisher. [Upstream HeadSwap release](https://github.com/RotkivKiv/DriveDeck-Updates/releases/tag/headswap-code348)

## Build

From the repository root, run the following with Python 3, JDK 17, Android SDK platform `android-37.0`, Build Tools 36.0.0 and Apktool 3.0.3.

```sh
python3 tools/drivedeck-companion/build.py \
  --original /path/to/DriveDeck-HeadSwap-0.7.0-code348.apk \
  --apktool /path/to/apktool_3.0.3.jar \
  --jdk "$JAVA_HOME" --sdk "$ANDROID_HOME" \
  --keystore /path/to/signing.keystore --key-alias your-alias \
  --work /path/to/companion-build
```

Use the same signing key as HSD. Set `HSD_STORE_PASSWORD` and, if the key has a separate password, `HSD_KEY_PASSWORD`. The default alias is `hsd-release`. The build script identifies the upstream APK and Apktool by SHA-256 and writes its output to the specified work directory. [Publisher setup](../../BUILDING.md#signed-apks)

The adaptation sources and patches are part of HSD. The original DriveDeck application and its assets belong to RotkivKiv.
