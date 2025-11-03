package dev.pointtosky.mobile.logs

import android.content.Context
import android.net.Uri
import com.google.android.gms.wearable.MessageClient
import com.google.android.gms.wearable.NodeClient
import com.google.android.gms.wearable.Wearable
import dev.pointtosky.core.common.logs.LOG_MESSAGE_QUERY_FILE
import dev.pointtosky.core.common.logs.LOG_MESSAGE_REQUEST_PATH
import dev.pointtosky.core.common.logs.LOG_TRANSFER_SIZE_LIMIT_BYTES
import dev.pointtosky.mobile.R
import kotlinx.coroutines.tasks.await

class WatchLogsInteractor(private val context: Context) {
    private val messageClient: MessageClient by lazy { Wearable.getMessageClient(context) }
    private val nodeClient: NodeClient by lazy { Wearable.getNodeClient(context) }

    suspend fun requestFile(fileName: String, sizeBytes: Long): Result<Unit> {
        if (sizeBytes > LOG_TRANSFER_SIZE_LIMIT_BYTES) {
            return Result.failure(IllegalArgumentException(context.getString(R.string.logs_error_too_large)))
        }
        return runCatching {
            val nodes = nodeClient.connectedNodes.await()
            val node = nodes.firstOrNull()
                ?: throw IllegalStateException(context.getString(R.string.logs_error_no_nodes))
            val uri = Uri.parse(LOG_MESSAGE_REQUEST_PATH).buildUpon()
                .appendQueryParameter(LOG_MESSAGE_QUERY_FILE, fileName)
                .build()
            messageClient.sendMessage(node.id, uri.toString(), ByteArray(0)).await()
        }
    }
}
