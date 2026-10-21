# Сборка и подпись iOS-компаньона

> Нативная сборка iOS возможна **только на macOS с Xcode**. На Windows её выполнить нельзя.
> Код в `ios/` пока **не компилировался** — при первой сборке в Xcode возможны ошибки, которые нужно исправить.

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
