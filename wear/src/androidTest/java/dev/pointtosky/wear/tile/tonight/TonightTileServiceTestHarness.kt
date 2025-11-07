package dev.pointtosky.wear.tile.tonight

import androidx.wear.tiles.RequestBuilders
import androidx.wear.tiles.ResourceBuilders
import androidx.wear.tiles.TileBuilders
import android.content.Context

/**
 * Подкласс для тестов: открываем доступ к protected методам TileService.
 */
class TonightTileServiceTestHarness : TonightTileService() {
    /** Инициализация сервиса в тесте (без ServiceScenario / рефлексии). */
    fun initForTest(context: Context) {
        // protected у TileService/ContextWrapper — вызываем из подкласса
        super.attachBaseContext(context)
    }
    fun callOnTileRequest(req: RequestBuilders.TileRequest)
        : com.google.common.util.concurrent.ListenableFuture<TileBuilders.Tile> =
        super.onTileRequest(req)

    fun callOnResourcesRequest(req: RequestBuilders.ResourcesRequest)
        : com.google.common.util.concurrent.ListenableFuture<ResourceBuilders.Resources> =
        super.onResourcesRequest(req)
}
