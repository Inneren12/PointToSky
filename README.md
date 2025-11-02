# Point-to-Sky

Стартовый скелет для набора приложений Point-to-Sky: Wear OS клиент и Android-приложение для телефона.

## Модули
- `wear` — приложение для Wear OS с Compose и навигацией.
- `wear/sensors` — библиотека с контрактами и моделями сенсоров ориентации (OrientationRepository, OrientationFrame и др.).
- `mobile` — Android-приложение с Compose Material 3.
- `core/common` — общий мультиплатформенный модуль (KMP) под доменную логику.

## Сенсорный слой
Модуль `:wear:sensors` предоставляет контракты для работы с ориентацией устройства:

- `OrientationRepository` — источник `Flow<OrientationFrame>` с методами запуска, остановки и калибровки.
- `OrientationFrame` — снимок ориентации (азимут, тангаж, крен, нормированный forward-вектор, матрица вращения, точность `SensorAccuracy`).
- `SensorConfig` — параметры частоты опроса и троттлинга кадров.
- `ScreenRotation` — ориентация экрана (0/90/180/270).
- `FakeOrientationRepository` — синусоидальный фейк для превью и тестов.

## Требования
- Java 17
- Android Studio Koala или новее

## Сборка
```bash
./gradlew :wear:sensors:assemble
./gradlew :wear:assembleDebug
./gradlew :mobile:assembleDebug
```

## CI
При пуше или pull request запускается GitHub Actions workflow `android.yml`, который собирает оба приложения и кэширует Gradle.
