# Point-to-Sky

Стартовый скелет для набора приложений Point-to-Sky: Wear OS клиент и Android-приложение для телефона.

## Модули
- `wear` — приложение для Wear OS с Compose и навигацией.
- `mobile` — Android-приложение с Compose Material 3.
- `core/common` — общий мультиплатформенный модуль (KMP) под доменную логику.

## Требования
- Java 17
- Android Studio Koala или новее

## Сборка
```bash
./gradlew :wear:assembleDebug
./gradlew :mobile:assembleDebug
```

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
