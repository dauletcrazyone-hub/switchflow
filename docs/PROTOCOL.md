# SwitchFlow Local Transfer Protocol (SLTP) v1

Контракт между Windows-приложением (сервер) и приложениями-компаньонами
Android/iOS (клиенты). Все три реализации обязаны следовать этому документу.

## Транспорт

- HTTPS (TLS 1.2/1.3) на порту `47820` (если занят — любой свободный, фактический порт передаётся в QR).
- Сертификат ПК самоподписанный (ECDSA P-256), хранится в `%LOCALAPPDATA%\SwitchFlow\tls`.
- Клиент **обязан** проверять SHA-256 отпечаток DER-сертификата сервера (pinning) и не доверять никаким другим сертификатам. Проверка имени хоста не используется — её заменяет pinning.
- Все тела запросов и ответов — JSON (UTF-8, `camelCase`), кроме передачи содержимого файлов (`application/octet-stream`).
- Ошибки: HTTP-код + `{"error": "<код>", "message": "<текст для логов, без личных данных>"}`.

## Транспорт по кабелю (без отладки по USB и без QR-кода)

Тот же HTTPS, только вместо сети — кабель. Телефон слушает локальный порт `127.0.0.1:47821`;
каждое TCP-соединение приложения к нему становится потоком внутри одного кабельного канала:

| Платформа | Кабельный канал | Что нужно |
|---|---|---|
| Android | USB-аксессуар (Android Open Accessory 1.0): ПК переводит телефон в режим аксессуара (`18D1:2D00/2D01`), приложение открывает его по фильтру `manufacturer="SwitchFlow" model="SwitchFlow Cable"` | Драйвер UsbDk на ПК (ставит установщик); «ОК» в системном окне телефона |
| iPhone | usbmuxd (Apple Mobile Device Service): ПК подключается к порту `47822` приложения на iPhone; приложение слушает **только** `127.0.0.1`, по Wi-Fi к порту не подключиться | «Доверять этому компьютеру»; приложение SwitchFlow открыто |

Кадр: `тип (1 байт) · поток (4, big-endian) · длина (4) · данные`, не больше 16 384 байт
(Android читает аксессуар кусками по 16 КиБ). Длина кадра никогда не кратна 64 — иначе у
USB-передачи нет короткого пакета в конце и телефон ждёт продолжения.

| Тип | Кто | Смысл |
|---|---|---|
| 5 `READY` | телефон | приложение открыло канал (после перезапуска приложения — снова) |
| 1 `HELLO` | ПК | JSON `{"v":1,"uri":"switchflow://pair?..."}` — то же, что в QR-коде: адреса, порт, отпечаток сертификата, одноразовый секрет |
| 2 `OPEN` | телефон | новое соединение приложения к `127.0.0.1:47821` |
| 3 `DATA` | оба | байты потока |
| 4 `CLOSE` | оба | поток закрыт |

По `HELLO` телефон либо переводит уже существующее сопряжение с этим ПК (тот же отпечаток) на
кабель, либо начинает обычное сопряжение — с кодом на экране и подтверждением на ПК, но без камеры.
При отключении кабеля телефон пробует адреса из `HELLO` и продолжает по Wi-Fi.

## QR-код сопряжения

```
switchflow://pair?v=1&hosts=192.168.1.5,127.0.0.1&port=47820&fp=<64 hex>&s=<secret>&n=<имя ПК>
```

| Параметр | Значение |
|---|---|
| `v` | версия протокола, `1` |
| `hosts` | адреса ПК через запятую, по приоритету; `127.0.0.1` — для подключения по USB через `adb reverse` |
| `port` | порт сервера |
| `fp` | SHA-256 DER-сертификата, 64 hex-символа в нижнем регистре |
| `s` | одноразовый секрет сопряжения (base64url, 16 байт), действует 10 минут |
| `n` | имя компьютера (URL-encoded) |

