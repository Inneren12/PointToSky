# Point-to-Sky

Стартовый скелет для набора приложений Point-to-Sky: Wear OS клиент и Android-приложение для телефона.

## Модули
- `wear` — приложение для Wear OS с Compose и навигацией.
- `wear/sensors` — библиотека с контрактами доступа к ориентационным сенсорам и заглушкой.
- `mobile` — Android-приложение с Compose Material 3.
- `core/common` — общий мультиплатформенный модуль (KMP) под доменную логику.

## Сенсорный слой
- `OrientationRepository` предоставляет поток `Flow<OrientationFrame>` с данными ориентации, управляется методами `start`, `stop`, `setZeroAzimuthOffset` и `setRemap` для адаптации азимута и ориентации экрана.
- `OrientationFrame` содержит азимут, тангаж, крен, нормированный вектор «вперёд», матрицу вращения и точность (`SensorAccuracy`).
- `SensorConfig` позволяет настраивать частоту выборки (`samplingPeriodUs`) и ограничение частоты кадров (`frameThrottleMs`).
- `ScreenRotation` описывает поворот экрана (0°, 90°, 180°, 270°).
- `FakeOrientationRepository` генерирует синусоидальные данные и подходит для предпросмотра и тестов без доступа к железу.

## Требования
- Java 17
- Android Studio Koala или новее

## Сборка
```bash
./gradlew :wear:assembleDebug
./gradlew :mobile:assembleDebug
```

## CI
При пуше или pull request запускается GitHub Actions workflow `android.yml`, который собирает оба приложения и кэширует Gradle.
