# Pods Companion для Windows

Приложение в трее для AirPods и Beats на Windows 10/11: заряд наушников и кейса, датчик уха
и переключение между телефоном и ПК, похожее на iPhone и Mac.

## Что умеет

- Заряд левого, правого и кейса (у AirPods Max одно значение) в окне и в подсказке значка в трее.
- Пауза, когда снял наушник, и продолжение, когда надел обратно (Spotify, браузеры, плееры).
- **Переключение на ПК само**: включил звук на ПК, наушники надеты — ПК забирает их у телефона.
  Звук ставится на паузу, пока наушники подключаются, и продолжается уже в них.
- Пауза, когда наушники ушли к телефону, чтобы звук не пошёл в колонки.
- Кнопки «Подключить к ПК» и «Отключить (отдать телефону)».
- Запуск вместе с Windows.

Режимов шумоподавления пока нет: для них Windows требует драйвер ядра с подписью Microsoft.

## Как это устроено

- Заряд и датчик уха читаются из BLE-рекламы, которую AirPods рассылают всем рядом
  (`BluetoothLEAdvertisementWatcher`). Подключаться к наушникам для этого не нужно.
- Подключение и отключение — те же команды, что у кнопок в параметрах Bluetooth Windows
  (`KSPROPERTY_ONESHOT_RECONNECT/DISCONNECT` звукового драйвера Bluetooth). Без драйверов и прав администратора.
- Звук на ПК узнаём через системные медиаэлементы Windows (`GlobalSystemMediaTransportControlsSessionManager`).

## Сборка

Собирается в GitHub Actions (`.github/workflows/windows.yml`): один `PodsCompanion.exe`, .NET ставить не нужно.
Локально: `dotnet test windows/tests/PodsCompanion.Core.Tests` и
`dotnet publish windows/src/PodsCompanion.App -c Release -r win-x64 --self-contained true -p:PublishSingleFile=true`.

- `src/PodsCompanion.Core` — вся логика: разбор рекламы, Bluetooth, медиа, автопереключение.
- `src/PodsCompanion.App` — окно над треем (WPF, оформление Windows 11) и значок в трее.
- `tests/PodsCompanion.Core.Tests` — проверки на тех же реальных пакетах, что в Android-приложении.
