# PointToSky — Tonight Tile (tests)

## Как добавить тайл на часы

Для эмулятора/устройства с Wear OS:

```bash
adb shell cmd tile add dev.pointtosky.wear/dev.pointtosky.wear.tile.tonight.TonightTileService
```

> Сервис тайла объявлен в `AndroidManifest.xml` и экспортирован с label из строк ресурса `tile_tonight_label`.

## Запуск тестов

Юнит‑тесты провайдера (JVM, Robolectric):
```bash
./gradlew :wear:test
```

Инструментальные тесты рендеринга тайла (подключён девайс/эмулятор):
```bash
./gradlew :wear:connectedAndroidTest
```

## Как гонять H9

```bash
./gradlew :mobile:testDebugUnitTest
./gradlew :wear:testInternalDebugUnitTest :wear:testPublicDebugUnitTest
```

## Как гонять e2e на AVD‑паре

1. Создайте пару «телефон + часы» в Android Studio Device Manager (меню **Wear OS > Pair new device**). Минимально подходящие конфигурации: Pixel 7 (API 34+) и Wear OS Round (API 34+).
2. Запустите оба эмулятора и убедитесь, что они отображаются в `adb devices` как, например, `emulator-5554` (телефон) и `emulator-5556` (часы). Если пара ещё не связана, откройте **Wear OS Pairing Assistant** и привяжите выбранные устройства.
3. Убедитесь, что эмуляторы разблокированы и прошли первичную настройку (Google Play Services обновлены, часы синхронизированы с телефоном).
4. Запустите E2E-сценарии командой:
   ```bash
   ./gradlew :mobile:connectedAndroidTest :wear:connectedAndroidTest
   ```
   Gradle автоматически выполнит mobile UI и data-layer проверки на телефоне, а также confirm-сценарии на часах.
5. После завершения тестов можно выгрузить логи `adb logcat` для диагностики, если какая-либо проверка упала.

## Что проверяют тесты

- **Unit (RealTonightProvider):**
  - фолбэк без локации → `Moon + Vega`,
  - 2–3 элемента для реальной локации/даты,
  - при наличии планеты — она имеет приоритет над звёздами (в текущей реализации приоритет зашит). :contentReference[oaicite:8]{index=8}
- **Instrumented (TonightTileService):**
  - выдаёт корректную версию ресурсов и маппинги иконок,
  - возвращает непустую `Timeline` с валидным `Layout` (PrimaryLayout в корне). :contentReference[oaicite:9]{index=9}

## Security checks

Чтобы убедиться, что чувствительные компоненты не «торчат наружу» и все `PendingIntent`/`FileProvider` настроены корректно, запустите короткий смоук:

```bash
bash tools/security_smoke.sh
```

Скрипт проверяет экспортируемость сервисов/провайдеров и выводит сведения о `FileProvider` из дебажного APK. Если требуется выполнить команды вручную (например, в Windows PowerShell — замените `grep` на `findstr`), используйте следующую последовательность:

```powershell
adb shell dumpsys package dev.pointtosky.wear   | findstr exported
adb shell dumpsys package dev.pointtosky.mobile | findstr exported
aapt dump badging mobile-debug.apk              | findstr provider
```

> `mobile-debug.apk` лежит в `mobile/build/outputs/apk/debug/` после выполнения `./gradlew :mobile:assembleDebug`.

### FileProvider и временные grant'ы

- Провайдер объявлен с `android:authorities="${applicationId}.logs"`, поэтому ожидаемый URI выглядит так:
  `content://dev.pointtosky.mobile.logs/crash_logs/<имя_файла>.zip` (см. `mobile/src/main/res/xml/filepaths_logs.xml`).
- Попытка читать URI из shell без grant'а должна заканчиваться `Permission Denial`:
  ```bash
  adb shell content read --uri "content://dev.pointtosky.mobile.logs/crash_logs/example.zip"
  ```
- После того как пользователь поделился ZIP (через `CrashLogSharing.shareZip`), проверьте, что доступ выдан ровно целевому приложению:
  ```powershell
  adb shell dumpsys package com.google.android.gm | findstr dev.pointtosky.mobile.logs
  ```
  Здесь должен быть единственный `grantedUriPermissions` с нашим `content://`-URI. После закрытия экрана шаринга запись исчезает, что подтверждает отсутствие висячих grant'ов.

