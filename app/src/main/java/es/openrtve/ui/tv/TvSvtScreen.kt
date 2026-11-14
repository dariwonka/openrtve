package es.openrtve.ui.tv

import android.content.Context
import androidx.activity.compose.BackHandler
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import es.openrtve.AppContainer
import es.openrtve.playback.PlayerActivity
import androidx.compose.runtime.mutableStateMapOf
import es.openrtve.svt.SvtApi
import es.openrtve.svt.SvtBarn
import es.openrtve.svt.SvtEpisode
import es.openrtve.svt.SvtBarn.asItem
import es.openrtve.svt.SvtProgram
import es.openrtve.svt.SvtStreamResolver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * SVT Barn: los programas infantiles de SVT Play (en sueco) en una cuadrícula y, al abrir uno,
 * sus episodios. Lo que se reproduce se pide a SVT en ese momento.
 */
@Composable
fun TvSvtScreen(container: AppContainer, onMessage: (String) -> Unit) {
    val context = LocalContext.current
    val api = remember(container) { SvtApi(container.mediaHttpClient) }
    val resolver = remember(container) { SvtStreamResolver(container.mediaHttpClient) }
    // Primero la copia guardada (sale al momento); luego la lista actual de SVT la sustituye.
    val saved by produceSvtPrograms(context)
    var fresh by remember { mutableStateOf<List<SvtProgram>?>(null) }
    LaunchedEffect(Unit) {
        fresh = withContext(Dispatchers.IO) { runCatching { api.programs() }.getOrNull()?.takeIf { it.isNotEmpty() } }
    }
    val programs = remember(saved, fresh) {
        val bySlug = saved.associateBy { it.slug }
        fresh?.map { p -> p.copy(image = p.image.ifBlank { bySlug[p.slug]?.image.orEmpty() }, episodes = bySlug[p.slug]?.episodes.orEmpty()) } ?: saved
    }
    var openId by rememberSaveable { mutableStateOf<String?>(null) }
    var lastProgramId by rememberSaveable { mutableStateOf<String?>(null) }
    val loadedEpisodes = remember { mutableStateMapOf<String, List<SvtEpisode>>() }
    val scope = rememberCoroutineScope()
    val open = programs.firstOrNull { it.id == openId }
    BackHandler(enabled = open != null) { openId = null }
    // Al abrir un programa se piden sus episodios actuales; mientras llegan se enseñan los guardados.
    LaunchedEffect(open?.id) {
        val program = open ?: return@LaunchedEffect
        val episodes = withContext(Dispatchers.IO) { api.episodes(program.slug) }
        if (episodes != null) loadedEpisodes[program.id] = episodes
    }

    Column(Modifier.fillMaxSize()) {
        TvScreenTitle(open?.title ?: "SVT Barn")
        when {
            programs.isEmpty() -> TvCenterMessage(if (saved.isEmpty() && fresh == null) "Cargando…" else "SVT Barn no está disponible ahora")
            open == null -> ProgramGrid(programs, lastProgramId) { program ->
                lastProgramId = program.id
                openId = program.id
            }
            else -> {
                val episodes = loadedEpisodes[open.id] ?: open.episodes
                when {
                    episodes.isNotEmpty() -> EpisodeGrid(open.id, episodes) { episode ->
                        scope.launch {
                            val decision = runCatching { withContext(Dispatchers.IO) { resolver.resolve(episode.videoId, episode.title) } }.getOrNull()
                            if (decision == null) onMessage("No se pudo cargar este vídeo de SVT")
                            else context.startActivity(PlayerActivity.intent(context, decision))
                        }
                    }
                    open.id in loadedEpisodes -> TvCenterMessage("Ahora no hay episodios disponibles")
                    else -> TvCenterMessage("Cargando…")
                }
            }
        }
    }
}

@Composable
private fun produceSvtPrograms(context: Context) = androidx.compose.runtime.produceState(emptyList<SvtProgram>()) {
    value = withContext(Dispatchers.IO) {
        runCatching { context.assets.open("svt_barn.json").bufferedReader().use { SvtBarn.parse(it.readText()) } }.getOrDefault(emptyList())
    }
}

@Composable
private fun ProgramGrid(programs: List<SvtProgram>, focusId: String?, onOpen: (SvtProgram) -> Unit) {
    val items = remember(programs) { programs.map { it to it.asItem() } }
    val state = rememberLazyGridState()
    val start = remember(items, focusId) { items.indexOfFirst { it.first.id == focusId }.coerceAtLeast(0) }
    LaunchedEffect(Unit) { if (start > 0) state.scrollToItem(start) }
    ProvideTvCenteredScroll {
        LazyVerticalGrid(
            state = state,
            columns = GridCells.Fixed(TvGridColumns),
            contentPadding = PaddingValues(start = TvHorizontalMargin, end = TvHorizontalMargin, top = TvFocusScaleRoom, bottom = TvVerticalMargin),
            horizontalArrangement = Arrangement.spacedBy(20.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            itemsIndexed(items, key = { _, it -> it.first.id }) { index, (program, item) ->
                TvEpisodeCard(
                    item = item,
                    onClick = { onOpen(program) },
                    modifier = (if (index == start) Modifier.initialFocus() else Modifier).wrapRowFocus(index, items.size),
                    episodic = false,
                )
            }
        }
    }
}

@Composable
private fun EpisodeGrid(programId: String, episodes: List<SvtEpisode>, onPlay: (SvtEpisode) -> Unit) {
    val items = remember(programId, episodes) { episodes.map { it to it.asItem() } }
    ProvideTvCenteredScroll {
        LazyVerticalGrid(
            state = rememberLazyGridState(),
            columns = GridCells.Fixed(TvGridColumns),
            contentPadding = PaddingValues(start = TvHorizontalMargin, end = TvHorizontalMargin, top = TvFocusScaleRoom, bottom = TvVerticalMargin),
            horizontalArrangement = Arrangement.spacedBy(20.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            itemsIndexed(items, key = { _, it -> it.first.videoId }) { index, (episode, item) ->
                TvEpisodeCard(
                    item = item,
                    onClick = { onPlay(episode) },
                    modifier = (if (index == 0) Modifier.initialFocus() else Modifier).wrapRowFocus(index, items.size),
                    episodic = true,
                )
            }
        }
    }
}
