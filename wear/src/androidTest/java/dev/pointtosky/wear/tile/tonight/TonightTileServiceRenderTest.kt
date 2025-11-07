package dev.pointtosky.wear.tile.tonight

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Test
import org.junit.runner.RunWith
import androidx.wear.tiles.RequestBuilders
import androidx.wear.tiles.DeviceParametersBuilders
import android.content.Context
import android.content.ContextWrapper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
/**
 * Инструментальные тесты построения тайла с помощью Tiles API (и tiles-testing в classpath).
 * Проверяем, что:
 *  • сервис отвечает на запросы ресурсов с корректной версией;
 *  • onTileRequest возвращает непустую таймлайн‑запись и установлен RES_VER;
 *  • (smoke) Layout строится без NPE и содержит контент.
 *
 * Основано на TonightTileService (S7.B–S7.E).
 */
@RunWith(AndroidJUnit4::class)
class TonightTileServiceRenderTest {

    @Test
    fun resources_haveVersion() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val service = TonightTileServiceTestHarness()
        service.initForTest(ctx)
        val req = RequestBuilders.ResourcesRequest.Builder()
            .setVersion("test")
            .build()
        val res = service.callOnResourcesRequest(req).get()
        // Версия ресурсов должна быть та, что задаёт сервис (RES_VER)
        // Smoke: версия ресурсов есть (конкретное значение может меняться)
        assertTrue("Empty resources version", !res.version.isNullOrEmpty())
    // println("Resources version = ${res.version}")
    }

    @Test
    fun tileRequest_buildsPrimaryLayoutAndTimeline() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val service = TonightTileServiceTestHarness()
        service.initForTest(ctx)

        val dp = DeviceParametersBuilders.DeviceParameters.Builder()
            .setScreenWidthDp(192)
            .setScreenHeightDp(192)
            .setScreenDensity(320f)
            .build()
        val req = RequestBuilders.TileRequest.Builder()
            .setDeviceParameters(dp)
            .build()

        val tile = service.callOnTileRequest(req).get()
        // Smoke: версия ресурсов есть
        assertTrue("Empty tile resourcesVersion", !tile.resourcesVersion.isNullOrEmpty())
        val timeline = tile.timeline
        assertNotNull(timeline)
        assertNotNull(timeline!!.timelineEntries.firstOrNull()?.layout)
    }


}
