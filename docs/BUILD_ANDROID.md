# Сборка Android-компаньона

## Требования
- JDK 17 (например, Eclipse Temurin 17).
- Android SDK: Platform 36, Build-Tools 35.0.0, Platform-Tools. Проще всего — Android Studio.
- Телефон с Android 9 или новее (проверено на Android 9, 11 и 16).

Укажите путь к SDK в `android/local.properties` (файл не коммитится):

```properties
sdk.dir=C:/Users/<вы>/AppData/Local/Android/Sdk
```

## Две версии из одного кода

| Версия | Пакет | Чем отличается | Сборка |
|---|---|---|---|
| `full` | `app.switchflow.companion` | Всё, включая перенос приложений. Идёт с установщиком для ПК и сайтом. | `./gradlew assembleFullRelease` |
| `play` | `app.switchflow.transfer` | Для Google Play: без переноса приложений (Play запрещает устанавливать приложения не из Play), бесплатный режим + покупка «SwitchFlow Pro» (`switchflow_pro`). | `./gradlew bundlePlayRelease` → `.aab` |

Обе нацелены на Android 16 (API 36) — Google Play требует этого с 31.08.2026. Нужна платформа
`android-36` в SDK. Выпуск в Google Play — [docs/store/PLAY_RELEASE.md](store/PLAY_RELEASE.md).

## Отладочная сборка

```bash
cd android
./gradlew assembleFullDebug      # Windows: gradlew.bat assembleFullDebug
```

APK: `android/app/build/outputs/apk/full/debug/app-full-debug.apk`.

Установка на телефон с включённой «Отладкой по USB»:

```bash
adb install -r app/build/outputs/apk/full/debug/app-full-debug.apk
```

## Тесты

```bash
./gradlew testDebugUnitTest
```

## Релизная сборка и подпись

1. Создайте ключ (один раз, храните в надёжном месте, **не в репозитории**):

   ```bash
   keytool -genkeypair -v -keystore switchflow-release.jks -keyalg RSA -keysize 4096 -validity 10000 -alias switchflow
   ```

2. Передайте параметры через переменные окружения:

   ```bash
   export SWITCHFLOW_KEYSTORE=/path/to/switchflow-release.jks
   export SWITCHFLOW_KEYSTORE_PASSWORD=...
   export SWITCHFLOW_KEY_ALIAS=switchflow
   export SWITCHFLOW_KEY_PASSWORD=...
   ./gradlew assembleRelease    # APK для прямой установки
   ./gradlew bundleRelease      # AAB для Google Play
   ```

Без переменных `assembleRelease` создаёт неподписанный `app-release-unsigned.apk`.

## Публикация в Google Play: на что обратить внимание
- Разрешения `READ_MEDIA_IMAGES/VIDEO/AUDIO` требуют декларации: перенос и резервное копирование
  файлов пользователя — допустимый сценарий, его нужно описать в Play Console.
- Foreground service типа `dataSync` нужно задекларировать с описанием (перенос данных на ПК).
- `READ_CONTACTS`/`WRITE_CONTACTS`, `READ_CALENDAR`/`WRITE_CALENDAR` — указать в форме безопасности данных:
  данные не передаются третьим лицам и не покидают устройство, кроме переноса на компьютер пользователя.
