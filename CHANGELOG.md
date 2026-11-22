# Изменения / Changelog

## [HSD 0.8.1](https://github.com/SadykovIV/HSD/releases/tag/v0.8.1) · 2026-11-14

### English

- **CarLife** restores the picture after a decoder reset without unplugging the cable. Fixed desktop freezes when the phone screen turns off. On Android 10–12, locked-screen behavior depends on the firmware and app.
- Fixed duplicate touches that interrupted map dragging and pinch-to-zoom.
- **DriveDeck 0.7.0 (356)** brings back Back and the app menu in fullscreen. Double-tap Back to return home. You can remove the gap between windows and hide the bottom bars.
- **Android Auto** waits for the head unit to start the USB session. Certificate settings remain available after a connection failure. **Wireless CarPlay** now completes Bluetooth pairing before switching to Wi-Fi.
- **Activation** on Android 10 now works over USB OTG from a second Android phone running HSD. Android 11 and later can reuse a saved Wi-Fi pairing.

**Updating from 0.8.0?** Install both APKs over the existing apps to keep your settings.

### Русский

- **CarLife** восстанавливает картинку после сброса видеодекодера без переподключения кабеля. Исправили зависание рабочего стола при выключении экрана телефона. На Android 10–12 работа при блокировке зависит от прошивки и приложения.
- Исправили повторные касания, из-за которых прерывалось перемещение и масштабирование карты.
- В **DriveDeck 0.7.0 (356)** вернули «Назад» и меню приложений в полноэкранном режиме. Двойное нажатие «Назад» возвращает на рабочий стол. Зазор между окнами и нижние полоски можно убрать.
- **Android Auto** ждёт начала USB-сеанса со стороны магнитолы и сохраняет доступ к настройкам сертификатов при ошибке. **Беспроводной CarPlay** завершает сопряжение по Bluetooth перед переходом на Wi-Fi.
- **Активация на Android 10** теперь доступна со второго Android-телефона с HSD по USB-OTG. На Android 11 и новее можно повторно использовать сохранённое сопряжение по Wi-Fi.

**Переходите с 0.8.0?** Установите оба APK поверх текущих приложений. Настройки сохранятся.

## [HSD 0.8.0](https://github.com/SadykovIV/HSD/releases/tag/v0.8.0) · 2026-11-13

### English

- HSD now supports **Android 10 and later**. On Android 10, activate from a computer over USB. Android 11 and 12 also support Wi-Fi pairing with a six-digit code. On Android 10–12, DriveDeck’s split view depends on secondary-display support in the firmware and in each app. If the phone restricts audio capture, use Bluetooth for music.
- Updated **DriveDeck to 0.7.0 (350)** and fixed a crash when opening the launcher settings. Video encoding has also improved on older phones.
- Android Auto handles head units that split connection data across packets. HSD shows certificate errors and flags Wi-Fi access restrictions when a VPN is active. Wireless CarPlay improves discovery over Bluetooth and Wi-Fi, including connections initiated by the head unit.
- CarLife adapts video to slow transfers to reduce lag during touch gestures. Unplugging the USB cable pauses playback.

**Upgrading from 0.7.3 or earlier requires a fresh install.** The signing key changed for both APKs. Uninstall the old HSD and DriveDeck-HSD, install both APKs below and activate HSD again. Uninstalling clears their local settings.

**Already on HSD 0.8.0?** Install DriveDeck build 350 over build 349. Your layout and HSD activation carry over.

### Русский

- HSD теперь поддерживает **Android 10 и новее**. На Android 10 активируйте HSD с компьютера по USB. На Android 11 и 12 доступна также активация по Wi-Fi с шестизначным кодом. На Android 10–12 для двух окон DriveDeck нужна поддержка второго экрана в прошивке и в самих приложениях. Если телефон ограничивает захват звука, выводите музыку по Bluetooth.
- Обновлён **DriveDeck до 0.7.0 (350)**. Исправлен вылет при открытии настроек лаунчера. Улучшено кодирование видео на старых телефонах.
- Исправлено подключение Android Auto к магнитолам, которые передают данные по частям. HSD показывает ошибки сертификата и подсказывает, если при включённом VPN закрыт доступ к Wi-Fi магнитолы. В беспроводном CarPlay улучшен поиск по Bluetooth и Wi-Fi, в том числе когда подключение начинает магнитола.
- CarLife подстраивает передачу видео под скорость соединения, чтобы экран быстрее реагировал на касания. При отключении USB-кабеля музыка ставится на паузу.

**При переходе с 0.7.3 или более ранней версии нужна переустановка.** У обоих APK сменился ключ подписи. Удалите прежние HSD и DriveDeck-HSD, установите оба APK из релиза и заново активируйте HSD. При удалении локальные настройки сбросятся.

**Если уже стоит HSD 0.8.0**, установите только DriveDeck 350 поверх сборки 349. Раскладка и активация HSD сохранятся.

## [HSD 0.7.3](https://github.com/SadykovIV/HSD/releases/tag/v0.7.3) · 2026-11-13

### English

- Added an English interface.
- Fixed Samsung DeX startup when the phone switches to the head unit’s Wi-Fi.
- CarLife keeps music playing during map zoom and preserves Bluetooth playback when returning from a camera. Turn guidance now includes the street name when the navigation app provides it.

### Русский

- Добавлен английский интерфейс.
- Исправлен запуск Samsung DeX при переходе телефона в сеть Wi-Fi магнитолы.
- В CarLife музыка продолжает играть при масштабировании карты и по Bluetooth при возврате с камеры. Вместе со стрелкой поворота на приборку передаётся название улицы, если его сообщает навигатор.

## [HSD 0.7.2](https://github.com/SadykovIV/HSD/releases/tag/v0.7.2) · 2026-11-13

### English

- Wireless CarPlay now detects the phone’s Bluetooth address automatically, scales touch coordinates to the head unit’s display and supports AAC-LC audio.
- CarPlay keeps streaming when the head unit delays its feedback and reconnects after brief Wi-Fi interruptions.
- Android Auto now retries audio frames when the AAC encoder is briefly busy.
- DriveDeck gives Shizuku more time to start before reporting a connection error.

### Русский

- Беспроводной CarPlay автоматически определяет Bluetooth-адрес телефона, масштабирует координаты касаний под экран магнитолы и поддерживает звук AAC-LC.
- CarPlay продолжает передачу при задержке ответа магнитолы и восстанавливает соединение после короткого обрыва Wi-Fi.
- Android Auto повторно передаёт аудиокадры, если кодировщик AAC занят.
- DriveDeck дольше ожидает запуска Shizuku.

## [HSD 0.7.0](https://github.com/SadykovIV/HSD/releases/tag/v0.7.0) · 2026-11-12

### English

DriveDeck brings a full car launcher to your head unit. Launch apps from your phone full screen or use navigation and music side by side. Connect via Android Auto or Baidu CarLife over USB or Wi-Fi, or via wireless CarPlay.

Install **HSD-0.7.0.apk** and **DriveDeck-HSD-0.6.9-code338.apk**. If DriveDeck code338 is already installed, update HSD only.

### Русский

DriveDeck выводит полноценный автомобильный лаунчер на экран магнитолы. Запускайте приложения телефона на весь экран или открывайте карту и плеер рядом. Android Auto и Baidu CarLife поддерживают USB и Wi-Fi, CarPlay работает по беспроводному соединению.

Установите HSD и DriveDeck-HSD. Если DriveDeck уже обновлён до сборки 338, достаточно установить HSD.
