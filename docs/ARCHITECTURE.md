# Архитектура SwitchFlow

## Обзор

```
┌──────────────────────────── Windows (Tauri 2) ────────────────────────────┐
│  React UI (desktop/src)                     Rust core (desktop/src-tauri)   │
│  ┌──────────────┐  invoke / events   ┌─────────────────────────────────┐   │
│  │ UIStateManager│◀──────────────────▶│ commands.rs → Core (core.rs)    │   │
│  │ (zustand)     │  sf://snapshot      │  ├ pairing / devices / commands │   │
│  │ Screens, Motion│                    │  ├ engine.rs  (TransferEngine)  │   │
│  └──────────────┘                     │  ├ session.rs (TransferSession) │   │
│                                        │  ├ backup.rs, history.rs        │   │
│                                        │  └ discovery/ (WPD, SetupAPI,   │   │
│                                        │               ADB)              │   │
│                                        │ server.rs  HTTPS :47820 (axum + │   │
│                                        │            rustls, pinned cert) │   │
│                                        └───────────────┬─────────────────┘   │
└────────────────────────────────────────────────────────┼─────────────────────┘
                     TLS 1.2/1.3 · LAN или adb reverse    │
            ┌────────────────────────────┬────────────────┴─────────┐
   Android companion (Kotlin)        iOS companion (Swift)
   MediaStore · Contacts · Calendar  PhotoKit · Contacts · EventKit
```

ПК — проверяющий посредник: источник загружает файл на ПК, ПК проверяет SHA-256,
получатель забирает файл, сохраняет его системными средствами и подтверждает SHA-256.
Протокол — [PROTOCOL.md](PROTOCOL.md).

## Модули из технического задания

| Модуль | Где реализован |
|---|---|
| DeviceDiscovery | `src-tauri/src/discovery/` — `wpd.rs` (Windows Portable Devices), `setupapi.rs` (iPhone без драйвера), `adb.rs` |
| DeviceConnection | `core.rs` — `DeviceConn`, heartbeat, `check_liveness`, переподключение |
| PermissionManager | `core.rs::request_scan` (ПК), `CategoryPermissions` (Android), `authorize` (iOS) |
| TransferEngine | `engine.rs` — старт, проверки, загрузка/доставка, пауза, отмена, повтор |
| TransferSession | `session.rs` — элементы, состояния, прогресс, скорость/ETA |
| AndroidAdapter | `android/` — `transfer/`, `data/` |
| IOSAdapter | `ios/` — `Transfer/`, `Data/` |
| DataCompatibility | `shared/compatibility.json` + `compatibility.rs` + `src/lib/compat.ts` |
| StorageAnalyzer | `storage.rs` (место на ПК), heartbeat/prepare (место на телефоне) |
| ChecksumVerifier | `checksum.rs` (ПК), `Hashing` (Android/iOS) |
| TransferHistory | `history.rs` — история и журнал доставленного (`ledger`) |
| ErrorRecovery | `engine.rs` (`persist_if_dirty`, `resume_saved_session`, `on_device_lost/reconnected`, `retry_failed`) |
| SecureLocalTransport | `tls.rs` + `server.rs` + `ApiClient.kt` / `APIClient.swift` |
| UIStateManager | `snapshot.rs` (снимок состояния) + `src/state/store.ts` |

## Жизненный цикл элемента

```
Pending ─upload chunks─▶ Uploading ─complete+SHA ok─▶ Staged ─next─▶ Delivering ─ack SHA ok─▶ Delivered
   │                         │  SHA mismatch → Pending (заново)          │ ack failed / SHA mismatch
   └─fail (нет файла)────────┴──────────────▶ Failed ◀──────────────────┘   (повторить → Staged/Pending)
Skipped: уже перенесено ранее (ledger) или дубликат на получателе.
```

Прогресс считается в «единицах работы»: байты, принятые ПК, + байты, отданные получателю.
Скорость — по скользящему окну 6 секунд; ETA = оставшаяся работа / скорость.

## Конкурентность (Rust)

Всё состояние — под одним `std::sync::Mutex<State>`; критические секции короткие и не
пересекают `.await`. Длинные опросы (`/commands`, `/next`) подписываются на `tokio::watch`
до проверки условия, поэтому событие не теряется. Интерфейс получает снимок не чаще ~8 раз
в секунду. Запись чанков и хеширование выполняются вне блокировки.

## Дизайн-система

- Токены цвета — CSS-переменные на `[data-theme]` (`desktop/src/styles/index.css`), Tailwind 4 читает их через `@theme inline`.
- Тёмная тема: фон `#080B12`, вторичный `#111722`, карточки `#192231`, акцент `#478BFF`/`#75C8FF`, успех `#35C98A`, текст `#F6F8FC`/`#9CAAC0`. Светлая: фон `#F5F7FB`, карточки `#FFFFFF`, текст `#17202F`, акцент `#3478F6`.
- Шрифт: Segoe UI Variable (Display для заголовков, Text для текста) — системный шрифт Windows.
- Шкала: display 34 / title 24 / headline 17 / body 14 / callout 13 / caption 12 / micro 11.
- Поверхности: «матовое стекло» (`.glass`, `.glass-strong`) — полупрозрачный фон + `backdrop-filter`, тонкая граница 1px, мягкая тень.
- Движение (`src/lib/ui.ts`): micro 160 мс, small 220 мс, screen 340 мс, пружины для физических перемещений; анимируются только `opacity`/`transform`; бесконечные эффекты — CSS (`offset-path`, `stroke-dashoffset`).
- Иконки — Lucide; логотип и иллюстрация телефона — собственные SVG.
- Компоненты (`src/components/ui`): Button, IconButton, Card, Switch, Checkbox, Badge, Segmented, ProgressBar, Skeleton, Sheet, ConfirmDialog, AnimatedNumber, Toasts.
