# DriveDeck companion

DriveDeck-HSD 0.7.0 (code356) uses the author’s HeadSwap edition of DriveDeck 0.7.0 (code348). Its launcher can remain visible on the HSD display when the phone locks. Android controls lock-screen visibility for each embedded app. Embedded apps use the host’s window geometry in either orientation, up to 3840 × 2160 or 2160 × 3840. HSD and HeadSwap Desktop can open separate desktops. [Upstream HeadSwap release](https://github.com/RotkivKiv/DriveDeck-Updates/releases/tag/headswap-code348)

In fullscreen apps, tap **Back** once to go back or twice to return to DriveDeck. **App menu** opens the installed app list on the head unit. Both controls are enabled by default with HSD 0.8.1. Previous explicit settings take precedence. Under **Settings → Dock**, you can toggle Back, App menu and Home independently.

Under **Home screen**, **Remove gap between windows** joins adjacent dashboard tiles and the two apps on the split page. The split divider can still be dragged. Window bars can be set to normal, half size or hidden. Music and location access are available in Permissions.

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
