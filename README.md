# Point-to-Sky

Стартовый скелет для набора приложений Point-to-Sky: Wear OS клиент и Android-приложение для телефона.

## Модули
- `wear` — приложение для Wear OS с Compose и навигацией.
- `wear/sensors` — библиотека для работы с ориентацией устройства на Wear OS.
- `mobile` — Android-приложение с Compose Material 3.
- `core/common` — общий мультиплатформенный модуль (KMP) под доменную логику.

## Требования
- Java 17
- Android Studio Koala или новее

## Сборка
```bash
./gradlew :wear:assembleDebug
./gradlew :mobile:assembleDebug
./gradlew :wear:sensors:assemble
```

## API сенсоров
- `OrientationRepository` — контракт на получение `Flow<OrientationFrame>`; позволяет стартовать/останавливать обновления, задавать калибровку по азимуту и ремап относительно поворота экрана.
- `OrientationFrame` — снимок ориентации (азимут, тангаж, крен, матрица вращения и вектор вперёд) с точностью `SensorAccuracy`.
- `SensorConfig` — настройки частоты опроса/троттлинга кадров.
- `ScreenRotation` — перечисление доступных ориентаций экрана.
- `FakeOrientationRepository` — простая синусоидальная реализация для превью и тестов.

## CI
При пуше или pull request запускается GitHub Actions workflow `android.yml`, который собирает оба приложения и кэширует Gradle.
