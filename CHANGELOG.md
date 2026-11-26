# Изменения / Changelog

## [HSD 0.8.4](https://github.com/SadykovIV/HSD/releases/tag/v0.8.4) · 2026-11-26

**HSD 0.8.4 (167) и DriveDeck-HSD 0.7.4 (374)** для Android 11 и новее. Стабильная версия на основе RC46.

**Для работы Android Auto (AA) требуются действительные сертификаты AA, которые принимает магнитола.** Установите выданный вам ZIP в **Настройки → Сертификаты → Установить из ZIP**. Выберите профиль в режиме Android Auto и нажмите **Сохранить**.

### Что изменилось с 0.8.3

- **Подключение.** Добавлены WeLink по USB, CarbitLink / EasyConnection по USB и Wi-Fi и экспериментальный ICCOA Carlink. У ICCOA на проводном стенде проверены изображение и касания, по Wi-Fi — поиск и сопряжение. Проводной CarPlay и HiCar также остаются экспериментальными.
- **Возврат к рабочему столу.** Android Auto восстанавливает изображение после штатного меню и камеры, когда магнитола возвращает управление. В CarbitLink кнопка с машинкой сохраняет сеанс и музыку. После звонка магнитола может снова открыть тот же рабочий стол.
- **Звук и касания.** Улучшен отклик проводного CarLife под нагрузкой. В CarPlay исправлены пропуски при нерегулярной подаче звука телефоном. Для разговоров появилась отдельная настройка громкой связи через Bluetooth или совместимое двустороннее USB-аудиоустройство на Android 12 и новее.
- **Звонки и уведомления.** На экране магнитолы доступны карточки вызовов и всплывающие уведомления. Свайп от верхней кромки открывает шторку. Уведомления можно раскрывать и использовать действия, которые предлагает приложение.
- **DriveDeck 0.7.4.** Обновлены телефонная книга и музыкальный виджет. Обложка открывает играющий плеер, кнопка «Домой» возвращает из звонилки на рабочий стол. На совместимых устройствах можно выбрать клавиатуру на телефоне или магнитоле. Настраивается порог предупреждения о превышении скорости.
- **Настройка HSD.** Мастер запрашивает разрешения по очереди. У параметров изображения, звука и экрана телефона появились короткие пояснения. Исправлен сброс затемнения телефона при касании экрана магнитолы.

