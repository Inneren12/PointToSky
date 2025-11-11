package dev.pointtosky.mobile.search

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.pointtosky.core.catalog.runtime.CatalogRepository
import dev.pointtosky.mobile.card.CardRepository

@Composable
fun SearchRoute(
    catalogRepository: CatalogRepository,
    onBack: () -> Unit,
    onOpenCard: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val factory = rememberSearchViewModelFactory(context, catalogRepository)
    val viewModel: SearchViewModel = viewModel(factory = factory)
    val state by viewModel.state.collectAsStateWithLifecycle()

    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                is SearchViewModel.SearchEvent.OpenCard -> onOpenCard(event.cardId)
            }
        }
    }

    SearchScreen(
        state = state,
        onQueryChange = viewModel::onQueryChange,
        onResultClick = viewModel::onResultSelected,
        onBack = onBack,
        modifier = modifier.fillMaxSize(),
    )
}

@Composable
private fun rememberSearchViewModelFactory(
    context: android.content.Context,
    catalogRepository: CatalogRepository,
): SearchViewModel.Factory {
    val appContext = context.applicationContext
    return androidx.compose.runtime.remember(appContext, catalogRepository) {
        SearchViewModel.Factory(appContext, catalogRepository, CardRepository)
    }
}
