package dev.pointtosky.mobile.ar

import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * `internalDebug`-only tests for [buildCamDiagnosticShareIntent] (architecture fix §5) - real
 * `android.content.Intent` field round-tripping requires a real Android runtime (the plain-JVM `test`
 * source set's `android.jar` stub returns default/`null` values for every method, per this project's
 * `unitTests.isReturnDefaultValues = true`, and this project has no Robolectric), so this lives in
 * `androidTest`. Never launches a real chooser - only constructs and inspects the `Intent` value.
 */
@RunWith(AndroidJUnit4::class)
class CamDiagnosticActionsTest {
    @Test
    fun sendIntentCarriesActionTypeSubjectAndExactText() {
        val subject = "PointToSky CAM diagnostics"
        val text = "POINTTOSKY CAM DIAGNOSTICS\nSESSION\nsession: 9\n"

        val intent = buildCamDiagnosticShareIntent(subject, text)

        assertEquals(Intent.ACTION_SEND, intent.action)
        assertEquals("text/plain", intent.type)
        assertEquals(subject, intent.getStringExtra(Intent.EXTRA_SUBJECT))
        assertEquals(text, intent.getStringExtra(Intent.EXTRA_TEXT))
    }

    @Test
    fun sendIntentCarriesTheExactJsonTextForShareJson() {
        val json = """{"schemaVersion":1,"sessionId":9}"""

        val intent = buildCamDiagnosticShareIntent("PointToSky CAM diagnostics (JSON)", json)

        assertEquals(Intent.ACTION_SEND, intent.action)
        assertEquals("text/plain", intent.type)
        assertEquals(json, intent.getStringExtra(Intent.EXTRA_TEXT))
    }
}