**Обновление.** Установите оба APK поверх официальной 0.8.3 или тестовой 0.8.4. Если уже установлен DriveDeck 0.7.4 (374) из RC46, достаточно обновить HSD. На эмуляторе Android 14 проверены обновления с официальной 0.8.3 и с RC46 с сохранением настроек обоих приложений. Для Android 10 доступен [предыдущий стабильный выпуск 0.8.3](https://github.com/SadykovIV/HSD/releases/tag/v0.8.3).

### English

**HSD 0.8.4 (167) and DriveDeck-HSD 0.7.4 (374)** for Android 11 and later. This stable release is based on RC46.

**Android Auto (AA) requires valid AA certificates accepted by the head unit.** Install your issued ZIP under **Settings → Certificates → Install from ZIP**, select the profile in Android Auto mode, then tap **Save**.

- **Connections.** Added wired WeLink, USB and Wi-Fi CarbitLink / EasyConnection, and experimental ICCOA Carlink. ICCOA video and touch were tested on a wired bench receiver. Wireless discovery and pairing were tested separately. Wired CarPlay and HiCar also remain experimental.
- **Returning to projection.** Android Auto restores the picture when the head unit returns control after its native menu and camera. The car button in CarbitLink keeps the session and music running. After a call, the receiver can reopen the same desktop.
- **Audio and touch.** Wired CarLife touch response improved under load. CarPlay handles brief delays in the phone’s audio stream. On Android 12 and later, call audio can use a car’s Bluetooth hands-free profile or a compatible two-way USB audio device independently of music.
- **Calls and notifications.** Call cards and notification pop-ups appear on the head unit. Swipe down from the top edge to open the shade. Expand a notification to use the actions provided by its app.
- **DriveDeck 0.7.4.** Updated the contacts and music widgets. Album art opens the active player, and Home returns to the desktop from the dialer. Supported phones can show the keyboard on either display. The speed warning threshold is adjustable.
- **Setup.** The wizard requests permissions in sequence. Picture, audio and phone-screen settings show short descriptions. Touching the head unit now preserves HSD’s phone-screen dimming.

**Upgrade.** Install both APKs over official 0.8.3 or a 0.8.4 test build. If DriveDeck 0.7.4 (374) from RC46 is already installed, update HSD only. Both upgrade paths were tested on an Android 14 emulator with settings retained in both apps. Android 10 users can use [the previous stable release, 0.8.3](https://github.com/SadykovIV/HSD/releases/tag/v0.8.3).

## [HSD 0.8.3](https://github.com/SadykovIV/HSD/releases/tag/v0.8.3) · 2026-11-19

В релизе два APK — **HSD 0.8.3 (115)** и **DriveDeck HSD 0.7.0 (362)**. DriveDeck можно установить поверх версии из официального релиза 0.8.2 с сохранением настроек. [Скачать HSD](https://github.com/SadykovIV/HSD/releases/download/v0.8.3/HSD-0.8.3-code115.apk) · [Скачать DriveDeck для Android](https://github.com/SadykovIV/HSD/releases/download/v0.8.3/DriveDeck-HSD-0.7.0-code362.apk)

### Что изменилось с 0.8.2

1. **Музыка и подсказки по разным каналам.** В Android Auto, CarLife и CarPlay можно передавать стереомузыку в медиаканал магнитолы, а голос навигатора — в штатный канал подсказок. Если магнитола поддерживает только один канал, HSD смешивает звук. Для Android Auto и CarLife можно сохранить поправку синхронизации звука и изображения.
2. **Касания и повторное подключение.** После обрыва соединения посреди жеста сенсор снова принимает касания при подключении. Пропущенное касание больше не прерывает проекцию. HSD повторно ищет USB-магнитолу, если Android обнаруживает её с задержкой. Улучшено восстановление беспроводного CarPlay с ранее подключённой магнитолой.
3. **Новый протокол Huawei HiCar.** Добавлены два экспериментальных режима подключения — по Bluetooth и Wi-Fi и по кабелю USB. HSD запоминает выбор HiCar на магнитолах, которые также предлагают CarLife.
4. **DriveDeck и штатное меню.** В Android Auto, CarLife и CarPlay кнопка в DriveDeck открывает меню автомобиля. Чтобы вернуться, выберите проекцию на магнитоле. Обновление музыкальной карточки больше не ставит другие плееры на паузу. При отключении телефона воспроизведение ставится на паузу.
5. **Сон телефона.** При управлении с магнитолы телефон может погасить экран и заблокироваться по системному тайм-ауту. После отключения HSD освобождает блокировку сна. Если Android завершил сеанс доступа, повторную активацию можно открыть из уведомления о подключении USB.
6. **Размер приложения.** Оптимизирована упаковка ресурсов HSD. Сохранена поддержка ARM и x86, включая 32- и 64-битные устройства.

#### Ограничения

- Для Android Auto магнитола должна принимать профиль телефона. При необходимости его можно выбрать в системном хранилище Android.
- Проводной HiCar требует поддержки USB NCM со стороны магнитолы. CarPlay в HSD работает по беспроводному соединению.
- При высокой нагрузке в CarLife возможны короткие перебои звука. При программном кодировании видео первые касания могут отображаться с задержкой.
- На Android 10–12 работа приложений при заблокированном телефоне зависит от прошивки. Защищённое видео может отображаться чёрным экраном. Для автоблокировки при питании по USB отключите в параметрах разработчика Android настройку «Не выключать экран при зарядке».

### English

This release includes two APKs, **HSD 0.8.3 (115)** and **DriveDeck HSD 0.7.0 (362)**. Install DriveDeck over the version supplied with the official 0.8.2 release to keep your settings. [Download HSD](https://github.com/SadykovIV/HSD/releases/download/v0.8.3/HSD-0.8.3-code115.apk) · [Download DriveDeck for Android](https://github.com/SadykovIV/HSD/releases/download/v0.8.3/DriveDeck-HSD-0.7.0-code362.apk)

#### Changes since 0.8.2

1. **Separate channels for music and directions.** Android Auto, CarLife and CarPlay can send stereo music to the head unit’s media channel and spoken directions to its guidance channel. HSD mixes the two when the head unit supports a single channel. Android Auto and CarLife can also save an audio sync adjustment for your head unit.
2. **Touch input and reconnection.** If a connection drops mid-gesture, the touchscreen accepts input again when you reconnect. A missed touch no longer ends projection. HSD retries USB discovery when Android takes time to report the head unit. Wireless CarPlay reconnects more reliably to a previously paired head unit.
3. **Huawei HiCar support.** Two experimental connection modes are available — wireless over Bluetooth and Wi-Fi, and wired over USB. HSD remembers your HiCar selection on head units that also offer CarLife.
4. **DriveDeck and the car menu.** A DriveDeck button opens the car’s built-in menu in Android Auto, CarLife and CarPlay. Select projection on the head unit to return. Refreshing the music card no longer pauses other players. Playback pauses when the phone disconnects.
5. **Phone sleep.** The phone screen can turn off and lock on its normal timeout while you use the head unit. HSD releases its wake lock after disconnection. If Android ends desktop access, you can open the activation screen from the USB notification.
6. **App size.** HSD packages its resources more efficiently and retains support for 32-bit and 64-bit ARM and x86 devices.

#### Limitations

- Android Auto requires a phone profile accepted by the head unit. You can select a profile from Android storage when needed.
- Wired HiCar requires a head unit that supports USB NCM. HSD uses a wireless connection for CarPlay.
- Under heavy load, CarLife audio may briefly cut out. When video uses a software encoder, the first touches can take longer to appear.
- On Android 10–12, app behavior while the phone is locked depends on its firmware. Android may show protected video as a black screen. To use the normal lock timeout while charging over USB, turn off **Stay awake** in Android’s developer options.

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
