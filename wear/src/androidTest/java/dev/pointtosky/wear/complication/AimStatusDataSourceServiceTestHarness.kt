package dev.pointtosky.wear.complication

import android.content.Context

/**
 * Подкласс для тестов: открываем доступ к protected attachBaseContext у
 * ContextWrapper/Service (без ServiceScenario / рефлексии) — тот же приём,
 * что и TonightTileServiceTestHarness для TileService.
 */
class AimStatusDataSourceServiceTestHarness : AimStatusDataSourceService() {
    fun initForTest(context: Context) {
        super.attachBaseContext(context)
    }
}
