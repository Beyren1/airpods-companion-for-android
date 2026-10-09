<p align="center"><img src="docs/images/icon.png" width="128" alt=""></p>

# Pods Companion for Android

Неофициальное приложение-компаньон для беспроводных наушников Apple на Android.
Не связано с Apple Inc. AirPods — товарный знак Apple Inc., он используется
только для указания совместимости.

*English summary below.*

## Что умеет

- Заряд левого и правого наушника и кейса: на главном экране, в уведомлении, в виджете
  и в плитке быстрых настроек. Окно с зарядом при первом открытии кейса.
- Автопауза, когда наушник вынимают из уха, и продолжение, когда возвращают.
- Режимы шумоподавления (выкл., прозрачность, адаптивный, шумоподавление) и их
  переключение из приложения и из плитки.
- Настройки наушников, которые они сами сообщают: действия при нажатии, адаптивная
  громкость, распознавание разговора и другие; отдельные настройки AirPods Max.
- Переименование наушников, ответ на звонок кивком и отклонение покачиванием головы.
- Уведомления о низком заряде.
- Музыка: текущий трек, запуск плеера при подключении, режим шумоподавления под приложение,
  умное продолжение воспроизведения.
- Статистика по каждой паре: время в ушах, музыка, звонки.
- Языки: английский, русский, французский. Светлая и тёмная тема.

Проверено на Google Pixel 9 (без root) с AirPods 4 (ANC), AirPods Pro 2 и AirPods Max (USB-C).
Остальные модели распознаются, но могут поддерживать не все функции.

## Установка

1. Откройте страницу [Releases](../../releases) и скачайте файл `PodsCompanion-<версия>.apk`.
2. Откройте его на телефоне. Android попросит разрешить установку из этого источника
   (браузера или «Файлов») — разрешите.
3. При первом запуске приложение попросит доступ к устройствам поблизости и уведомлениям.

Требуется Android 10 или новее. Root не нужен.

## Разрешения и приватность

Приложению не нужен интернет: в нём нет разрешения `INTERNET`, рекламы и аналитики.
Все данные (настройки, статистика) хранятся только на телефоне.
Подробно, зачем нужно каждое разрешение: [PRIVACY.md](PRIVACY.md).

## Сборка

Сборку делает GitHub Actions:

- `.github/workflows/android.yml` — тесты, debug- и fast-сборки на каждый PR;
- `.github/workflows/release.yml` — подписанная сборка выпуска и черновик Release по тегу `v*`.

Как выпустить новую версию: [docs/vypusk.md](docs/vypusk.md).

Локально: откройте папку в Android Studio или выполните `./gradlew assembleDebug`.
Тесты протокола: `./gradlew :protocol-aap:test`.

## Благодарности

Форматы пакетов Bluetooth взяты из публичных исследований протокола, прежде всего
[LibrePods](https://github.com/kavishdevar/librepods), а также CAPod и OpenPods,
и проверены на реальных наушниках. Код приложения написан с нуля.

---

## English

Pods Companion is an unofficial companion app for Apple wireless headphones on Android.
It shows the battery of each earbud and the case (app, notification, widget, Quick Settings tile,
case-open popup), pauses music when an earbud is taken out, switches noise control modes,
exposes the headphone settings the device reports (including AirPods Max), renames the headphones,
answers or declines calls with head gestures, warns about low battery, integrates with music players
and keeps per-pair usage statistics. No root required; tested on a Pixel 9 with AirPods 4 (ANC),
AirPods Pro 2 and AirPods Max (USB-C).

The app has no internet permission, no ads and no analytics; all data stays on the phone.
See [PRIVACY.md](PRIVACY.md). Download the APK from [Releases](../../releases).

Not affiliated with Apple Inc. AirPods is a trademark of Apple Inc.
