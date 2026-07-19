package dev.pointtosky.mobile.ar.camera

import android.Manifest
import android.content.ClipData
import android.content.Intent
import android.content.pm.PackageManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * CAM-2c frame-content correspondence experiment (`internalDebug`-only). Real `image/svg+xml` *file*
 * sharing for [buildFrameContentTargetSvg] — see `FrameContentTargetSvgSharing.kt`'s own KDoc for the bug
 * this fixes ("Share target SVG" previously only ever sent `EXTRA_TEXT`, never a real file). File I/O,
 * [androidx.core.content.FileProvider] `Uri` resolution, and `PackageManager` provider lookups all
 * require a real Android runtime (this project has no Robolectric — see [CamDiagnosticActionsTest]'s own
 * KDoc), so this lives in `androidTest`, not the plain-JVM `test` source set.
 */
@RunWith(AndroidJUnit4::class)
class FrameContentTargetSvgSharingTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val spec = DEFAULT_FRAME_CONTENT_TARGET_SPEC

    @Test
    fun theGeneratedFileExistsInCacheAfterBuildingTheShareIntent() {
        val intent = buildFrameContentTargetSvgShareIntent(context, spec)
        val file = writeFrameContentTargetSvgToCache(context, spec)

        assertTrue("expected the SVG cache file to exist after buildFrameContentTargetSvgShareIntent", file.exists())
        assertTrue(file.path.startsWith(context.cacheDir.path))
        assertNotNull(intent.getParcelableExtra<android.net.Uri>(Intent.EXTRA_STREAM))
    }

    @Test
    fun theGeneratedFileNameEndsInSvg() {
        assertTrue(FRAME_CONTENT_TARGET_SVG_FILE_NAME.endsWith(".svg"))
        val file = writeFrameContentTargetSvgToCache(context, spec)
        assertTrue(file.name.endsWith(".svg"))
        assertEquals(FRAME_CONTENT_TARGET_SVG_FILE_NAME, file.name)
    }

    @Test
    fun theWrittenBytesExactlyEqualTheDeterministicSvgTextEncodedAsUtf8() {
        val file = writeFrameContentTargetSvgToCache(context, spec)
        val expectedBytes = buildFrameContentTargetSvg(spec).toByteArray(Charsets.UTF_8)

        assertTrue(expectedBytes.contentEquals(file.readBytes()))
    }

    @Test
    fun theIntentActionIsActionSend() {
        val intent = buildFrameContentTargetSvgShareIntent(context, spec)
        assertEquals(Intent.ACTION_SEND, intent.action)
    }

    @Test
    fun theMimeTypeIsImageSvgXml() {
        val intent = buildFrameContentTargetSvgShareIntent(context, spec)
        assertEquals("image/svg+xml", intent.type)
    }

    @Test
    fun extraStreamIsTheExpectedContentUri() {
        val intent = buildFrameContentTargetSvgShareIntent(context, spec)
        val uri = intent.getParcelableExtra<android.net.Uri>(Intent.EXTRA_STREAM)

        assertNotNull(uri)
        assertEquals("content", uri!!.scheme)
        assertEquals(
            context.packageName + FRAME_CONTENT_TARGET_SVG_PROVIDER_AUTHORITY_SUFFIX,
            uri.authority,
        )
    }

    @Test
    fun flagGrantReadUriPermissionIsSet() {
        val intent = buildFrameContentTargetSvgShareIntent(context, spec)
        assertTrue((intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION) != 0)
    }

    @Test
    fun clipDataCarriesTheSameUriAsExtraStream() {
        val intent = buildFrameContentTargetSvgShareIntent(context, spec)
        val extraStreamUri = intent.getParcelableExtra<android.net.Uri>(Intent.EXTRA_STREAM)
        val clipData: ClipData? = intent.clipData

        assertNotNull("EXTRA_STREAM must carry a real Uri", extraStreamUri)
        assertNotNull("a ClipData must be attached so the read grant propagates to every receiving component", clipData)
        assertEquals(1, clipData!!.itemCount)
        assertEquals(extraStreamUri, clipData.getItemAt(0).uri)
    }

    @Suppress("DEPRECATION")
    @Test
    fun packageManagerCanResolveAndReadTheProviderUriInThisInternalDebugBuild() {
        val intent = buildFrameContentTargetSvgShareIntent(context, spec)
        val uri = intent.getParcelableExtra<android.net.Uri>(Intent.EXTRA_STREAM)!!

        val providerInfo = context.packageManager.resolveContentProvider(uri.authority!!, 0)
        assertNotNull("PackageManager must resolve the FileProvider authority this Uri targets", providerInfo)

        val bytes = context.contentResolver.openInputStream(uri)!!.use { it.readBytes() }
        val expectedBytes = buildFrameContentTargetSvg(spec).toByteArray(Charsets.UTF_8)
        assertTrue("the resolved content Uri must be readable and return the exact deterministic SVG bytes", expectedBytes.contentEquals(bytes))
    }

    @Suppress("DEPRECATION")
    @Test
    fun theProviderIsNonExportedAndGrantsTemporaryReadAccess() {
        val intent = buildFrameContentTargetSvgShareIntent(context, spec)
        val uri = intent.getParcelableExtra<android.net.Uri>(Intent.EXTRA_STREAM)!!

        val providerInfo = context.packageManager.resolveContentProvider(uri.authority!!, 0)
        assertNotNull(providerInfo)
        assertFalse(
            "the cam2c.target FileProvider must stay exported=false - only this app's own share flow may reach it",
            providerInfo!!.exported,
        )
        assertTrue(
            "the cam2c.target FileProvider must set grantUriPermissions=true so a temporary read grant " +
                "can reach whichever app the chooser launches",
            providerInfo.grantUriPermissions,
        )
    }

    @Test
    fun noExternalStoragePermissionIsIntroduced() {
        val packageInfo =
            context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS)
        val requestedPermissions = packageInfo.requestedPermissions?.toList().orEmpty()

        assertFalse(requestedPermissions.contains(Manifest.permission.WRITE_EXTERNAL_STORAGE))
        assertFalse(requestedPermissions.contains(Manifest.permission.READ_EXTERNAL_STORAGE))
    }
}
