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
