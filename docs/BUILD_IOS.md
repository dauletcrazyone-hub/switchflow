# Сборка и подпись iOS-компаньона

> Нативная сборка iOS возможна только на macOS с Xcode — но свой Mac не обязателен.

## Без Mac: облачная сборка (GitHub Actions)

`.github/workflows/ios.yml` запускается при каждом изменении в `ios/` (или вручную: GitHub → Actions → iOS →
Run workflow). На облачном Mac он генерирует проект XcodeGen, собирает приложение, прогоняет тесты в симуляторе
iPhone и сохраняет журнал сборки, результаты тестов и скриншоты в артефакт **ios-results**.

```bash
gh run list --workflow ios.yml
gh run download <id> -n ios-results
```

Второе задание, **e2e**, — сквозной тест iPhone ↔ ядро SwitchFlow на том же облачном Mac:

1. `cargo build --example ios_e2e_host` — настоящее ядро SwitchFlow без интерфейса
   (`desktop/src-tauri/examples/ios_e2e_host.rs`): пишет код сопряжения и сам подтверждает подключение.
2. В «Фото» симулятора кладутся тестовые снимки (`ios/ci/make_fixtures.py`).
3. UI-тест `ios/SwitchFlowE2E` запускает Debug-сборку приложения с содержимым QR-кода
   (`-SFPairingURI`) и нажимает «Разрешить полный доступ» в системных запросах.
4. Ядро делает резервную копию iPhone → ПК, восстановление ПК → iPhone и повторное восстановление,
   проверяя SHA-256, число контактов и пропуск дубликатов. Итог — «E2E PASSED» и `summary.json`.

Скриншоты, журналы ядра, приложения и UI-теста — в артефакте **e2e-results**:

```bash
gh run download <id> -n e2e-results
```

Для установки на настоящий iPhone без Mac нужен аккаунт Apple Developer и загрузка сборки в TestFlight
(подпись в облаке через App Store Connect API key) — этот шаг ещё не настроен.

## Требования
- Mac с macOS 14+ и Xcode 15+ (Swift 5.9).
- Apple ID; для установки на чужие устройства и TestFlight — платная учётная запись Apple Developer Program.
- iPhone с iOS 16 или новее.
- [XcodeGen](https://github.com/yonaskolb/XcodeGen): `brew install xcodegen`.

## Создание проекта

```bash
cd ios
xcodegen            # создаст SwitchFlowCompanion.xcodeproj из project.yml
open SwitchFlowCompanion.xcodeproj
```

## Подпись
1. Xcode → проект **SwitchFlowCompanion** → вкладка **Signing & Capabilities**.
2. Включите **Automatically manage signing**, выберите вашу **Team**.
3. При необходимости смените **Bundle Identifier** (`app.switchflow.companion`) на свой уникальный.
   Либо задайте `DEVELOPMENT_TEAM` в `project.yml` и снова выполните `xcodegen`.

## Запуск на iPhone
1. Подключите iPhone кабелем, на iPhone: **Настройки → Конфиденциальность и безопасность → Режим разработчика** — включить.
2. В Xcode выберите устройство и нажмите **Run** (⌘R).
3. При первом запуске iOS спросит доступ к **локальной сети** — разрешите, иначе приложение не увидит ПК.

## Тесты

```bash
xcodebuild test -project SwitchFlowCompanion.xcodeproj -scheme SwitchFlowCompanion \
  -destination 'platform=iOS Simulator,name=iPhone 16'
```

## TestFlight и App Store
1. Product → **Archive**.
2. Organizer → **Distribute App** → App Store Connect → Upload.
3. В App Store Connect заполните раздел «Конфиденциальность приложения»: данные не собираются.
4. Иконка в `Resources/Assets.xcassets/AppIcon.appiconset` уже непрозрачная (требование App Store).

## Используемые разрешения (Info.plist)
`NSLocalNetworkUsageDescription`, `NSCameraUsageDescription`, `NSPhotoLibraryUsageDescription`,
`NSPhotoLibraryAddUsageDescription`, `NSContactsUsageDescription`,
`NSCalendarsFullAccessUsageDescription`, `NSCalendarsWriteOnlyAccessUsageDescription`.
`NSAllowsLocalNetworking` разрешает подключение к ПК по IP; подлинность ПК проверяется пиннингом сертификата.

## Особенности
- iOS не позволяет приложению работать в фоне долго: во время переноса держите SwitchFlow открытым.
  Приложение само отключает автоблокировку экрана на время переноса.
- Полученные документы и музыка доступны в приложении **Файлы → На iPhone → SwitchFlow**.
