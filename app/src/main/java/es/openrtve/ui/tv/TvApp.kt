package es.openrtve.ui.tv

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.focusGroup
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.NavigationDrawer
import androidx.tv.material3.NavigationDrawerItem
import androidx.tv.material3.NavigationDrawerScope
import androidx.tv.material3.Text
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

/**
 * Raíz de TV según las guías de Compose for TV: `NavigationDrawer` en el borde
 * izquierdo (rail de iconos que se expande al enfocar) y el contenido a la
 * derecha. Atrás lo maneja el mando; no hay botones de "Atrás" en pantalla.
 */
@Composable
fun TvApp(container: AppContainer, incomingLinks: Flow<String> = emptyFlow()) {
    val repository = container.catalogRepository
    val navigation: NavigationViewModel = viewModel()
    val nav by navigation.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val actions = rememberCatalogActions(container, navigation)

    BackHandler(enabled = nav.canGoBack, onBack = navigation::pop)

    val message = nav.message?.text(context)
    val pending = nav.message
    if (pending != null && pending != UiMessage.OpeningLink) {
        // "Abriendo enlace…" se queda hasta que el enlace se resuelve.
        LaunchedEffect(pending) {
            delay(MESSAGE_VISIBLE_MS)
            navigation.dismissMessage(pending)
        }
    }

    LaunchedEffect(incomingLinks, actions) {
        incomingLinks.collect(actions::openIncomingLink)
    }

    val destination = nav.current
    ProvideClock {
        NavigationDrawer(
            drawerContent = {
                // Al entrar en el menú desde el contenido, el foco va a la sección activa,
                // no a la entrada que geométricamente quede más cerca.
                val selectedEntry = remember { FocusRequester() }
                Column(
                    modifier = Modifier
                        .fillMaxHeight()
                        .background(MaterialTheme.colorScheme.surface)
                        .padding(12.dp)
                        .focusProperties { onEnter = { selectedEntry.requestFocus() } }
                        .focusGroup(),
                ) {
                    Spacer(Modifier.height(TvVerticalMargin))
                    DrawerEntry(Icons.Filled.Home, stringResource(R.string.tab_home), nav.global == null && nav.tab == Tab.HOME, selectedEntry) { navigation.selectTab(Tab.HOME) }
                    DrawerEntry(Icons.Filled.Search, stringResource(R.string.tab_search), nav.global == null && nav.tab == Tab.SEARCH, selectedEntry) { navigation.selectTab(Tab.SEARCH) }
                    DrawerEntry(Icons.Filled.Menu, stringResource(R.string.tab_explore), nav.global == null && nav.tab == Tab.EXPLORE, selectedEntry) { navigation.selectTab(Tab.EXPLORE) }
                    DrawerEntry(Icons.Filled.Settings, stringResource(R.string.settings_title), destination == GlobalDestination.Settings, selectedEntry) {
                        if (destination != GlobalDestination.Settings) actions.openSettings()
                    }
                }
            },
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background),
        ) {
            Box(Modifier.fillMaxSize()) {
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
                            onOpenItem = actions.playItem,
                            onOpenRelated = actions.openItem,
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
                        null -> when (nav.tab) {
                            Tab.HOME -> TvPortadaScreen(
                                repository = repository,
                                url = RtveUrls.TV_HOME,
                                title = "",
                                history = container.watchHistory,
                                onOpenItem = actions.openItem,
                                onOpenRow = actions.openRow,
                                onOpenLink = actions.openLink,
                                onResume = actions.playItem,
                                onRemoveFromHistory = actions.removeFromHistory,
                                reselectRequest = nav.rootRequests.getValue(Tab.HOME),
                            )
                            Tab.SEARCH -> TvSearchScreen(repository, actions.openItem, reselectRequest = nav.rootRequests.getValue(Tab.SEARCH))
                            Tab.EXPLORE -> TvExploreScreen(repository, actions.openCategory, actions.openItem, reselectRequest = nav.rootRequests.getValue(Tab.EXPLORE))
                        }
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

@Composable
private fun NavigationDrawerScope.DrawerEntry(icon: ImageVector, label: String, selected: Boolean, selectedFocus: FocusRequester, onClick: () -> Unit) {
    NavigationDrawerItem(
        selected = selected,
        onClick = onClick,
        modifier = if (selected) Modifier.focusRequester(selectedFocus) else Modifier,
        leadingContent = { Icon(icon, contentDescription = null) },
    ) {
        Text(label, fontWeight = FontWeight.SemiBold)
    }
}

private const val MESSAGE_VISIBLE_MS = 4_000L
