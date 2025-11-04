package dev.pointtosky.mobile.catalog

import android.content.Context
import dev.pointtosky.core.catalog.runtime.CatalogRepository

object CatalogRepositoryProvider {
    @Volatile
    private var repository: CatalogRepository? = null

    fun get(context: Context): CatalogRepository {
        return repository ?: synchronized(this) {
            repository ?: CatalogRepository.create(context).also { repository = it }
        }
    }
}
