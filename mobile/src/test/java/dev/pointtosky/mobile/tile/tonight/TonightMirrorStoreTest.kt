package dev.pointtosky.mobile.tile.tonight

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TonightMirrorStoreTest {
    @Test fun applyJson_parsesBareWatchFormat() {
        val json = """{"updatedAt":1719000000,"items":[{"id":"sirius","title":"Sirius","icon":"STAR"}]}"""
        TonightMirrorStore.applyJson(json, redacted = false)
        val m = TonightMirrorStore.model.value!!
        assertEquals(1719000000L, m.updatedAt)
        assertEquals(1, m.items.size)
        assertEquals("sirius", m.items[0].id)
    }

    @Test fun applyJson_redactedDropsItems() {
        TonightMirrorStore.applyJson("""{"updatedAt":1,"items":[{"id":"x","title":"X","icon":"STAR"}]}""", redacted = true)
        assertTrue(TonightMirrorStore.model.value!!.items.isEmpty())
    }
}