Клиент пробует адреса по порядку (таймаут 3 с на адрес) и запоминает первый ответивший.

## Модели

```ts
type Platform = "android" | "ios";
type Category = "photos" | "videos" | "contacts" | "documents" | "music" | "calendars";
type Permission = "granted" | "limited" | "denied" | "unavailable";

interface DeviceInfo {
  id: string;               // UUID, генерируется один раз при установке приложения
  platform: Platform;
  manufacturer: string;     // "Google", "Apple"
  model: string;            // "Pixel 8", "iPhone 15 Pro"
  osVersion: string;        // "15", "18.1"
  name: string;             // имя устройства, заданное пользователем
  appVersion: string;
  storageFreeBytes: number;
  storageTotalBytes: number;
  batteryPercent: number | null;
  charging: boolean | null;
}

interface SourceItem {
  id: string;               // локальный id источника (может содержать любые символы)
  category: Category;
  name: string;             // имя файла
  size: number;             // байты
  sizeExact: boolean;       // false, если размер оценочный (iOS)
  mime: string;
  createdMs: number | null; // дата съёмки/создания
  modifiedMs: number | null;
  albumIds: string[];
  itemCount: number | null; // для contacts/calendars: число записей внутри файла
}

interface Album { id: string; name: string; count: number; totalBytes: number; }

interface CategoryInventory {
  category: Category;
  permission: Permission;
  count: number;            // логическое число элементов (фото, контактов, событий)
  totalBytes: number;
  albums: Album[];
  items: SourceItem[];
  note: string | null;      // код пояснения, например "icloud_only_items"
}
```

## 1. Сопряжение

### `POST /v1/pair`
```json
{ "secret": "…", "device": DeviceInfo }
```
→ `200 { "pairingId": "…", "verificationCode": "482913", "expiresInSec": 120 }`
→ `403 bad_secret` (после 5 неверных попыток секрет меняется), `429 too_many_requests`.

Телефон показывает `verificationCode`. ПК показывает запрос с тем же кодом и кнопками «Разрешить» / «Отклонить».

### `GET /v1/pair/{pairingId}`
→ `200 { "status": "pending" | "approved" | "denied" | "expired", "token": string | null, "desktopName": "…" }`

Клиент опрашивает раз в секунду. После `approved` все запросы ниже требуют заголовок
`Authorization: Bearer <token>`. Без токена — `401 unauthorized`.

## 2. Состояние и команды

### `POST /v1/heartbeat`
Каждые 5 секунд.
```json
{ "storageFreeBytes": 0, "storageTotalBytes": 0, "batteryPercent": 80, "charging": false, "appState": "foreground" }
```
→ `200 { "role": "none" | "source" | "target" }`

Если ПК не получает heartbeat 15 секунд, устройство считается потерянным, а активный перенос переходит в состояние `waiting_reconnect`.

### `GET /v1/commands?after=<seq>&wait=25`
Длинный опрос. → `200 { "commands": [ { "seq": 7, "type": "…", "payload": { … } } ] }`.
Пустой массив — таймаут, клиент повторяет запрос.

| `type` | Кому | `payload` |
|---|---|---|
| `role` | обоим | `{ "role": "source" \| "target" \| "none" }` |
| `scan` | источнику | `{ "categories": Category[] }` — запросить разрешения **только** на эти категории и прислать `inventory` |
| `prepare` | получателю | `{ "sessionId", "categories": Category[], "totalBytes", "contactsPolicy": "merge" \| "skip" \| "keepBoth" }` — запросить разрешения на запись, проверить место, ответить `prepare-result` |
| `upload` | источнику | `{ "sessionId", "items": [ { "key": "i17", "sourceId": "…" } ] }` |
| `download` | получателю | `{ "sessionId" }` — начать забирать файлы через `next` |
| `pause` / `resume` / `cancel` | обоим | `{ "sessionId" }` |
| `unpair` | обоим | `{}` |

### `POST /v1/inventory`
```json
{ "categories": [ CategoryInventory ] }
```

