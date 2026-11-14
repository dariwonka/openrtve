package es.openrtve.ui.tv

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.lifecycle.repeatOnLifecycle
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import es.openrtve.AppContainer
import es.openrtve.domain.ContentKind
import es.openrtve.ui.ClanCatalogViewModel
import es.openrtve.ui.ClanSection
import es.openrtve.ui.Destination
import es.openrtve.ui.DestinationHost
import es.openrtve.ui.GlobalDestination
import es.openrtve.ui.NavigationViewModel
import es.openrtve.ui.ProvideClock
import es.openrtve.ui.Tab
import es.openrtve.ui.rememberCatalogActions
import es.openrtve.ui.text
import kotlinx.coroutines.delay

/**
 * Raíz de la app: una barra superior fija (Series, Películas y Ajustes)
 * y, debajo, la pantalla actual. No hay menú lateral. Atrás lo maneja el mando.
 */
@Composable
fun TvApp(container: AppContainer) {
    val repository = container.catalogRepository
    val navigation: NavigationViewModel = viewModel()
    val nav by navigation.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val actions = rememberCatalogActions(container, navigation)
    // Un solo catálogo para toda la sesión: volver al inicio no lo descarga otra vez.
    val catalogViewModel: ClanCatalogViewModel = viewModel(
        key = "clan-catalog",
        factory = viewModelFactory { initializer { ClanCatalogViewModel(repository, container.clanCatalogStore, container.infantilSeed, container.movieSeed) } },
    )
    val catalog by catalogViewModel.uiState.collectAsStateWithLifecycle()
    var section by rememberSaveable { mutableStateOf(ClanSection.SERIES) }
    // El desplazamiento de cada lista y la serie abierta sobreviven a entrar en una serie y volver.
    val seriesGrid = rememberLazyGridState()
    val moviesGrid = rememberLazyGridState()
    var lastOpened by rememberSaveable { mutableStateOf<String?>(null) }
    // Lo que ya sale suelto en Películas (o se ha eliminado) no se repite dentro de su serie.
    val outsideSeries = remember(catalog.catalog.movies, catalog.removed) {
        catalog.catalog.movies.mapTo(HashSet()) { it.id } + catalog.removed.map { it.id }
    }

    // El reproductor puede eliminar un título; al volver se refleja.
    val lifecycle = (context as? androidx.activity.ComponentActivity)?.lifecycle
    LaunchedEffect(lifecycle) {
        lifecycle?.repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.RESUMED) { catalogViewModel.syncFromStore() }
    }

    BackHandler(enabled = nav.canGoBack, onBack = navigation::pop)

    val message = nav.message?.text(context)
    val pending = nav.message
    if (pending != null) {
        LaunchedEffect(pending) {
            delay(MESSAGE_VISIBLE_MS)
            navigation.dismissMessage(pending)
        }
    }

    val destination = nav.current
    // Elegir una lista o Inicio vuelve a la raíz desde cualquier pantalla.
    // Cada vez que se pulsa Series, Películas o SVT Barn el cursor va al primer contenido.
    var focusFirstTick by remember { mutableIntStateOf(0) }
    val showSection: (ClanSection) -> Unit = { chosen ->
        focusFirstTick++
        lastOpened = null
        val atRoot = destination == null && nav.tab == Tab.HOME
        val changed = section != chosen
        section = chosen
        if (changed) lastOpened = null
        if (!atRoot || !changed) {
            navigation.selectTab(Tab.HOME)
            // Si al volver a Inicio quedaba una serie abierta, se cierra: el botón lleva a la lista.
            if (navigation.state.value.current != null) navigation.selectTab(Tab.HOME)
        }
    }
    Box(Modifier.fillMaxSize()) {
    ProvideClock {
        Column(
            Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background),
        ) {
            TvTopBar(
                section = section.takeIf { destination == null && nav.tab == Tab.HOME },
                onSection = showSection,
                onSearch = { navigation.selectTab(Tab.SEARCH) },
                onSettings = { if (destination != GlobalDestination.Settings) actions.openSettings() },
                onSvt = {
                    focusFirstTick++
                    if (destination != GlobalDestination.Svt) actions.openSvt()
                },
                svtSelected = destination == GlobalDestination.Svt,
            )
            Box(Modifier.fillMaxWidth().weight(1f)) {
                DestinationHost(nav, navigation) { current ->
                    ProvideScreenInitialFocus { when (current) {
                        is Destination.Program -> TvProgramScreen(
                            repository = repository,
                            history = container.watchHistory,
                            program = current.item,
                            onPlay = actions.playItem,
                            hiddenIds = outsideSeries,
                        )
                        GlobalDestination.Settings -> TvSettingsScreen(container, onMessage = actions.showMessage, removed = catalog.removed, onRestore = catalogViewModel::restore, onBackupRestored = catalogViewModel::reloadFromStore)
                        GlobalDestination.Svt -> TvSvtScreen(container, onMessage = actions.showMessage, focusFirstTick = focusFirstTick)
                        // Dos raíces: el buscador y los títulos de Clan. Una serie abre su página; un vídeo se reproduce.
                        null -> if (nav.tab == Tab.SEARCH) TvSearchScreen(
                            repository = repository,
                            onOpenItem = { item -> if (item.kind == ContentKind.VIDEO) actions.play(item) else actions.openItem(item) },
                            knownIds = catalog.knownIds,
                            pinnedIds = catalog.pinnedIds,
                            onAdd = catalogViewModel::add,
                            onTogglePin = catalogViewModel::togglePin,
                            onRemove = catalogViewModel::remove,
                            isRemovalUnlocked = { container.removalUnlocked },
                            onUnlockRemoval = { container.removalUnlocked = true },
                            reselectRequest = nav.rootRequests.getValue(Tab.SEARCH),
                        ) else TvCatalogScreen(
                            state = catalog,
                            section = section,
                            onOpen = { item ->
                                lastOpened = item.id
                                if (item.kind == ContentKind.VIDEO) actions.play(item) else actions.openItem(item)
                            },
                            gridState = when (section) {
                                ClanSection.SERIES -> seriesGrid
                                ClanSection.MOVIES -> moviesGrid
                            },
                            onTogglePin = catalogViewModel::togglePin,
                            onRemove = catalogViewModel::remove,
                            isRemovalUnlocked = { container.removalUnlocked },
                            onUnlockRemoval = { container.removalUnlocked = true },
                            focusId = lastOpened,
                            onRetry = catalogViewModel::retry,
                            reselectRequest = nav.rootRequests.getValue(Tab.HOME),
                            focusFirstTick = focusFirstTick,
                        )
                    } }
                }
                if (message != null) {
                    Text(
                        text = message,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(bottom = TvVerticalMargin)
                            .background(MaterialTheme.colorScheme.primary, TvCardShape)
                            .padding(horizontal = 20.dp, vertical = 10.dp),
                    )
                }
            }
        }
    }
    // Pantalla de inicio solo hasta que hay catálogo: con copia guardada, nada más leerla.
    androidx.compose.animation.AnimatedVisibility(
        visible = !catalog.ready,
        exit = androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(SPLASH_FADE_MS)),
    ) {
        androidx.compose.foundation.Image(
            painter = androidx.compose.ui.res.painterResource(es.openrtve.R.drawable.splash),
            contentDescription = null,
            contentScale = androidx.compose.ui.layout.ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )
    }
    }
}

private const val MESSAGE_VISIBLE_MS = 4_000L
private const val SPLASH_FADE_MS = 150
