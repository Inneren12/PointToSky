package dev.pointtosky.core.catalog.io

import android.content.Context
import java.io.FileNotFoundException
import java.io.InputStream

/**
 * Android-backed implementation of [AssetProvider] that reads from the app's
 * bundled assets directory.
 */
class AndroidAssetProvider(private val context: Context) : AssetProvider {
    private val assetManager = context.assets

    override fun open(path: String): InputStream {
        return assetManager.open(path)
    }

    override fun exists(path: String): Boolean {
        return try {
            assetManager.open(path).close()
            true
        } catch (_: FileNotFoundException) {
            false
        }
    }

    override fun list(path: String): List<String> {
        return assetManager.list(path)?.toList().orEmpty()
    }
}
