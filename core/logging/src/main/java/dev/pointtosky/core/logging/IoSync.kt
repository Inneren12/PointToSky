package dev.pointtosky.core.logging

import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.io.IOException
import java.nio.channels.FileChannel

/** flush() + fsync для OutputStream (если это FileOutputStream). */
fun OutputStream.flushAndSyncBlocking() {
    runCatching { this.flush() }
    if (this is FileOutputStream) {
        runCatching { this.fd.sync() }
    }
}

/** fsync для File через FileChannel.force(true). */
fun File.flushAndSyncBlocking() {
    runCatching {
        FileOutputStream(this, /*append=*/true).channel.use { ch ->
            ch.force(true)
        }
    }
}

/** fsync для FileChannel. */
fun FileChannel.flushAndSyncBlocking() {
    runCatching { this.force(true) }
}
