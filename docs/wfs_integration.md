# Интеграция Watch Face Studio с PointToSky Aim

Этот документ описывает, как добавить на циферблат Watch Face Studio (WFS) область нажатия, которая открывает экран Aim в приложении PointToSky на часах.

## Параметры интента
- **Пакет:** `dev.pointtosky.wear`
- **Действие (Intent action):** `dev.pointtosky.action.OPEN_AIM`

Интент не требует дополнительных категорий или параметров. WFS автоматически создаст `Intent` с флагами `FLAG_ACTIVITY_NEW_TASK` и `FLAG_ACTIVITY_RESET_TASK_IF_NEEDED` — этого достаточно для запуска Aim.

## Настройка Tap target в Watch Face Studio
1. Откройте проект циферблата в Watch Face Studio.
2. В панели **Components** выберите **Tap action** → **Tap target** и разместите область на циферблате.
3. В свойствах компонента:
    - В блоке **Interaction** задайте **Type** = `App`.
    - В выпадающем списке **Action** выберите `Custom action`.
    - В поле **Package name** укажите `dev.pointtosky.wear`.
    - В поле **Action name** впишите `dev.pointtosky.action.OPEN_AIM`.
4. Сохраните проект и соберите пакет циферблата. При нажатии на указанную область часы запустят Aim в приложении PointToSky.

![Настройка Tap target в Watch Face Studio](images/wfs_tap_target.png)

## Иконки для кнопки «Открыть указку»
В директории [`docs/assets/wfs`](assets/wfs) находятся две SVG-иконки, подготовленные для кнопки запуска Aim:

| Файл | Описание |
| --- | --- |
| [`open_aim_light.svg`](assets/wfs/open_aim_light.svg) | Светлая версия иконки (под светлые циферблаты). |
| [`open_aim_dark.svg`](assets/wfs/open_aim_dark.svg) | Тёмная версия иконки (под тёмные циферблаты). |

Иконки экспортированы в размере 48×48 px, содержат прозрачный фон и масштабируются без потери качества. Их можно использовать в слоях **Button** или **Image** в WFS, либо экспортировать в PNG для размещения на циферблате.

## Проверка
1. Установите собранный циферблат на часах с установленным приложением PointToSky.
2. Нажмите на область Tap target — должен открыться экран Aim.
3. Если Aim не запускается, проверьте правильность пакета и action, а также убедитесь, что приложение обновлено до версии с поддержкой `ACTION_OPEN_AIM`.