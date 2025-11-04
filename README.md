# Point-to-Sky

Стартовый скелет для набора приложений Point-to-Sky: Wear OS клиент и Android-приложение для телефона.

## Модули
- `wear` — приложение для Wear OS с Compose и навигацией.
- `mobile` — Android-приложение с Compose Material 3.
- `core/common` — общий мультиплатформенный модуль (KMP) под доменную логику.
- `core/location` — контракты источников локации, модели и форматирование DMS. Предоставляет заглушки
  `FakeLocationRepository` и `StubLocationOrchestrator` для превью и тестов. Подключение:
  `implementation(project(":core:location"))`.
- `core/time` — источник времени и репозиторий часовых поясов (`ZoneRepo`), общий для wear/mobile.
  Подключение: `implementation(project(":core:time"))`.

## Требования
- Java 17
- Android Studio Koala или новее

## Сборка
```bash
./gradlew :wear:assembleDebug
./gradlew :mobile:assembleDebug
```

## CLI для эпемерид
Для ручной проверки расчётов без Android Studio можно собрать утилиту `:tools:ephem-cli`:

```bash
./gradlew :tools:ephem-cli:installDist
```

Запуск примерного сценария (первые 24 часа 2025-01-01 с шагом 1 час):

```bash
build/install/ephem-cli/bin/ephem-cli \
  --instant=2025-01-01T00:00:00Z \
  --body=JUPITER \
  --stepHours=1 \
  --count=24
```

Программа печатает CSV с колонками `instant, body, raDeg, decDeg, distanceAu, phase`. Значения можно
сверять с внешними источниками (например, [JPL Horizons](https://ssd.jpl.nasa.gov/horizons/app.html)
или [In-The-Sky.org](https://in-the-sky.org/ephemeris.php)): достаточно указать тот же момент времени и
координаты тела, после чего сравнить прав ascension/declination, расстояние и фазу.

## CLI для каталога звёзд
Модуль `:tools:catalog-packer` превращает CSV (Bright Star Catalog v5 или HYG Database) в компактный
бинарь `stars_v1.bin` для рантайма. Сборка и запуск:

```bash
./gradlew :tools:catalog-packer:installDist

build/install/catalog-packer/bin/catalog-packer \
  --source=hyg \
  --input=~/Downloads/hyg_v35.csv \
  --out=build/catalog/stars_v1.bin \
  --mag-limit=6.5 \
  --with-con-codes
```

Результат — файл `stars_v1.bin` (~0.6–1.2 МБ для предела видимой величины 6.5m) и метаданные
`stars_v1.meta.json` с агрегированными статистиками.

### Формат `stars_v1.bin`

* Заголовок (32 байта, Little Endian):
  * `magic[8]` — ASCII `PTSKSTAR`.
  * `version u16` — 1.
  * `reserved u16` — 0.
  * `countStars u32` — число записей.
  * `stringPoolSize u32` — размер пула строк.
  * `indexOffset u32` — смещение блока индекса от начала data-секции (после заголовка).
  * `indexSize u32` — размер блока индекса.
  * `crc32 u32` — контрольная сумма CRC32 от всей data-секции.
* Пул строк — UTF-8 строки, разделённые нуль-терминатором. Смещения используются в записях.
* Записи звёзд (`countStars` × 32 байта):
  * `ra f32`, `dec f32`, `mag f32`, `bv f32` (пока 0), `hip i32`.
  * `nameOffset u32`, `designationOffset u32` (Bayer/Flamsteed+созвездие).
  * `flags u16` (наличие HIP/имени/обозначения и источник), `conCode u16` (код созвездия или 0xFFFF).
* Блок индекса:
  * Таблица из 180 деклинационных поясов (−90…+89): `bandId i16`, `start u32`, `count u32` — диапазон
    звёзд в массивах ниже.
  * `starIdsByBand[]` — конкатенированные ID звёзд (u32) по поясам, внутри каждого отсортированы по RA.
  * `raByBand[]` — значения RA (f32) в том же порядке для быстрого двоичного поиска.
  * Сводка (8 × f32): число поясов, число записей в индексе, min/max mag, min/max RA, min/max Dec.

## Структурированное логирование
Модуль `:core:logging` предоставляет фасад `LogBus` для структурированного логирования в формате JSONL.

- Инициализация выполняется через `LoggerInitializer.init(context, isDebug, DeviceInfo)`. По умолчанию
  кольцевой буфер на 10 000 записей активен всегда, а запись в файл `files/logs/pointtosky-*.jsonl`
  включается для debug-сборок или при явном флаге `diagnosticsEnabled` в `DeviceInfo`.
- Файлы логов ротируются по размеру (5 MiB) и смене суток. Закрытые файлы автоматически архивируются в
  `*.jsonl.gz`, хранится до 7 файлов.
- В JSONL каждая строка содержит поля `timestamp`, `level`, `tag`, `message`, `event`, `payload`, `thread`,
  `process`, `device`, а также информацию об ошибке при наличии.

Пример записи:

```kotlin
LogBus.e(
    tag = "Diagnostics",
    msg = "Background sync failed",
    err = RuntimeException("network timeout"),
    payload = mapOf("task" to "sync", "attempt" to 2)
)
```

После вызова запись появляется как в кольцевом буфере `LogBus.snapshot()`, так и в активном JSONL-файле.

## CI
При пуше или pull request запускается GitHub Actions workflow `android.yml`, который собирает оба приложения и кэширует Gradle.

## Запуск тестов S4.D
- Голден-эпимериды для `SimpleEphemerisComputer` хранятся в `core/astro/src/test/resources/ephem_golden_v1.json`.
- Локально (Gradle): `./gradlew astroTest`.
- Локально (обновить голдены): `./gradlew :core:astro:test -PupdateGolden=true`.
  - Перезапись выполняется только локально (когда переменная окружения `CI` отсутствует).
  - После успешной генерации тесты помечаются как пропущенные — запустите сборку ещё раз без флага для валидации.
- Android Studio: откройте модуль `:core:astro`, выберите папку `test`, кликните правой кнопкой и запустите `Run Tests`.
- CLI: сборка и запуск `ephem-cli` через `./gradlew ephemCli`, затем `build/install/ephem-cli/bin/ephem-cli ...`.
