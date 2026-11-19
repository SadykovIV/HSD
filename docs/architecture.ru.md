# Как работает HSD

[English](architecture.md) · [Подключение](../README.ru.md#как-подключиться)

## Телефон и магнитола

```mermaid
flowchart LR
  subgraph Phone["Телефон"]
    Apps["Приложения телефона"] --> Desk["DriveDeck / Android Desktop / Samsung DeX"]
    Desk -->|"Кадры экрана"| HSD["Сеанс проекции HSD"]
    Music["Звук телефона"] -->|"Захват звука, если включён"| HSD
    HSD -->|"Касания и кнопки"| Desk
    Nav["Подсказки навигатора"] -->|"Данные манёвра CarLife"| HSD
  end
  HSD -->|"Видео и согласованный звук"| HU["Магнитола"]
  HU -->|"Касания и кнопки"| HSD
  Music -. "Звук по Bluetooth, если выбран" .-> HU
  HSD -. "CarLife: поворот, улица и расстояние" .-> HUD["Приборная панель / HUD"]
```

Приложения работают на телефоне, магнитола показывает выбранный рабочий стол. Совместимая приборная панель может получать подсказки поворотов независимо от видео и музыки.

## Способы подключения

```mermaid
flowchart LR
  H["HSD"] --> AA["Android Auto"]
  H --> CL["Baidu CarLife"]
  H --> CP["CarPlay"]
  AA --> AU["USB в режиме аксессуара"]
  AA --> AW["Согласование по Bluetooth → Wi-Fi"]
  AA --> AL["Локальная сеть → сервер на ГУ или телефоне"]
  CL --> CU["USB в режиме аксессуара"]
  CL --> CW["Wi-Fi / Wi-Fi Direct"]
  CP --> PW["Согласование iAP2 по Bluetooth → проекция по Wi-Fi"]
```

## Карта проекта

| Задача | Исходный код |
| --- | --- |
| Выбор подключения и управление сеансом | [ProjectionRoute](../android/app/src/main/java/com/hsd/universal/ProjectionRoute.kt), [AutoService](../android/app/src/main/java/com/hsd/universal/AutoService.kt) |
| Сеансы Android Auto, CarPlay и CarLife | [AutoProjection](../android/app/src/main/java/com/hsd/universal/AutoProjection.kt), [CarPlayProjection](../android/app/src/main/java/com/hsd/universal/CarPlayProjection.kt), [CarLifeSession](../android/app/src/main/java/com/hsd/universal/CarLifeSession.kt) |
| Сетевой и проводной транспорт | [Android Auto](../android/aap-transport/), [CarLife](../android/app/src/main/java/com/hsd/universal/link/) |
| Описание телефона AA: иконки, модель и стабильный ID независимо от сертификата | [ProjectionDescription](../android/app/src/main/java/com/hsd/universal/ProjectionDescription.kt), [PhoneDescription](../android/aap-transport/src/main/java/net/prototux/aare/aap/PhoneDescription.kt) |
| Профили телефона и сохранённые магнитолы Android Auto | [ProjectionIdentity](../android/app/src/main/java/com/hsd/universal/ProjectionIdentity.kt) |
| Общая временная шкала CarLife и очереди без блокировки аудио ожидающим видео | [DesktopAvTimeline](../android/app/src/main/java/com/hsd/universal/link/DesktopAvTimeline.kt), [StreamTransport](../android/app/src/main/java/com/hsd/universal/link/StreamTransport.kt), [MultiSocketTransport](../android/app/src/main/java/com/hsd/universal/link/MultiSocketTransport.kt) |
| Активация, виртуальные дисплеи, ввод и захват звука | [Службы рабочего стола](../android/app/src/main/java/com/hsd/universal/dex/) |
| Модуль DriveDeck и интерфейс внешних панелей | [Модуль](../tools/drivedeck-companion/), [SDK рабочих столов](../sdk/desktop-panel-v1/) |
| Подсказки на приборной панели и HUD | [Источники навигации](../android/app/src/main/java/com/hsd/universal/cluster/), [Метаданные CarLife](../android/app/src/main/java/com/hsd/universal/aa/AaMeta.kt) |
