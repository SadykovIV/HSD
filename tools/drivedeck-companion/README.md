# DriveDeck companion

DriveDeck-HSD 0.7.0 (code362) uses the author’s HeadSwap edition of DriveDeck 0.7.0 (code360). Its launcher can remain visible on the HSD display when the phone locks. Android controls lock-screen visibility for each embedded app. Embedded apps use the host’s window geometry in either orientation, up to 3840 × 2160 or 2160 × 3840. HSD and HeadSwap Desktop can open separate desktops. [Upstream HeadSwap release](https://github.com/RotkivKiv/DriveDeck-Updates/releases/tag/headswap-code360)

Code362 resumes the last player through its Android media session or supported media-button receiver. HSD supplies a session ID so the launcher can resume once on each connection, including when its process survives a disconnect. Returning Home or waking the phone preserves a manual pause during that connection. The dashboard stays on the projected display. Opening the blue Music shortcut uses the configured Back and Home controls. A Home request from HSD also closes the Music window.

Code361 starts the selected page’s embedded apps on the first visible frame of the HSD display. This lets a projected desktop on Android 11 and 12 open both tiles while the phone keeps input focus.

Code359 keeps DriveDeck visible if the fullscreen host is unavailable during reconnection. Tapping the body of the music card opens the selected player with the launcher controls. Its playback buttons retain their individual actions. Media-session updates refresh the launcher without issuing pause commands to other players.

Code358 adds **Car menu** beside the dock's app-menu and split/home controls. The host must advertise `com.hsd.projection.CAR_MENU`; older hosts keep their original layout. The dock reserves a slot in either orientation, and the button has its own touch and accessibility bounds. The signed command identifies the current projection display and is handled only by the active HSD service. [Protocol handoff and validation](../../docs/car-menu.md).

Code357 restores any selected app that has left its tile during a fullscreen session. Restoration waits for the geometry acknowledgement and checks the actual task state; an existing task, including an external activity opened by the app, is preserved. Unknown task state never triggers an automatic launch.

In fullscreen apps, tap **Back** once to go back or twice to return to DriveDeck. **App menu** opens the installed app list on the head unit. Both controls are enabled by default with HSD 0.8.1. Previous explicit settings take precedence. Under **Settings → Dock**, you can toggle Back, App menu and Home independently.

Under **Home screen**, **Remove gap between windows** joins adjacent dashboard tiles and the two apps on the split page. The split divider can still be dragged. Window bars can be set to normal, half size or hidden. Music and location access are available in Permissions.

## Build

From the repository root, run the following with Python 3, JDK 17, Android SDK platform `android-37.0`, Build Tools 36.0.0 and Apktool 3.0.3.

```sh
python3 tools/drivedeck-companion/build.py \
  --original /path/to/DriveDeck-HeadSwap-0.7.0-code360.apk \
  --apktool /path/to/apktool_3.0.3.jar \
  --jdk "$JAVA_HOME" --sdk "$ANDROID_HOME" \
  --keystore /path/to/signing.keystore --key-alias your-alias \
  --work /path/to/companion-build
```

Use the same signing key as HSD. Set `HSD_STORE_PASSWORD` and, if the key has a separate password, `HSD_KEY_PASSWORD`. The default alias is `hsd-release`. The build script identifies the upstream APK and Apktool by SHA-256 and writes its output to the specified work directory. [Publisher setup](../../BUILDING.md#signed-apks)

The adaptation sources and patches are part of HSD. The original DriveDeck application and its assets belong to RotkivKiv.
