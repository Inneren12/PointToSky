package dev.pointtosky.core.time

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ZoneRepoTest {

    @Test
    fun `zone flow emits current and updated zone`() = runTest {
        val context = Application()
        var zone = ZoneId.of("UTC")
        val registrar = FakeRegistrar(context)
        val repo = ZoneRepo(
            context = context,
            zoneProvider = { zone },
            registerReceiver = registrar::register,
            unregisterReceiver = registrar::unregister,
        )

        val values = mutableListOf<ZoneId>()
        val job = launch { repo.zoneFlow.take(2).toList(values) }

        advanceUntilIdle()
        assertEquals(listOf(ZoneId.of("UTC")), values)

        zone = ZoneId.of("Europe/Berlin")
        registrar.send(Intent(Intent.ACTION_TIMEZONE_CHANGED))

        job.join()

        assertEquals(
            listOf(ZoneId.of("UTC"), ZoneId.of("Europe/Berlin")),
            values,
        )

        val filter = registrar.filter
        assertNotNull(filter)
        assertTrue(filter.hasAction(Intent.ACTION_TIMEZONE_CHANGED))
        assertTrue(filter.hasAction(Intent.ACTION_TIME_CHANGED))
        assertTrue(filter.hasAction(Intent.ACTION_LOCALE_CHANGED))
        assertTrue(registrar.unregistered)
    }

    private class FakeRegistrar(private val context: Context) {
        var receiver: BroadcastReceiver? = null
            private set
        var filter: IntentFilter? = null
            private set
        var unregistered: Boolean = false
            private set

        fun register(ctx: Context, receiver: BroadcastReceiver, filter: IntentFilter) {
            require(ctx === context)
            this.receiver = receiver
            this.filter = filter
        }

        fun unregister(ctx: Context, receiver: BroadcastReceiver) {
            require(ctx === context)
            require(this.receiver === receiver)
            unregistered = true
        }

        fun send(intent: Intent) {
            receiver?.onReceive(context, intent)
        }
    }
}
