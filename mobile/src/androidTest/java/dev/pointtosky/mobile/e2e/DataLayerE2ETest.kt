package dev.pointtosky.mobile.e2e

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.pointtosky.core.datalayer.AimSetTargetMessage
import dev.pointtosky.core.datalayer.AimTargetEquatorialPayload
import dev.pointtosky.core.datalayer.AimTargetKind
import dev.pointtosky.core.datalayer.JsonCodec
import dev.pointtosky.core.datalayer.PATH_AIM_SET_TARGET
import dev.pointtosky.core.datalayer.PATH_CARD_OPEN
import dev.pointtosky.core.location.prefs.LocationPrefs
import dev.pointtosky.core.location.prefs.fromContext
import dev.pointtosky.mobile.card.CardDeepLinkLauncher
import dev.pointtosky.mobile.card.CardRepository
import dev.pointtosky.mobile.card.CardUiState
import dev.pointtosky.mobile.card.CardViewModel
import dev.pointtosky.mobile.card.CardViewModelFactory
import dev.pointtosky.mobile.catalog.CatalogRepositoryProvider
import dev.pointtosky.mobile.datalayer.MobileBridge
import dev.pointtosky.mobile.datalayer.v1.DlJson
import dev.pointtosky.mobile.datalayer.v1.DlMessageSender
import dev.pointtosky.mobile.datalayer.v1.DlReceiverService
import dev.pointtosky.mobile.search.SearchViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CopyOnWriteArrayList

@RunWith(AndroidJUnit4::class)
class DataLayerE2ETest {
    private lateinit var context: Context

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        CardRepository.resetForTests()
        CardDeepLinkLauncher.overrideForTests(null)
        MobileBridge.overrideTransportForTests(null)
        MobileBridge.resetForTests()
        DlMessageSender.overrideForTests(null)
    }

    @After
    fun tearDown() {
        CardRepository.resetForTests()
        CardDeepLinkLauncher.overrideForTests(null)
        MobileBridge.overrideTransportForTests(null)
        MobileBridge.resetForTests()
        DlMessageSender.overrideForTests(null)
    }

    @Test
    fun searchCardSetTargetSendsAimMessage() = runBlocking {
        val catalogRepository = CatalogRepositoryProvider.get(context)
        val searchViewModel = SearchViewModel(
            context = context,
            catalogRepository = catalogRepository,
            cardRepository = CardRepository,
        )

        searchViewModel.onQueryChange("Vega")

        val readyState = withContext(Dispatchers.Default) {
            searchViewModel.state.first { state ->
                state.results.any { it.title.contains("Vega", ignoreCase = true) }
            }
        }

        val vegaResult = readyState.results.first { it.title.contains("Vega", ignoreCase = true) }
        searchViewModel.onResultSelected(vegaResult)

        val cardId = CardRepository.latestCardIdFlow().first { it != null }!!

        val locationPrefs: LocationPrefs = LocationPrefs.fromContext(context)
        val cardViewModel = CardViewModelFactory(cardId, CardRepository, locationPrefs)
            .create(CardViewModel::class.java)

        val cardState = cardViewModel.state.first { it is CardUiState.Ready } as CardUiState.Ready
        val targetOption = requireNotNull(cardState.targetOption)

        val transport = RecordingTransport()
        MobileBridge.overrideTransportForTests(transport)
        MobileBridge.resetForTests()

        val ack = MobileBridge.get(context).send(PATH_AIM_SET_TARGET) { cid ->
            JsonCodec.encode(targetOption.buildMessage(cid))
        }

        assertNotNull(ack)

        val sent = transport.messages.single()
        assertEquals(PATH_AIM_SET_TARGET, sent.path)
        val message = JsonCodec.decode<AimSetTargetMessage>(sent.payload)
        assertTrue(message.cid.isNotBlank())
        assertEquals(AimTargetKind.EQUATORIAL, message.kind)
        val payload = JsonCodec.decodeFromElement<AimTargetEquatorialPayload>(message.payload)
        val expectedEq = requireNotNull(cardState.equatorial)
        assertEquals(expectedEq.raDeg, payload.raDeg, 1e-6)
        assertEquals(expectedEq.decDeg, payload.decDeg, 1e-6)
    }

    @Test
    fun identifyResultUpdatesCardRepositoryAndLaunchesCard() = runBlocking {
        val payload = JSONObject().apply {
            put("id", "identify_vega")
            put("type", "STAR")
            put("name", "Vega")
            put("constellation", "LYR")
            put(
                "eq",
                JSONObject().apply {
                    put("raDeg", 279.23473479)
                    put("decDeg", 38.78368896)
                },
            )
            put("mag", 0.03)
        }
        val message = JSONObject().apply {
            put("v", 1)
            put("cid", "identify-cid")
            put("object", payload)
        }
        val data = message.toString().encodeToByteArray()

        val launchedIds = CopyOnWriteArrayList<String>()
        CardDeepLinkLauncher.overrideForTests { _, id -> launchedIds += id }

        val fakeSender = object : DlMessageSender.Delegate {
            val sent = CopyOnWriteArrayList<Triple<String, String, ByteArray>>()
            override fun sendMessage(
                context: Context,
                nodeId: String,
                path: String,
                payload: ByteArray,
                onFailure: DlMessageSender.FailureListener,
            ) {
                sent += Triple(nodeId, path, payload)
            }
        }
        DlMessageSender.overrideForTests(fakeSender)

        val service = DlReceiverService()
        service.onMessageReceived(
            TestMessageEvent(
                path = PATH_CARD_OPEN,
                data = data,
                sourceNodeId = "wear-node",
            ),
        )

        val latestId = CardRepository.latestCardIdFlow().first { it == "identify_vega" }
        assertEquals("identify_vega", latestId)
        assertTrue(launchedIds.contains("identify_vega"))

        val ackPayload = fakeSender.sent.single().third
        val (refCid, ok) = DlJson.parseAck(ackPayload)
        assertEquals("identify-cid", refCid)
        assertTrue(ok == true)
    }

    private class RecordingTransport : MobileBridge.Sender.Transport {
        data class Message(val nodeId: String, val path: String, val payload: ByteArray)
        val messages = CopyOnWriteArrayList<Message>()

        override fun connectedNodes(): List<MobileBridge.Sender.Transport.Node> {
            return listOf(MobileBridge.Sender.Transport.Node("wear"))
        }

        override fun sendMessage(nodeId: String, path: String, payload: ByteArray) {
            messages += Message(nodeId, path, payload)
        }
    }

    private class TestMessageEvent(
        private val path: String,
        private val data: ByteArray,
        private val sourceNodeId: String,
    ) : com.google.android.gms.wearable.MessageEvent {
        override fun getData(): ByteArray = data
        override fun getPath(): String = path
        override fun getRequestId(): Int = 0
        override fun getSourceNodeId(): String = sourceNodeId
    }
}
