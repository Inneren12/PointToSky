package dev.pointtosky.mobile.ar

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent

/**
 * "Copy all" (diagnostic export/freeze fix §3): writes [text] to the real Android
 * [ClipboardManager] as plain text - never a Compose semantics-tree scrape, and never anything but
 * the exact string [dev.pointtosky.mobile.ar.camera.buildCamDiagnosticReportText] produced. [label]
 * is the clip's own description, shown by some OEM clipboard-history UIs; carries no sensitive data
 * of its own.
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
 * "Share log"/"Share JSON" (diagnostic export/freeze fix §4/§5): a plain `ACTION_SEND`,
 * `type = "text/plain"`, `EXTRA_TEXT = `[text] - the exact same deterministic string "Copy all" would
 * have copied, whether that is the full report or the JSON export. No file is ever written, so this
 * needs no external-storage permission. [context] not being an [Activity] (e.g. an application
 * context) requires `FLAG_ACTIVITY_NEW_TASK` on the launched chooser, or Android throws at
 * `startActivity` time - added defensively here regardless of the caller's actual context type.
 */
fun shareCamDiagnosticText(
    context: Context,
    subject: String,
    text: String,
) {
    val sendIntent =
        Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, subject)
            putExtra(Intent.EXTRA_TEXT, text)
        }
    val chooser =
        Intent.createChooser(sendIntent, subject).apply {
            if (context !is Activity) {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        }
    context.startActivity(chooser)
}
