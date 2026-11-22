# HSD · HeadSwap Desktop

HSD выводит приложения с Android-телефона на экран автомобиля. В лаунчере **DriveDeck** можно открыть одно приложение на весь экран или разместить навигатор и плеер рядом. Касания на магнитоле управляют приложениями телефона. В Android Auto, CarLife и CarPlay музыку и голосовые подсказки можно передавать по разным каналам, если это поддерживает магнитола.

**Скачать для Android 10 и новее** · [HSD 0.8.3 (115) APK](https://github.com/SadykovIV/HSD/releases/download/v0.8.3/HSD-0.8.3-code115.apk) · [DriveDeck-HSD 0.7.0 (362) APK](https://github.com/SadykovIV/HSD/releases/download/v0.8.3/DriveDeck-HSD-0.7.0-code362.apk)

[Последний публичный релиз](https://github.com/SadykovIV/HSD/releases/latest) · [История изменений](CHANGELOG.md) · [English](#english)

## Поддерживаемые протоколы / Supported protocols

**0.8.3 (115)** — публичный выпуск. **0.8.4 RC26 (146)** — тестовая сборка для Android 11 и новее с DriveDeck-HSD 0.7.2 (370).

| Протокол магнитолы / Head unit protocol | 0.8.3 | 0.8.4 RC26 |
| --- | --- | --- |
| Android Auto | USB, Bluetooth + Wi-Fi | USB, Bluetooth + Wi-Fi |
| Baidu CarLife | USB, Wi-Fi | USB, Wi-Fi |
| Apple CarPlay | Bluetooth + Wi-Fi | Bluetooth + Wi-Fi, USB¹ |
| Huawei HiCar¹ | Bluetooth + Wi-Fi, USB (NCM) | Bluetooth + Wi-Fi, USB (NCM) |
| CarbitLink / EasyConnection¹ | — | USB, Wi-Fi |
| WeLink (Mapbar A1) | — | USB |

¹ Экспериментальный режим / Experimental mode.

В Android Auto обе версии также поддерживают подключение по локальной сети в режимах **Сервер на телефоне** и **Сервер на магнитоле**. Для проводного HiCar магнитола должна поддерживать USB NCM.

Проводной CarPlay и CarbitLink проверены на стенде. Музыка в CarbitLink идёт по Bluetooth автомобиля. Владелец Honda SN‑3 с обновлённым приёмником подтвердил работу WeLink на RC21.

## Первое подключение

1. Установите на телефон **оба APK** из одного выпуска. Откройте HSD, пройдите мастер активации и выберите DriveDeck.
2. В HSD откройте **Тип подключения** и выберите протокол магнитолы. Включите этот же режим на магнитоле. Для проводного подключения используйте её USB-порт проекции.

[Обсуждение на 4PDA](https://4pda.to/forum/index.php?showtopic=1129468) · [Группа в Telegram](https://t.me/+H2QUrHMGm-g3ODhi) · [Поддержать проект](https://pay.cloudtips.ru/p/41f6ecdd)

## English

HSD brings apps from your Android phone to the car display. DriveDeck lets you use one app full screen or place a map and music player side by side. Control both from the head unit’s touchscreen.

The table compares the **public 0.8.3 release** for Android 10 and later with **0.8.4 RC26**, a test build for Android 11 and later. Android Auto also supports a local network connection with either the phone or the head unit acting as the server. Wired HiCar requires a head unit with USB NCM.

Wired CarPlay and CarbitLink have been tested on a bench. CarbitLink uses the car’s Bluetooth for music. A Honda SN‑3 owner confirmed WeLink on RC21 with an updated receiver.

Install both APKs on your phone, open HSD and complete activation. Select DriveDeck, then select the same connection mode in HSD and on the head unit. [Download the latest release](https://github.com/SadykovIV/HSD/releases/latest) · [Changelog](CHANGELOG.md)
