# Сборка Windows-приложения

## 1. Инструменты (один раз)

| Инструмент | Как установить |
|---|---|
| Visual Studio Build Tools 2022 с рабочей нагрузкой **«Разработка классических приложений на C++»** (MSVC + Windows 11 SDK) | <https://aka.ms/vs/17/release/vs_BuildTools.exe> |
| Rust stable (MSVC) | <https://rustup.rs> → `rustup default stable` |
| Node.js 20 или новее | <https://nodejs.org> |
| WebView2 Runtime | уже есть в Windows 11; для Windows 10 установщик скачает его сам |

Проверка:

```bash
rustc --version
node --version
```

## 2. Зависимости

```bash
cd desktop
npm install
```

## 3. Режим разработки

```bash
npm run tauri:dev
```

Откроется окно SwitchFlow с горячей перезагрузкой интерфейса. Rust-часть
пересобирается автоматически при изменении файлов в `src-tauri/src`.

## 4. Релизная сборка: `.exe` и установщики

```bash
npm run tauri:build
```

Результат:

| Файл | Что это |
|---|---|
| `desktop/src-tauri/target/release/switchflow.exe` | Само приложение (без установки) |
| `desktop/src-tauri/target/release/bundle/nsis/SwitchFlow_0.1.0_x64-setup.exe` | Установщик NSIS (русский и английский) |
| `desktop/src-tauri/target/release/bundle/msi/SwitchFlow_0.1.0_x64_en-US.msi` | Установщик MSI (для развёртывания в организациях) |

При первой сборке Tauri скачивает NSIS и WiX Toolset.

Установщик NSIS устанавливает приложение для всех пользователей (нужны права
администратора) и добавляет правило брандмауэра Windows, разрешающее входящие
подключения **только в частных сетях**. При удалении правило удаляется.

## 5. Подпись кода (для распространения)

Без подписи Windows SmartScreen покажет предупреждение. Получите сертификат подписи кода
(OV или EV) и укажите его в `desktop/src-tauri/tauri.conf.json`:

```json
"bundle": { "windows": { "certificateThumbprint": "<отпечаток>", "digestAlgorithm": "sha256", "timestampUrl": "http://timestamp.digicert.com" } }
```

Сертификат должен быть в хранилище сертификатов текущего пользователя.

## 6. Тесты

```bash
npm test             # интерфейс (Vitest)
npm run typecheck    # TypeScript strict
cd src-tauri && cargo test   # Rust: модульные + сквозные тесты протокола
```

## Где SwitchFlow хранит данные

`%LOCALAPPDATA%\app.switchflow.desktop\` — `settings.json`, `history.json`, `ledger.txt`,
`session.json` (незавершённый перенос), `tls\` (сертификат), `logs\`, `staging\`.
Резервные копии по умолчанию — `Документы\SwitchFlow Backups`.
