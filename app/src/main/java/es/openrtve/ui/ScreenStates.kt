package es.openrtve.ui

import androidx.activity.compose.ReportDrawnWhen
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import es.openrtve.AppContainer
import es.openrtve.data.CatalogRepository
import es.openrtve.data.Settings
import es.openrtve.data.WatchEntry
import es.openrtve.data.WatchHistory
import es.openrtve.domain.CatalogItem
import es.openrtve.domain.HomeRow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter

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

class PortadaScreenState(
    val viewModel: PortadaViewModel,
    val state: PortadaUiState,
    /** "Seguir viendo", vacío si la portada no lleva historial. */
    val resumable: List<WatchEntry>,
)

/** Refresco como la app oficial: al volver a primer plano y, para los directos, cada minuto en pantalla. */
@Composable
fun rememberPortadaScreen(
    repository: CatalogRepository,
    url: String,
    history: WatchHistory?,
): PortadaScreenState {
    val viewModel: PortadaViewModel = viewModel(
        key = "portada-$url",
        factory = viewModelFactory { initializer { PortadaViewModel(repository, url) } },
    )
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val historyEntries by (history?.entries ?: remember { MutableStateFlow(emptyList()) }).collectAsStateWithLifecycle()
    val resumable = remember(historyEntries) { history?.resumable.orEmpty() }
    // Arranque completo (Play Vitals, Macrobenchmark): la primera fila ya tiene contenido
    // o la pantalla explica el fallo. Solo cuenta la primera vez en la actividad.
    ReportDrawnWhen { !state.isLoading && state.sections.firstOrNull()?.state !is SectionState.Loading }

    LifecycleResumeEffect(viewModel) {
        viewModel.onResumed()
        onPauseOrDispose { }
    }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(viewModel) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (true) {
                delay(LIVE_REFRESH_MS)
                viewModel.refreshLiveSections()
            }
        }
    }
    // Sin ErrorEffect: el fallo de la portada se pinta en su sitio (ver PortadaUiState.error).
    return PortadaScreenState(viewModel, state, resumable)
}

class ModuleScreenState(val viewModel: ModuleViewModel, val state: ModuleUiState)

@Composable
fun rememberModuleScreen(repository: CatalogRepository, row: HomeRow, title: String, onError: (String) -> Unit): ModuleScreenState {
    val viewModel: ModuleViewModel = viewModel(
        key = "module-${row.contentUrl}",
        factory = viewModelFactory { initializer { ModuleViewModel(repository, row, title) } },
    )
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    ErrorEffect(state.error, onError, viewModel::dismissError)
    return ModuleScreenState(viewModel, state)
}

class ProgramScreenState(
    val viewModel: ProgramViewModel,
    val state: ProgramUiState,
    /** Historial por id de episodio: progreso y "visto". */
    val entriesById: Map<String, WatchEntry>,
    /** Qué reproduce el botón grande. */
    val suggestion: PlaySuggestion?,
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
    // De la lista completa, no de la temporada que se esté mirando: el botón grande es estable.
    val suggestion = remember(historyEntries, state.headerEpisodes, state.headerOrder, state.structure) {
        if (state.structure.isCollection) null else suggestPlay(history, program.id, state.headerEpisodes, state.headerOrder) {
            it.contentTab(state.structure) == ProgramTab.EPISODES
        }
    }
    // Sin ErrorEffect: cada sección explica su fallo en el sitio, con su reintento.
    return ProgramScreenState(viewModel, state, entriesById, suggestion)
}

/**
 * Paginación infinita: pide más cuando el último visible está a menos de
 * [threshold] del final. Se reevalúa también cuando cambia el total, así una
 * página corta que no saca al usuario de la zona final encadena la siguiente.
 */
@Composable
fun LoadMoreOnScrollEnd(listState: LazyListState, threshold: Int, enabled: Boolean = true, contentKey: Any? = null, loadMore: () -> Unit) {
    LaunchedEffect(listState, enabled, contentKey) {
        if (!enabled) return@LaunchedEffect
        snapshotFlow {
            val info = listState.layoutInfo
            (info.visibleItemsInfo.lastOrNull()?.index ?: -1) to info.totalItemsCount
        }
            .distinctUntilChanged()
            .filter { (last, total) -> total > 0 && last >= total - threshold }
            .collect { loadMore() }
    }
}

class VideoScreenState(
    val viewModel: VideoViewModel,
    val state: VideoUiState,
    /** Entrada a medias del historial, si la hay. */
    val resume: WatchEntry?,
)

@Composable
fun rememberVideoScreen(
    repository: CatalogRepository,
    history: WatchHistory,
    item: CatalogItem,
): VideoScreenState {
    val viewModel: VideoViewModel = viewModel(
        key = "video-${item.id}",
        factory = viewModelFactory { initializer { VideoViewModel(repository, item) } },
    )
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val historyEntries by history.entries.collectAsStateWithLifecycle()
    val resume = remember(historyEntries, state.item.id) { history.entryFor(state.item.id)?.takeIf { it.inProgress } }
    // Sin ErrorEffect: si falla la ficha, la pantalla lo explica en su sitio con su reintento.
    return VideoScreenState(viewModel, state, resume)
}

class SearchScreenState(val viewModel: SearchViewModel, val state: SearchUiState)

@Composable
fun rememberSearchScreen(repository: CatalogRepository): SearchScreenState {
    val viewModel: SearchViewModel = viewModel(factory = viewModelFactory { initializer { SearchViewModel(repository) } })
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    // Sin ErrorEffect: filtros y búsqueda explican su fallo en el sitio, con su reintento.
    return SearchScreenState(viewModel, state)
}

class ExploreScreenState(val viewModel: ExploreViewModel, val state: ExploreUiState)

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

@Composable
fun rememberExploreScreen(repository: CatalogRepository, tv: Boolean = false): ExploreScreenState {
    val viewModel: ExploreViewModel = viewModel(factory = viewModelFactory { initializer { ExploreViewModel(repository, tv) } })
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    return ExploreScreenState(viewModel, state)
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

private const val LIVE_REFRESH_MS = 60_000L
