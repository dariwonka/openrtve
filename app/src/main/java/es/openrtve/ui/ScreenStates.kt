package es.openrtve.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import es.openrtve.AppContainer
import es.openrtve.data.CatalogRepository
import es.openrtve.data.Settings
import es.openrtve.data.WatchEntry
import es.openrtve.data.WatchHistory
import es.openrtve.domain.CatalogItem

/*
 * Estado de cada pantalla, común a móvil y TV: crear el ViewModel, observar su
 * estado, cruzarlo con el historial y entregar los errores una vez. Las
 * pantallas solo pintan.
 */

/** Un error de carga se muestra una sola vez (snackbar o aviso) y se descarta. */
@Composable
fun ErrorEffect(error: LoadError?, onError: (String) -> Unit, dismiss: () -> Unit) {
    val context = LocalContext.current
    if (error != null) {
        val text = error.text(context)
        LaunchedEffect(error) {
            onError(text)
            dismiss()
        }
    }
}

class ProgramScreenState(
    val viewModel: ProgramViewModel,
    val state: ProgramUiState,
    /** Historial por id de episodio: progreso y "visto". */
    val entriesById: Map<String, WatchEntry>,
)

@Composable
fun rememberProgramScreen(
    repository: CatalogRepository,
    history: WatchHistory,
    program: CatalogItem,
): ProgramScreenState {
    val viewModel: ProgramViewModel = viewModel(
        key = "program-${program.id}",
        factory = viewModelFactory { initializer { ProgramViewModel(repository, program.id, program.title) } },
    )
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val historyEntries by history.entries.collectAsStateWithLifecycle()
    val entriesById = remember(historyEntries) { historyEntries.associateBy { it.item.id } }
    // Sin ErrorEffect: la lista explica su fallo en el sitio, con su reintento.
    return ProgramScreenState(viewModel, state, entriesById)
}

class SearchScreenState(val viewModel: SearchViewModel, val state: SearchUiState)

@Composable
fun rememberSearchScreen(repository: CatalogRepository): SearchScreenState {
    val viewModel: SearchViewModel = viewModel(factory = viewModelFactory { initializer { SearchViewModel(repository) } })
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    // Sin ErrorEffect: filtros y búsqueda explican su fallo en el sitio, con su reintento.
    return SearchScreenState(viewModel, state)
}

/** Se consume una sola vez por raíz, conservando su scroll al cambiar de sección. */
@Composable
fun RootReselectionEffect(request: Int, onReselect: suspend () -> Unit) {
    var handled by rememberSaveable { mutableIntStateOf(0) }
    val action by rememberUpdatedState(onReselect)
    LaunchedEffect(request) {
        if (request > handled) {
            handled = request
            action()
        }
    }
}

class SettingsScreenState(
    val viewModel: SettingsViewModel,
    val settings: Settings,
    /** Tamaño de la caché; `null` mientras se calcula. */
    val cacheBytes: Long?,
    val versionName: String,
)

@Composable
fun rememberSettingsScreen(container: AppContainer): SettingsScreenState {
    val context = LocalContext.current
    val viewModel: SettingsViewModel = viewModel(
        factory = viewModelFactory {
            initializer { SettingsViewModel(container.settings, container.documentCache, context.applicationContext) }
        },
    )
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val cacheBytes by viewModel.cacheBytes.collectAsStateWithLifecycle()
    val versionName = remember { context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "?" }
    return SettingsScreenState(viewModel, settings, cacheBytes, versionName)
}

