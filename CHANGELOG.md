# Изменения / Changelog

## [HSD 0.8.2](https://github.com/SadykovIV/HSD/releases/tag/v0.8.2) · 2026-11-17

### Русский

Изменения относительно последнего опубликованного релиза **0.8.1**, включая доработки RC1–RC8. В комплекте **HSD 0.8.2 (104)** и **DriveDeck HSD 0.7.0 (357)**.

1. **Android Auto без установленного приложения AA и автоматическое обновление сертификатов.** HSD содержит проверенное удостоверение телефона и может обновлять его через публичный HTTPS-сервис Mgears без выпуска нового APK. Проверяются цепочка доверия, срок действия и соответствие ключа. Рабочий комплект сохраняется для подключения без интернета; неудачное обновление его не удаляет. В режиме «Автоматически» выбирается действующий комплект с наиболее поздним сроком из встроенного, скачанного и доступного в установленном AA. Автообновление можно отключить. Продление зависит от появления нового действующего комплекта на сервере.
2. **Меньше накопленной задержки изображения и управления в AA и CarLife.** В AA устранена двойная очередь видеокадров: устаревшее видео заменяется свежим ключевым кадром. В CarLife звук и команды проходят перед ожидающим видео между целыми сообщениями. Регулятор нагрузки учитывает реальный возраст кадров в очереди, передачу и обработку внутри телефона. Убрано двойное ограничение FPS в AA; буферы кодировщика освобождаются раньше. Лимит ожидания кадров AA в локальной очереди — 100 мс, а не предел общей задержки изображения.
3. **Переработан звук при звонках, камере заднего вида и отключении.** Потеря изображения при переходе на камеру больше не завершает аудиозахват. При запросе AA на приглушение музыка плавно уменьшается до 20%, затем восстанавливается; полная потеря аудиофокуса обрабатывается отдельно. CarLife переоткрывает медиапоток после звонка, не передавая накопленный старый звук. Завершение работавшего аудиосеанса по Wi-Fi ставит выбранный плеер на паузу, как при USB. Молчащий источник больше не блокирует остальные; исправлены потеря коротких подсказок и ошибки смешивания по времени.
4. **Раздельная громкость музыки и навигации.** Добавлены независимые регуляторы 0–100% с плавным изменением во время воспроизведения для звука HSD по AA, CarLife и CarPlay. Для навигаторов, обозначающих подсказки как обычную музыку, можно выбрать приложение отдельно: после переподключения весь его поток MEDIA относится к навигации. Прямой Bluetooth-звук в обход HSD этими регуляторами не управляется.
5. **DriveDeck на Android 17 после активации работает с выключенной отладкой.** Повторные подключения, изображение, звук, жесты и возврат из полноэкранного приложения используют живую активацию при выключенных USB- и беспроводной отладке. Исправлен порядок завершения сеанса и передачи поверхностей. Между сеансами остаются пять пустых служебных дисплеев 128×128 в спящей группе. Перезагрузка, принудительная остановка или завершение процесса могут потребовать новой активации.
6. **Исправления беспроводного CarPlay.** Переработан запуск Bluetooth/iAP2 и запрос полного сертификата магнитолы. Команды HID/iAP2 и ответы на события направляются в соответствующие каналы, ответы сопоставляются с запросами по CSeq. Исправлены обработка портов, ожидание ответов и освобождение аудиобуферов.
7. **Фоновое USB-подключение и штатный сон телефона.** При готовой активации подключение запускает службу проекции без открытия главного окна HSD. Если нужна настройка, предлагается открыть её из уведомления. По умолчанию используются обычные тайм-аут экрана и блокировка Android; управление экраном остаётся отдельной настройкой.
8. **Исправление масштаба карт и возврата приложений.** Автоматический DPI в AA рассчитывается по области рабочего стола; ручная настройка сохраняет приоритет. Коррекция DPI применяется ко всем выбранным приложениям. DriveDeck 357 восстанавливает приложение в его виджете после полноэкранного режима, сохраняя уже существующую задачу и открытые в ней страницы.
9. **Возвращён выбор FPS и добавлен переключатель синхронизации.** Доступны «Авто / 15 / 30 / 60», с учётом возможностей ГУ и тепловых ограничений телефона. Дополнительное выравнивание аудио и видео AA/CarLife можно отключить. Обязательные метки времени протоколов, в том числе CarPlay, сохраняются.
10. **Точнее состояние подключения, диагностика и чище код.** AA учитывает подтверждение проекции со стороны ГУ, а DISCOVERY передаёт полное описание телефона с иконками HSD. Общий отказ `AUTH -3` больше не объявляется доказанной ошибкой часов. Расширены журналы подключения, очередей и касаний; запись диагностики вынесена из потоков ввода и звука. Удалены недостижимый код старых Android и неиспользуемые ресурсы, выделены общие компоненты очередей и аудиотракта.

#### Android 10

- Минимальная версия остаётся Android 10, но на проверенном эмуляторе сохраняется зелёное/повреждённое изображение в Android Auto и CarLife. Этот дефект в 0.8.2 не устранён.
- На Android 10–12 работа приложений при блокировке зависит от прошивки и самого приложения; внешние касания могут откладывать выключение экрана телефона. Если окно перестало обновляться после защищённой блокировки, разблокируйте телефон.
- Если Android не выдаёт право на захват звука, используйте прямой Bluetooth-аудиовыход магнитолы. Раздельные регуляторы HSD на такой звук не действуют.
- Активация Android 10 выполняется через компьютер или второй Android-телефон с USB-OTG. После перезагрузки её нужно повторить.

