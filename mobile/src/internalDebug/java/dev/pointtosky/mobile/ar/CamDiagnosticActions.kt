package dev.pointtosky.mobile.ar

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent

/**
 * `internalDebug`-only. "Copy all": writes [text] to the real Android [ClipboardManager] as plain text
 * - never a Compose semantics-tree scrape, and never anything but the exact string
 * [dev.pointtosky.mobile.ar.camera.buildCamDiagnosticReportText] produced. [label] is the clip's own
 * description, shown by some OEM clipboard-history UIs; carries no sensitive data of its own.
 */
fun copyCamDiagnosticTextToClipboard(
    context: Context,
    label: String,
    text: String,
) {
    val clipboardManager = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboardManager.setPrimaryClip(ClipData.newPlainText(label, text))
}

/**
 * `internalDebug`-only. Pure `Intent` construction (architecture fix §5) - a plain `ACTION_SEND`,
 * `type = "text/plain"`, `EXTRA_SUBJECT = `[subject]`, `EXTRA_TEXT = `[text]. Never launches anything
 * itself, and never a chooser wrapper - callers that actually need to launch this ([shareCamDiagnosticText])
 * wrap it with [Intent.createChooser] separately. Tests can call this directly and assert its exact
 * `action`/`type`/extras without ever starting a real chooser Activity.
 */
fun buildCamDiagnosticShareIntent(
    subject: String,
    text: String,
): Intent =
    Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_SUBJECT, subject)
        putExtra(Intent.EXTRA_TEXT, text)
    }

/**
 * `internalDebug`-only. "Share log"/"Share JSON": wraps [buildCamDiagnosticShareIntent] in a chooser
 * and launches it - the one place this file actually starts an Activity. No file is ever written, so
 * this needs no external-storage permission. [context] not being an [Activity] (e.g. an application
 * context) requires `FLAG_ACTIVITY_NEW_TASK` on the launched chooser, or Android throws at
 * `startActivity` time - added defensively here regardless of the caller's actual context type.
 */
fun shareCamDiagnosticText(
    context: Context,
    subject: String,
    text: String,
) {
    val sendIntent = buildCamDiagnosticShareIntent(subject, text)
    val chooser =
        Intent.createChooser(sendIntent, subject).apply {
            if (context !is Activity) {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        }
    context.startActivity(chooser)
}
