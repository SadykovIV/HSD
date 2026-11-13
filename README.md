# HSD

[Русский](README.ru.md) · [Download both APKs](https://github.com/SadykovIV/HSD/releases/latest) · [4PDA discussion](https://4pda.to/forum/index.php?showtopic=1129468) · [Telegram group](https://t.me/+H2QUrHMGm-g3ODhi) · [Support via CloudTips](https://pay.cloudtips.ru/p/41f6ecdd)

HSD builds on **[HeadSwap Desktop](https://github.com/SadykovIV/headswap_desk)** and brings **DriveDeck, a full car launcher**, to your head unit. Launch apps installed on your Android phone directly on the car screen, including apps outside the Android Auto catalog. Open an app full screen or keep navigation and a music player side by side. Control them using the head unit’s touchscreen.

| Head unit supports | Connect with |
| --- | --- |
| Android Auto | USB or wireless |
| Baidu CarLife | USB or wireless |
| CarPlay | Wireless |

HSD installs on Android 10 and later. On Android 10–12, app windows depend on secondary-display support in the phone firmware and in each app. When Android denies playback capture, select Bluetooth audio on the head unit. You can also select Android Desktop or, on compatible Samsung phones, Samsung DeX.
![DriveDeck on the author’s head unit, with music on the left and the map on the right](docs/hsd-head-unit.jpg)
[![Yandex Maps and Yandex Music running side by side in DriveDeck](docs/drivedeck.png)](docs/drivedeck.png)

[![HSD 0.7.3 on the phone: home, connection types and settings](docs/hsd-interface-en.png)](docs/hsd-interface-en.png)

See how the phone, launcher and head unit exchange video, audio and input. [System diagrams and project map](docs/architecture.md)

## Connect your phone

**Upgrading from 0.7.3 or earlier?** Both apps now use Ildar Sadykov’s signing key. Uninstall the old HSD and DriveDeck-HSD, install both APKs from the release, then activate HSD again. Uninstalling erases the apps’ local settings.

1. For an Android Auto connection, disable Google’s **Android Auto** app on your phone under **Settings → Apps → Android Auto** so the head unit can open HSD.
2. Download **HSD** and **DriveDeck-HSD** from the release and install both APKs. Allow your browser or file manager to install unknown apps if prompted. If Google Play Protect blocks installation, you can temporarily turn off app scanning in **Play Store → Profile → Play Protect → Settings** and turn it back on after installation. [Get the APKs](https://github.com/SadykovIV/HSD/releases/latest)
3. Open HSD, grant the permissions requested by the setup wizard and select **DriveDeck**.
4. On Android 10, select **Settings → Activation → Computer** in HSD and run the displayed command over USB. On Android 11 and later, use a computer or pair over Wi-Fi. For Wi-Fi pairing, connect the phone to a Wi-Fi network. In Android **Developer options**, enable **USB debugging** and **Wireless debugging**. In HSD, open **Settings → Activation → Wi-Fi** and enter the six-digit code from **Wireless debugging → Pair device with pairing code**.
5. On the HSD home screen, select the connection type supported by your head unit. Open the matching mode on the head unit and connect by USB or wirelessly. If Android asks which app should handle USB, choose **HSD**.

## Credits

Thanks to YegorkaPomidorka for the Baidu CarLife implementation, RotkivKiv for DriveDeck, and the Android Auto and CarPlay contributors. [Projects and contributors](CREDITS.md) · [Build from source](BUILDING.md) · [License](LICENSE)