#### Проверка

Прошли **1580 модульных тестов**; lint — без ошибок. Подписанный релиз прошёл **13 контрольных сценариев на эмуляторах Android 16–17**, включая обновление с 0.8.1/RC8 с сохранением данных и три подключения без отладки с одной активацией. Полная матрица того же кода RC8 охватывает Android 10–17: 71 успешная проверка и две ошибки изображения на Android 10; недоступные native-аудиопроверки Android 10–12 не считаются успешными. .

#### Обновление

С официальных 0.8.0/0.8.1 и кандидатов 0.8.2 установите **оба APK поверх существующих приложений**: настройки сохранятся. Для автоматического выбора сертификата укажите **«Автоматически»** в настройках AA; прежние ручные назначения для отдельных ГУ сохраняются, пока вы их не измените.

### English

Changes since the last published release, **0.8.1**, including RC1–RC8. Includes **HSD 0.8.2 (104)** and **DriveDeck HSD 0.7.0 (357)**.

1. **Standalone Android Auto and automatic certificate updates.** Google's AA app is optional. HSD verifies bundled, downloaded and installed-AA identities and selects the valid identity with the latest expiry. The public Mgears HTTPS feed supports renewal without replacing the APK; a verified offline cache survives failed updates. Updates can be disabled. Renewal depends on the upstream service publishing a compatible valid identity.
2. **Less accumulated video and input delay in AA and CarLife.** Removed AA's double video queue; stale frame chains recover with a fresh keyframe. CarLife prioritizes audio and commands over waiting video at complete-message boundaries. Adaptation uses actual queue, write and encoder age. Removed duplicate AA frame limiting and released encoder buffers earlier. AA's 100 ms pending-queue budget is not an end-to-end latency guarantee.
3. **Reworked audio across calls, camera transitions and disconnection.** Losing video ownership no longer ends capture. AA ducking requests smoothly lower PCM to 20% and restore it on gain; full focus loss remains separate. CarLife reinitializes media after calls without replaying stale audio. Losing an established wireless audio output pauses the selected player, as USB does. Silent sources no longer block other sources; short prompts and sample timing are corrected.
4. **Independent music and navigation volume.** Live 0–100% controls with smooth gain changes cover audio through HSD's AA, CarLife and CarPlay paths. Selected navigation apps can have their entire MEDIA output routed to the navigation control after reconnecting. Direct Bluetooth audio bypassing HSD is unaffected.
5. **Android 17 reconnects with debugging disabled.** A live activation supports video, audio, gestures and fullscreen return with USB and wireless debugging off. Session teardown and surface handover are corrected. Five empty 128×128 service displays remain in a sleeping group between sessions. Rebooting, force-stopping or losing the owning process may require activation again.
6. **Wireless CarPlay fixes.** Reworked Bluetooth/iAP2 setup and full accessory-certificate requests. HID/iAP2 commands and event replies use their respective channels; control replies match requests by CSeq. Corrected port handling, response waits and audio-buffer ownership.
7. **Background USB startup and normal phone sleep.** A ready activation starts projection without opening HSD's main window. Setup, when required, is offered through a notification. Android's normal screen timeout and lock settings are used by default.
8. **Map density and DriveDeck app restoration.** Automatic AA desktop DPI uses the actual desktop area, with manual overrides preserved. Density correction covers all selected apps. DriveDeck 357 restores missing apps to their tiles after fullscreen while preserving existing tasks and pages.
9. **FPS selection and optional A/V alignment.** Auto / 15 / 30 / 60 FPS choices respect receiver and thermal limits. Additional AA/CarLife A/V alignment can be disabled; mandatory protocol timestamps, including CarPlay's clocks, remain active.
10. **Connection state, diagnostics and refactoring.** AA readiness considers receiver confirmation; DISCOVERY includes the phone description and HSD icons. Generic `AUTH -3` no longer implies a clock error. Expanded negotiation/queue/input diagnostics, moved log writes off input/audio threads, and removed unreachable legacy code and unused resources.

#### Android 10

Android 10 remains the minimum version, but the tested Android 10 emulator still produces green/corrupt AA and CarLife pictures; this is unresolved in 0.8.2. On Android 10–12, locked-app behavior depends on the firmware and app, and remote input can postpone phone sleep. Unlock the phone if an embedded app stops updating after a secure lock. If playback capture is denied, use direct Bluetooth audio, which bypasses HSD's volume controls. Android 10 activation uses a computer or another Android phone over USB OTG and must be repeated after reboot.

#### Validation

**1,580 unit tests passed**, with no lint errors. The signed release passed **13 emulator checks on Android 16–17**, including data-preserving upgrades from 0.8.1/RC8 and three connections with debugging off under one activation. The preceding RC8 matrix for the same code covered Android 10–17: 71 passes and two Android 10 picture failures; unavailable native-audio checks on Android 10–12 are excluded from passes. .

#### Updating

Install **both APKs over the official 0.8.0/0.8.1 or 0.8.2 candidate apps** to keep settings. Choose **Automatic** in the AA certificate settings to use automatic selection; existing per-head-unit manual assignments are preserved until changed.

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
