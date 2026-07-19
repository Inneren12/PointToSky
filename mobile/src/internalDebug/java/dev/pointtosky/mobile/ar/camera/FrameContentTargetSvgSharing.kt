package dev.pointtosky.mobile.ar.camera

import android.app.Activity
import android.content.ClipData
import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import dev.pointtosky.mobile.BuildConfig
import java.io.File

/**
 * CAM-2c frame-content correspondence experiment (`internalDebug`-only). Real `image/svg+xml` *file*
 * sharing for [buildFrameContentTargetSvg].
 *
 * ## The bug this fixes
 * The prior "Share target SVG" button routed the generated SVG text through
 * [dev.pointtosky.mobile.ar.shareCamDiagnosticText] — a plain `ACTION_SEND` with `type = "text/plain"`
 * and `EXTRA_TEXT`. That never created or attached any file: a receiving app was offered a text blob it
 * could paste, never something it could open as an SVG, preview as an image, or print at true millimetre
 * scale — despite the UI calling it "Share target SVG" and [buildFrameContentTargetSvg]'s own KDoc
 * calling it "printing this exact file".
 *
 * ## The fix
 * [writeFrameContentTargetSvgToCache] writes the *exact* UTF-8 bytes [buildFrameContentTargetSvg]
 * produces to a stable, app-owned cache file ([FRAME_CONTENT_TARGET_SVG_FILE_NAME]). That file is
 * exposed through [FileProvider] — never a bare `file://` `Uri`, which throws `FileUriExposedException`
 * on API 24+ and which no receiving app outside this process could read from anyway — via a dedicated
 * `internalDebug`-only provider (`mobile/src/internalDebug/AndroidManifest.xml`,
 * `mobile/src/internalDebug/res/xml/filepaths_cam2c_target.xml`) scoped to only this cache subdirectory,
 * `exported=false`, `grantUriPermissions=true`. [buildFrameContentTargetSvgShareIntent] builds a real
 * `ACTION_SEND` with `type = "image/svg+xml"`, `EXTRA_STREAM` set to that content `Uri`,
 * `FLAG_GRANT_READ_URI_PERMISSION`, and a [ClipData] wrapping the same `Uri` (some receiving apps only
 * honour the temporary grant via `ClipData`, not `EXTRA_STREAM` alone). No external-storage permission is
 * needed or requested — the file lives entirely inside this app's own cache directory.
 */
internal const val FRAME_CONTENT_TARGET_SVG_FILE_NAME: String = "cam_2c_frame_content_target.svg"
internal const val FRAME_CONTENT_TARGET_SVG_MIME_TYPE: String = "image/svg+xml"
private const val FRAME_CONTENT_TARGET_SVG_CACHE_SUBDIR = "cam2c_target"
internal const val FRAME_CONTENT_TARGET_SVG_PROVIDER_AUTHORITY_SUFFIX: String = ".cam2c.target"

/** Writes [buildFrameContentTargetSvg]'s exact UTF-8 bytes for [spec] to a stable, app-owned cache file
 * — [FRAME_CONTENT_TARGET_SVG_FILE_NAME], inside a dedicated cache subdirectory that
 * `filepaths_cam2c_target.xml`'s `cache-path` is scoped to (never the whole cache directory). Every call
 * overwrites the same file with the same deterministic bytes for the same [spec] — never a randomized or
 * timestamped filename, so a repeat share always reflects the current [spec] exactly, and the receiving
 * app always sees the same descriptive filename. */
internal fun writeFrameContentTargetSvgToCache(
    context: Context,
    spec: FrameContentTargetSpec = DEFAULT_FRAME_CONTENT_TARGET_SPEC,
): File {
    val dir = File(context.cacheDir, FRAME_CONTENT_TARGET_SVG_CACHE_SUBDIR)
    dir.mkdirs()
    val file = File(dir, FRAME_CONTENT_TARGET_SVG_FILE_NAME)
    file.writeBytes(buildFrameContentTargetSvg(spec).toByteArray(Charsets.UTF_8))
    return file
}

/**
 * Pure `Intent` construction (mirrors [dev.pointtosky.mobile.ar.buildCamDiagnosticShareIntent]'s own
 * testability rationale) — writing the cache file is unavoidable here (there is no way to obtain a real
 * `Uri` for `EXTRA_STREAM` without the underlying file existing), but this function never launches
 * anything itself and never wraps its result in a chooser; [shareFrameContentTargetSvg] does that
 * separately. A test can therefore call this directly and assert its exact `action`/`type`/extras/flags
 * without ever starting a real chooser `Activity`.
 */
internal fun buildFrameContentTargetSvgShareIntent(
    context: Context,
    spec: FrameContentTargetSpec = DEFAULT_FRAME_CONTENT_TARGET_SPEC,
): Intent {
    val file = writeFrameContentTargetSvgToCache(context, spec)
    val authority = BuildConfig.APPLICATION_ID + FRAME_CONTENT_TARGET_SVG_PROVIDER_AUTHORITY_SUFFIX
    val uri = FileProvider.getUriForFile(context, authority, file)
    return Intent(Intent.ACTION_SEND).apply {
        type = FRAME_CONTENT_TARGET_SVG_MIME_TYPE
        putExtra(Intent.EXTRA_STREAM, uri)
        clipData = ClipData.newUri(context.contentResolver, FRAME_CONTENT_TARGET_SVG_FILE_NAME, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
}

/** "Share target SVG": wraps [buildFrameContentTargetSvgShareIntent] in a chooser and launches it - the
 * one place this file actually starts an Activity. [context] not being an [Activity] (e.g. an
 * application context) requires `FLAG_ACTIVITY_NEW_TASK` on the launched chooser, or Android throws at
 * `startActivity` time - added defensively here regardless of the caller's actual context type, matching
 * [dev.pointtosky.mobile.ar.shareCamDiagnosticText]'s own convention. */
internal fun shareFrameContentTargetSvg(
    context: Context,
    spec: FrameContentTargetSpec = DEFAULT_FRAME_CONTENT_TARGET_SPEC,
) {
    val sendIntent = buildFrameContentTargetSvgShareIntent(context, spec)
    val chooser =
        Intent.createChooser(sendIntent, "CAM-2c frame-content target SVG").apply {
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            if (context !is Activity) {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        }
    context.startActivity(chooser)
}
