# PointToSky — Store Listing Assets

Этот каталог содержит все артефакты для оформления Google Play (иконки, фича-графика, скриншоты, тексты).

## Что сюда входит
```
store/
  icon/
    layers/foreground.svg
    layers/background.svg
    icon-512.svg                # сводный SVG (быстрый экспорт в PNG 512)
    README.md                   # экспорт в PNG
  feature-graphic/
    feature-graphic-1024x500.svg
  screenshots/
    README.md                   # правила и чек-лист съёмки
    templates/
      phone-frame.svg
      wear-frame.svg
  play/
    en/short.txt
    en/full.txt
    en/whatsnew.txt
    ru/short.txt
    ru/full.txt
    ru/whatsnew.txt
  disclaimers/
    EN_NOT_FOR_NAVIGATION.txt
    RU_NOT_FOR_NAVIGATION.txt
  rating/
    iarc.md
     ```

## Требования Google Play (сжатое)
- Icon 512×512 PNG (32-bit, ≤1 MB). Не добавлять тени/скругления — Play сделает сам.
- Feature graphic 1024×500, JPG/PNG (без альфы).
- Screenshots:
  - Общие: 2–8 на тип устройства; min 320 px, max 3840 px; long ≤2× short edge.
  - Phones/Chromebook/Tablet: 16:9 или 9:16, желательно ≥1080 px по короткой стороне.
  - Wear OS: 1:1, ≥384×384, без девайс-рамок и внешних текстов.

Ссылки на официальные требования см. в комментариях коммита/PR.

## Экспорт графики
### Иконка → PNG 512×512
Вариант A (Inkscape GUI): открыть `store/icon/icon-512.svg` → Export PNG → 512×512 → `store/icon/icon-512.png`.
Вариант B (Inkscape CLI):
```
inkscape -o store/icon/icon-512.png -w 512 -h 512 store/icon/icon-512.svg
```

### Фича-графика → 1024×500
```
inkscape -o store/feature-graphic/feature-graphic-1024x500.png \
-w 1024 -h 500 store/feature-graphic/feature-graphic-1024x500.svg
```

## Съёмка скриншотов (кратко)
- **Phone**: реальный UI, 1080×1920 или 1920×1080, без уведомлений/шторок. Первые 3 — ключевые фичи.
- **Wear OS**: 1:1 (напр. 640×640), чистый UI без рамок и надписей вне приложения.
- Называние файлов: `phone_01_aim.png`, `phone_02_identify.png`, `wear_01_tile.png`, …
- Детали: см. `store/screenshots/README.md`.

## Тексты
Черновики лежат в `store/play/{en,ru}/`. Под корректировку перед загрузкой.

## Дисклеймеры
`store/disclaimers/*` — вставляются в конец полного описания и/или на сайт.

## Контент-рейтинг (IARC)
Заполнить анкету в Play Console по шаблону из `store/rating/iarc.md`.
