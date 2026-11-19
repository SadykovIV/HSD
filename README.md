# HSD

[Русский](README.ru.md) · [Download DriveDeck](https://github.com/SadykovIV/HSD/releases/latest) · [4PDA discussion](https://4pda.to/forum/index.php?showtopic=1129468) · [Telegram group](https://t.me/+H2QUrHMGm-g3ODhi) · [Support via CloudTips](https://pay.cloudtips.ru/p/41f6ecdd)

HSD brings **DriveDeck, a full car launcher**, to your head unit. Launch apps installed on your Android phone directly on the car screen, including apps outside the Android Auto catalog. Open an app full screen or keep navigation and a music player side by side. Control them using the head unit’s touchscreen.

| Head unit supports | Connect with |
| --- | --- |
| Android Auto | USB or wireless |
| Baidu CarLife | USB or wireless |
| CarPlay | Wireless |
| Huawei HiCar (experimental in 0.8.3) | Bluetooth/Wi-Fi or USB NCM on compatible receivers |

HSD installs on Android 10 and later. With Android Desktop or DriveDeck, you can turn the phone screen off or let it time out normally. On Android 10–12, the phone firmware and each app determine which windows remain visible and interactive while locked. If an app on the head unit stops updating after the phone locks with a PIN, password or pattern, unlock the phone to resume it. When Android denies playback capture, select Bluetooth audio on the head unit. Samsung DeX is available on compatible Samsung phones.
![DriveDeck on the author’s head unit, with music on the left and the map on the right](docs/hsd-head-unit.jpg)
[![Yandex Maps and Yandex Music running side by side in DriveDeck](docs/drivedeck.png)](docs/drivedeck.png)

[![HSD 0.7.3 on the phone: home, connection types and settings](docs/hsd-interface-en.png)](docs/hsd-interface-en.png)

See how the phone, launcher and head unit exchange video, audio and input. [System diagrams and project map](docs/architecture.md)

[What’s new in HSD 0.8.3 since 0.8.2](docs/releases/0.8.3.md#english) · [Known limitations](docs/releases/0.8.3.md#limitations).

## Connect your phone

**Upgrading from 0.7.3 or earlier?** Both apps now use Ildar Sadykov’s signing key. Uninstall the old HSD and DriveDeck-HSD, install compatible versions of both apps, then activate HSD again. Uninstalling erases the apps’ local settings.

1. HSD 0.8.3 is available as source. With HSD already installed, you can update DriveDeck separately. [Build HSD](BUILDING.md) · [Download DriveDeck 362](https://github.com/SadykovIV/HSD/releases/download/v0.8.3/DriveDeck-HSD-0.7.0-code362.apk)
2. For Android Auto, select a phone profile in HSD that your head unit accepts. You can use the local HSD profile or select a profile from Android storage.
3. Open HSD, grant the permissions requested by the setup wizard and select **DriveDeck**.
4. On Android 10, enable **USB debugging** on the phone used in the car. Install the same HSD APK on a second Android with USB OTG, connect the phones with a data cable, then select **Activate HSD on another phone** on the second phone. Approve debugging on the car phone. Unplug when HSD confirms activation. Repeat after a reboot. For a PC, select **Use a computer**. On Android 11 and later, use a computer or pair over Wi-Fi. For Wi-Fi pairing, connect the phone to a Wi-Fi network. Enable **Wireless debugging** in Developer options. In HSD, open **Settings → Activation → Wi-Fi** and tap **Activate**. HSD reconnects automatically if Android has saved the pairing. For first-time pairing, open **Wireless debugging → Pair device with pairing code** and enter the six digits in the HSD notification while the dialog stays open. On Android 17, after activation succeeds, USB and wireless debugging can be turned off for repeated connections while the activation process remains alive. Rebooting, force-stopping or losing that process may require activation again.
5. On the HSD home screen, select the connection type supported by your head unit. Open the matching mode on the head unit and connect by USB or wirelessly. For wired Android Auto or CarLife, choose **HSD** if Android asks which app should handle USB. For wired HiCar, save **Huawei HiCar · USB (NCM)**, plug in the cable and press **Connect**. [HiCar compatibility and setup](docs/hicar.md)

## Credits

Thanks to YegorkaPomidorka for the Baidu CarLife implementation, RotkivKiv for DriveDeck, and the Android Auto and CarPlay contributors. [Projects and contributors](CREDITS.md) · [Build from source](BUILDING.md) · [License](LICENSE)
