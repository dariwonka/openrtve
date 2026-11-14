package es.openrtve.ui.tv

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import es.openrtve.AppContainer
import es.openrtve.R
import es.openrtve.boing.Boing
import es.openrtve.boing.Boing.asItem
import es.openrtve.boing.Boing.ordered
import es.openrtve.boing.BoingEpisode
import es.openrtve.domain.CatalogItem
import es.openrtve.playback.PlayerActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Página de una serie de Boing: sus capítulos en la misma cuadrícula que una serie de RTVE.
 * Salen primero los de la copia de la app; al abrir se pide la lista actual a Boing y, solo de
 * los capítulos que no se conocían, su duración (que se guarda para siempre).
 */
@Composable
fun TvBoingScreen(container: AppContainer, program: CatalogItem, onMessage: (String) -> Unit) {
    val context = LocalContext.current
    val api = container.boingApi
    val guid = program.id.removePrefix(Boing.PREFIX)
    val bundled = remember(container, guid) { container.boingCatalog.firstOrNull { it.guid == guid }?.episodes.orEmpty() }
    var episodes by remember { mutableStateOf(bundled) }
    var loaded by remember { mutableStateOf(false) }
    val history by container.watchHistory.entries.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()

    LaunchedEffect(guid) {
        val fresh = withContext(Dispatchers.IO) { api.chapters(guid) }
        loaded = true
        if (fresh == null) return@LaunchedEffect
        val known = bundled.associateBy { it.guid }
        val saved = container.boingDurations.read()
        fun withKnown(e: BoingEpisode): BoingEpisode {
            val old = known[e.guid]
            val cached = saved[e.slug]
            return when {
                old != null && old.seconds > 0 -> e.copy(seconds = old.seconds, number = old.number.takeIf { it > 0 } ?: e.number)
                cached != null -> e.copy(seconds = cached.first, number = cached.second.takeIf { it > 0 } ?: e.number)
                else -> e
            }
        }
        episodes = fresh.map(::withKnown).ordered()
        // Solo los capítulos nuevos: se pide su ficha una vez para saber cuánto duran.
        for (episode in episodes.filter { it.seconds <= 0 }) {
            val info = withContext(Dispatchers.IO) { api.video(episode.slug)?.let(Boing::parseVideoInfo) } ?: continue
            container.boingDurations.put(episode.slug, info.first, info.second)
            episodes = episodes.map { if (it.guid == episode.guid) it.copy(seconds = info.first, number = info.second.takeIf { n -> n > 0 } ?: it.number) else it }.ordered()
        }
    }

    val items = remember(episodes) { episodes.map { it.asItem(program.id, program.title) } }
    val gridState = rememberLazyGridState()
    val byId = remember(history) { history.associateBy { it.item.id } }

    Column(Modifier.fillMaxSize()) {
        TvScreenTitle(program.title)
        when {
            items.isNotEmpty() -> ProvideTvCenteredScroll {
                LazyVerticalGrid(
                    state = gridState,
                    columns = GridCells.Fixed(TvGridColumns),
                    contentPadding = PaddingValues(start = TvHorizontalMargin, end = TvHorizontalMargin, top = TvFocusScaleRoom, bottom = TvVerticalMargin),
                    horizontalArrangement = Arrangement.spacedBy(20.dp),
                    verticalArrangement = Arrangement.spacedBy(24.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    itemsIndexed(items, key = { _, it -> it.id }) { index, item ->
                        val entry = byId[item.id]
                        TvEpisodeCard(
                            item = item,
                            onClick = {
                                scope.launch {
                                    val decision = withContext(Dispatchers.IO) {
                                        api.video(item.id.removePrefix(Boing.PREFIX).let { g -> episodes.first { it.guid == g }.slug })
                                            ?.let { Boing.pickStream(it, item.title) }
                                    }
                                    if (decision == null) {
                                        onMessage("No se pudo cargar este vídeo de Boing")
                                    } else {
                                        container.watchHistory.register(item)
                                        context.startActivity(
                                            PlayerActivity.intent(context, decision.copy(historyKey = item.id), false, listOfNotNull(item.id, item.programId)),
                                        )
                                    }
                                }
                            },
                            modifier = (if (index == 0) Modifier.initialFocus() else Modifier).wrapRowFocus(index, items.size),
                            progress = entry?.takeIf { it.inProgress }?.progress,
                            badge = if (entry?.finished == true) stringResource(R.string.episode_watched) else null,
                        )
                    }
                }
            }
            loaded -> TvCenterMessage("Ahora no hay capítulos disponibles")
            else -> TvCenterMessage(stringResource(R.string.state_loading))
        }
    }
}
