# Политика конфиденциальности Pods Companion

*Обновлено: 9 октября 2026 г. English version below.*

Pods Companion не собирает, не передаёт и не продаёт никаких данных. В приложении нет
разрешения на доступ к интернету, рекламы, аналитики и сторонних SDK, которые отправляют данные.

Всё, что приложение хранит (настройки, имена и ключи ваших наушников, статистика использования,
последнее место наушников),
лежит только в его личной папке на телефоне. Оно удаляется вместе с приложением и не попадает
в облачную резервную копию Android.

## Зачем нужны разрешения

| Разрешение | Зачем |
|---|---|
| Устройства поблизости (Bluetooth: поиск и подключение) | Видеть пакеты, которые наушники рассылают с зарядом, и подключаться к ним напрямую для настроек и режимов шумоподавления. На Android 12+ поиск идёт без определения местоположения. |
| Местоположение | Только для «Последнего места» на вкладке «Найти»: когда наушники отключаются от телефона, приложение запоминает, где был телефон в этот момент. Хранится одна точка на каждую пару наушников, только на телефоне. «Разрешать всегда» нужно, потому что наушники обычно отключаются, когда приложение закрыто. Просится, только если вы нажмёте «Разрешить» на этой вкладке. На Android 10–11 без местоположения ещё и не работает поиск Bluetooth-устройств. |
| Уведомления | Уведомление с зарядом, предупреждения о низком заряде, запуск плеера. |
| Работа в фоне, запуск после включения телефона | Следить за наушниками, пока приложение закрыто: автопауза, виджет, плитка. |
| Телефон: состояние звонка и ответ на звонок | Только для жестов головой: узнать, что идёт входящий звонок, и ответить или отклонить его кивком. Номера и журнал звонков приложение не читает. Разрешение просится, только если вы включаете жесты. |
| Поверх других окон | Показать окно с зарядом при открытии кейса. Просится, только если вы включаете это окно. |
| Доступ к уведомлениям | Только для функций музыки: без него Android не показывает приложению, какой плеер сейчас играет и какой трек. Сами уведомления приложение не читает вообще. Доступ даётся вручную в настройках Android, только если вы включаете эти функции. |

Статистика (время в ушах, музыка, звонки) считается на телефоне и хранит только длительности
по каждой паре наушников за последние 30 дней, без названий треков и номеров.

## Связь

Вопросы и сообщения об ошибках: [Issues](https://github.com/Beyren1/airpods-companion-for-android/issues).

---

# Pods Companion privacy policy

*Updated October 9, 2026.*

Pods Companion does not collect, transmit or sell any data. The app has no internet permission,
no ads, no analytics and no third-party SDKs that send data anywhere.

Everything the app stores (settings, your headphones' names and keys, usage statistics, the headphones'
last place) stays in the
app's private storage on your phone. It is removed with the app and excluded from Android cloud backup.

Permissions are used only for these purposes: Bluetooth (read battery broadcasts and connect to the
headphones for settings), location (only for "Last place" in the Find tab: when the headphones
disconnect, the phone's position at that moment is saved, one point per pair, on the phone only; "all the time"
is needed because headphones usually disconnect while the app is closed; also required by Android 10–11 for
Bluetooth scanning), notifications, background service and start on boot (auto-pause,
widget, tile), phone state and answering calls (only for head gestures, no numbers or call log are
read), display over other apps (only for the case-open popup), and notification access (only for
music features: Android requires it to let the app see which player and track is playing; the app never reads
notifications themselves).

Contact: [GitHub Issues](https://github.com/Beyren1/airpods-companion-for-android/issues).
