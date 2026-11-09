package es.openrtve.ui.tv

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
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
import es.openrtve.ui.UiMessage
import es.openrtve.ui.rememberCatalogActions
import es.openrtve.ui.text
import kotlinx.coroutines.delay

/**
 * Raíz de la app: una barra superior fija (Series, Películas, Inicio y Ajustes)
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
        factory = viewModelFactory { initializer { ClanCatalogViewModel(repository) } },
    )
    val catalog by catalogViewModel.uiState.collectAsStateWithLifecycle()
    var section by rememberSaveable { mutableStateOf(ClanSection.SERIES) }

    BackHandler(enabled = nav.canGoBack, onBack = navigation::pop)

    val message = nav.message?.text(context)
    val pending = nav.message
    if (pending != null && pending != UiMessage.OpeningLink) {
        LaunchedEffect(pending) {
            delay(MESSAGE_VISIBLE_MS)
            navigation.dismissMessage(pending)
        }
    }

    val destination = nav.current
    // Elegir una lista o Inicio vuelve a la raíz desde cualquier pantalla.
    val showSection: (ClanSection) -> Unit = { chosen ->
        val atRoot = destination == null
        val changed = section != chosen
        section = chosen
        if (!atRoot || !changed) navigation.selectTab(Tab.HOME)
    }
    ProvideClock {
        Column(
            Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background),
        ) {
            TvTopBar(
                section = section.takeIf { destination == null },
                onSection = showSection,
                onHome = { showSection(ClanSection.SERIES) },
                onSettings = { if (destination != GlobalDestination.Settings) actions.openSettings() },
            )
            Box(Modifier.fillMaxWidth().weight(1f)) {
                DestinationHost(nav, navigation) { current ->
                    ProvideScreenInitialFocus { when (current) {
                        is Destination.Portada -> TvPortadaScreen(
                            repository = repository,
                            url = current.url,
                            title = current.title,
                            onOpenItem = actions.openItem,
                            onOpenRow = actions.openRow,
                            onOpenLink = actions.openLink,
                        )
                        is Destination.Module -> TvModuleScreen(
                            repository = repository,
                            row = current.row,
                            title = current.title,
                            onOpenItem = actions.openItem,
                            onError = actions.showMessage,
                        )
                        is Destination.Program -> TvProgramScreen(
                            repository = repository,
                            history = container.watchHistory,
                            program = current.item,
                            onPlay = actions.playItem,
                        )
                        is Destination.Video -> TvVideoScreen(
                            repository = repository,
                            history = container.watchHistory,
                            item = current.item,
                            onPlay = actions::play,
                            onOpenProgram = actions.openProgram,
                            onOpenItem = actions.openItem,
                        )
                        GlobalDestination.Settings -> TvSettingsScreen(container, onMessage = actions.showMessage)
                        // Única raíz: los títulos de Clan. Una serie abre su página; una película se reproduce.
                        null -> TvCatalogScreen(
                            state = catalog,
                            section = section,
                            onOpen = { item -> if (item.kind == ContentKind.VIDEO) actions.play(item) else actions.openItem(item) },
                            onRetry = catalogViewModel::retry,
                            reselectRequest = nav.rootRequests.getValue(Tab.HOME),
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
}

private const val MESSAGE_VISIBLE_MS = 4_000L
