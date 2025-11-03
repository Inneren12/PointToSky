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

## S4.D tests
- Голден-эпимериды для `SimpleEphemerisComputer` хранятся в `core/astro/src/test/resources/ephem_golden_v1.json`.
- Проверка против текущих значений: `./gradlew :core:astro:test`.
- Обновление голденов: `./gradlew :core:astro:test -PupdateGolden=true`.
  - Перезапись выполняется только локально (когда переменная окружения `CI` отсутствует).
  - После успешной генерации тесты помечаются как пропущенные — запустите сборку ещё раз без флага для валидации.
