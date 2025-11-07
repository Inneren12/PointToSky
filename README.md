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

## Что проверяют тесты

- **Unit (RealTonightProvider):**
  - фолбэк без локации → `Moon + Vega`,
  - 2–3 элемента для реальной локации/даты,
  - при наличии планеты — она имеет приоритет над звёздами (в текущей реализации приоритет зашит). :contentReference[oaicite:8]{index=8}
- **Instrumented (TonightTileService):**
  - выдаёт корректную версию ресурсов и маппинги иконок,
  - возвращает непустую `Timeline` с валидным `Layout` (PrimaryLayout в корне). :contentReference[oaicite:9]{index=9}
