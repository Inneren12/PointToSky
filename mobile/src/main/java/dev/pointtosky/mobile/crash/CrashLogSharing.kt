package dev.pointtosky.mobile.crash

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.core.content.FileProvider
import dev.pointtosky.mobile.BuildConfig
import dev.pointtosky.mobile.R
import java.io.File

object CrashLogSharing {
    private const val AUTHORITY_SUFFIX = ".logs"
    private const val MIME_TYPE_ZIP = "application/zip"

    fun shareZip(
        context: Context,
        file: File,
    ) {
        if (!file.exists()) {
            Toast.makeText(context, context.getString(R.string.crash_logs_share_missing), Toast.LENGTH_SHORT).show()
            return
        }
        val authority = BuildConfig.APPLICATION_ID + AUTHORITY_SUFFIX
        val uri = FileProvider.getUriForFile(context, authority, file)
        val shareIntent =
            Intent(Intent.ACTION_SEND).apply {
                type = MIME_TYPE_ZIP
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        val chooser =
            Intent.createChooser(
                shareIntent,
                context.getString(R.string.crash_logs_share_title),
            )
        chooser.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        try {
            context.startActivity(chooser)
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(context, context.getString(R.string.crash_logs_share_error), Toast.LENGTH_SHORT).show()
        }
    }
}
