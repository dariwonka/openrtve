package es.openrtve.ui.mobile

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffold
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import es.openrtve.AppContainer
import es.openrtve.R
import es.openrtve.domain.RtveUrls
import es.openrtve.ui.Destination
import es.openrtve.ui.DestinationHost
import es.openrtve.ui.GlobalDestination
import es.openrtve.ui.NavigationViewModel
import es.openrtve.ui.Tab
import es.openrtve.ui.UiMessage
import es.openrtve.ui.rememberCatalogActions
import es.openrtve.ui.ProvideClock
import es.openrtve.ui.text
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

@Composable
fun MobileApp(
    container: AppContainer,
    /** Enlaces recibidos por la actividad (VIEW o Compartir). */
    incomingLinks: Flow<String> = emptyFlow(),
) {
    val repository = container.catalogRepository
    val navigation: NavigationViewModel = viewModel()
    val nav by navigation.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }
    val actions = rememberCatalogActions(container, navigation)

    BackHandler(enabled = nav.canGoBack, onBack = navigation::pop)

    val undoLabel = stringResource(R.string.action_undo)
    nav.message?.let { message ->
        val text = message.text(context)
        LaunchedEffect(message) {
            val undo = undoLabel.takeIf { message is UiMessage.RemovedFromHistory }
            // "Abriendo enlace…" se queda hasta que el enlace se resuelve; con acción
            // ("Deshacer") el aviso dura más: cuatro segundos no dan para pulsarla.
            val duration = when {
                message == UiMessage.OpeningLink -> SnackbarDuration.Indefinite
                undo != null -> SnackbarDuration.Long
                else -> SnackbarDuration.Short
            }
            val result = snackbarHostState.showSnackbar(text, actionLabel = undo, duration = duration)
            if (result == SnackbarResult.ActionPerformed && message is UiMessage.RemovedFromHistory) actions.undoRemoveFromHistory(message)
            navigation.dismissMessage(message)
        }
    }

    LaunchedEffect(incomingLinks, actions) {
        incomingLinks.collect(actions::openIncomingLink)
    }

    ProvideClock {
        NavigationSuiteScaffold(
            navigationSuiteItems = { appNavigationItems(nav.tab, navigation::selectTab) },
            modifier = Modifier.fillMaxSize(),
        ) {
            Scaffold(
                modifier = Modifier.fillMaxSize(),
                snackbarHost = { SnackbarHost(snackbarHostState) },
                // Sin el inset superior: cada pantalla lo gestiona (la barra de
                // aplicación lo lleva dentro; la portada raíz y las fichas van a
                // sangre bajo la barra de estado).
                contentWindowInsets = WindowInsets.systemBars.only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal),
            ) { padding ->
                Box(
                    Modifier
                        .fillMaxSize()
                        .padding(padding),
                ) {
                    DestinationHost(nav, navigation) { destination ->
                        when (destination) {
                            is Destination.Portada -> PortadaScreen(
                                repository = repository,
                                url = destination.url,
                                title = destination.title,
                                onBack = navigation::pop,
                                onOpenItem = actions.openItem,
                                onOpenRow = actions.openRow,
                                onOpenLink = actions.openLink,
                            )
                            is Destination.Module -> ModuleScreen(
                                repository = repository,
                                row = destination.row,
                                title = destination.title,
                                onBack = navigation::pop,
                                onOpenItem = actions.openItem,
                                onError = actions.showMessage,
                            )
                            is Destination.Program -> ProgramScreen(
                                repository = repository,
                                history = container.watchHistory,
                                program = destination.item,
                                onBack = navigation::pop,
                                onOpenItem = actions.playItem,
                                onError = actions.showMessage,
                                onPlay = actions::play,
                                onOpenRelated = actions.openItem,
                            )
                            GlobalDestination.Settings -> SettingsScreen(
                                container = container,
                                onBack = navigation::pop,
                                onMessage = actions.showMessage,
                            )
                            is Destination.Video -> VideoScreen(
                                repository = repository,
                                history = container.watchHistory,
                                item = destination.item,
                                onBack = navigation::pop,
                                onPlay = actions::play,
                                onOpenItem = actions.openItem,
                                onOpenProgram = actions.openProgram,
                            )
                            null -> when (nav.tab) {
                                Tab.HOME -> PortadaScreen(
                                    repository = repository,
                                    url = RtveUrls.TV_HOME,
                                    title = "",
                                    history = container.watchHistory,
                                    onBack = null,
                                    onOpenItem = actions.openItem,
                                    onOpenRow = actions.openRow,
                                    onOpenLink = actions.openLink,
                                    onOpenSettings = actions.openSettings,
                                    onResume = actions.playItem,
                                    onRemoveFromHistory = actions.removeFromHistory,
                                )
                                Tab.SEARCH -> SearchScreen(
                                    repository = repository,
                                    onOpenItem = actions.openItem,
                                )
                                Tab.EXPLORE -> ExploreScreen(
                                    repository = repository,
                                    onOpenCategory = actions.openCategory,
                                    onOpenSettings = actions.openSettings,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun NavigationSuiteScope.appNavigationItems(selected: Tab, onSelect: (Tab) -> Unit) {
    item(
        selected = selected == Tab.HOME,
        onClick = { onSelect(Tab.HOME) },
        icon = { Icon(Icons.Filled.Home, contentDescription = null) },
        label = { Text(stringResource(R.string.tab_home)) },
    )
    item(
        selected = selected == Tab.SEARCH,
        onClick = { onSelect(Tab.SEARCH) },
        icon = { Icon(Icons.Filled.Search, contentDescription = null) },
        label = { Text(stringResource(R.string.tab_search)) },
    )
    item(
        selected = selected == Tab.EXPLORE,
        onClick = { onSelect(Tab.EXPLORE) },
        icon = { Icon(Icons.Filled.Menu, contentDescription = null) },
        label = { Text(stringResource(R.string.tab_explore)) },
    )
}
