package dev.pointtosky.core.catalog.runtime

sealed interface CatalogLoadState {
    object Loading : CatalogLoadState
    data class Ready(val repository: CatalogRepository) : CatalogLoadState
}