### `POST /v1/prepare-result`
```json
{ "sessionId": "…", "ok": true, "permissions": { "photos": "granted" }, "storageFreeBytes": 0, "error": null }
```

## 3. Загрузка с источника на ПК

Ключ `key` выдаёт ПК (URL-безопасный, например `i17`).

| Запрос | Ответ |
|---|---|
| `GET /v1/sessions/{sid}/items/{key}/upload-status` | `{ "receivedBytes": n, "state": "pending" \| "receiving" \| "staged" \| "skipped" \| "failed" }` |
| `PUT /v1/sessions/{sid}/items/{key}/chunk?offset=n` (тело ≤ 16 МиБ) | `200 { "receivedBytes" }`; `409 offset_mismatch { "receivedBytes" }`; `423 paused`; `410 cancelled` |
| `POST /v1/sessions/{sid}/items/{key}/complete` `{ "size", "sha256" }` | `200 { "state": "staged" }`; `422 checksum_mismatch` (ПК удалил данные, нужно загрузить заново с нуля) |
| `POST /v1/sessions/{sid}/items/{key}/fail` `{ "error": "<код>" }` | `200` — источник не смог прочитать элемент |

Источник считает SHA-256 по мере чтения. ПК после `complete` пересчитывает SHA-256 всего
сохранённого файла и сравнивает. Совпадение — файл получает состояние `staged`.

## 4. Доставка с ПК на получатель

### `GET /v1/sessions/{sid}/next?wait=25`
```json
{ "state": "item", "item": {
    "key": "i17", "category": "photos", "name": "IMG_0001.HEIC", "size": 2345678,
    "sha256": "…", "mime": "image/heic", "createdMs": 0, "modifiedMs": 0,
    "albumName": "Отпуск", "itemCount": null, "contactsPolicy": "merge" } }
```
или `{ "state": "wait" | "paused" | "done" | "cancelled" }`.

Элемент, выданный через `next`, остаётся закреплённым за получателем; если получатель
переподключился, `next` сначала вернёт незавершённые элементы.

### `GET /v1/sessions/{sid}/items/{key}/content`
Поддерживает `Range: bytes=n-` → `206 Partial Content` для докачки.

### `POST /v1/sessions/{sid}/items/{key}/ack`
```json
{ "result": "saved" | "duplicate" | "failed",
  "sha256": "…",
  "verification": "read_back" | "written_file",
  "error": null,
  "detail": { "imported": 0, "merged": 0, "skipped": 0 } }
```

- `read_back` — получатель перечитал сохранённые данные из системного хранилища и посчитал SHA-256 (Android).
- `written_file` — SHA-256 файла, переданного системному API (iOS PhotoKit может хранить данные иначе, поэтому перечитать байт-в-байт нельзя).
- `saved` с несовпадающим `sha256` ПК считает ошибкой и предлагает повторить.

## Коды ошибок

`bad_secret`, `unauthorized`, `not_found`, `offset_mismatch`, `paused`, `cancelled`,
`checksum_mismatch`, `permission_denied`, `insufficient_storage`, `io_error`,
`unsupported_format`, `icloud_unavailable`, `source_read_failed`, `too_large`.

## Безопасность

- Секрет сопряжения одноразовый по времени (10 минут), меняется при каждом открытии окна сопряжения и после 5 ошибок.
- Токен — 32 случайных байта, действует до `unpair` или «Сбросить ключи и сопряжения». ПК хранит только его
  SHA-256 (`devices.json`), поэтому сопряжение переживает перезапуск, а сам токен с диска не восстановить.
- Номера команд: телефон помнит последний полученный `seq`. Если после перезапуска ПК телефон спрашивает
  `after` больше выданных номеров, ПК продолжает нумерацию с `after + 1`.
- Содержимое файлов, имена файлов и данные контактов никогда не пишутся в журналы.
- ПК не делает исходящих соединений: телефоны подключаются сами.
