package dev.pointtosky.mobile.datalayer.v1

import android.content.Context
import androidx.annotation.VisibleForTesting
import com.google.android.gms.wearable.Wearable

internal object DlMessageSender {
    fun interface FailureListener {
        fun onFailure(error: Exception)
    }

    interface Delegate {
        fun sendMessage(context: Context, nodeId: String, path: String, payload: ByteArray, onFailure: FailureListener)
    }

    @Volatile
    private var overrideDelegate: Delegate? = null

    fun sendMessage(
        context: Context,
        nodeId: String,
        path: String,
        payload: ByteArray,
        onFailure: (Exception) -> Unit,
    ) {
        val delegate = overrideDelegate ?: RealDelegate
        delegate.sendMessage(context, nodeId, path, payload) { error -> onFailure(error) }
    }

    @VisibleForTesting
    fun overrideForTests(delegate: Delegate?) {
        overrideDelegate = delegate
    }

    private object RealDelegate : Delegate {
        override fun sendMessage(
            context: Context,
            nodeId: String,
            path: String,
            payload: ByteArray,
            onFailure: FailureListener,
        ) {
            Wearable.getMessageClient(context)
                .sendMessage(nodeId, path, payload)
                .addOnFailureListener { error -> onFailure.onFailure(error) }
        }
    }
}
