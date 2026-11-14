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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.activity.ComponentActivity
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import es.openrtve.domain.CatalogItem
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
fun TvSvtScreen(container: AppContainer, onMessage: (String) -> Unit, focusFirstTick: Int = 0) {
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
    // Solo vale al volver de un programa a la lista; entrar de nuevo en SVT empieza por el primero.
    var lastProgramId by remember { mutableStateOf<String?>(null) }
    // Pulsar SVT Barn estando dentro de un programa vuelve a la lista.
    var handledTick by remember { mutableIntStateOf(focusFirstTick) }
    LaunchedEffect(focusFirstTick) {
        if (focusFirstTick == handledTick) return@LaunchedEffect
        handledTick = focusFirstTick
        openId = null
        lastProgramId = null
    }
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
            open == null -> ProgramGrid(programs, lastProgramId, focusFirstTick, onExit = { (context as? ComponentActivity)?.finish() }) { program ->
                lastProgramId = program.id
                openId = program.id
            }
            else -> {
                val episodes = loadedEpisodes[open.id] ?: open.episodes
                when {
                    episodes.isNotEmpty() -> EpisodeGrid(open.id, episodes, focusFirstTick, onExit = { openId = null }) { episode ->
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
private fun ProgramGrid(programs: List<SvtProgram>, focusId: String?, focusFirstTick: Int, onExit: () -> Unit, onOpen: (SvtProgram) -> Unit) {
    val items = remember(programs) { programs.map { it.id to it.asItem() } }
    val byId = remember(programs) { programs.associateBy { it.id } }
    SvtCardGrid(items, focusId, focusFirstTick, episodic = false, onExit = onExit) { id -> byId[id]?.let(onOpen) }
}

@Composable
private fun EpisodeGrid(programId: String, episodes: List<SvtEpisode>, focusFirstTick: Int, onExit: () -> Unit, onPlay: (SvtEpisode) -> Unit) {
    val items = remember(programId, episodes) { episodes.map { it.videoId to it.asItem() } }
    val byId = remember(programId, episodes) { episodes.associateBy { it.videoId } }
    SvtCardGrid(items, null, focusFirstTick, episodic = true, onExit = onExit) { id -> byId[id]?.let(onPlay) }
}

/**
 * Cuadrícula de SVT con el mismo manejo del cursor que Series y Películas: al entrar queda en
 * [startKey] (o el primero); al pulsar la pestaña otra vez, en el primero; y Atrás lleva al primero
 * y, estando ya en él, llama a [onExit].
 */
@Composable
private fun SvtCardGrid(
    entries: List<Pair<String, CatalogItem>>,
    startKey: String?,
    focusFirstTick: Int,
    episodic: Boolean,
    onExit: () -> Unit,
    onClick: (String) -> Unit,
) {
    val state = rememberLazyGridState()
    val scope = rememberCoroutineScope()
    var focusedIndex by remember { mutableIntStateOf(-1) }
    val start = remember(entries.size, startKey) { entries.indexOfFirst { it.first == startKey }.coerceAtLeast(0) }
    val startRequester = remember { FocusRequester() }
    val firstRequester = if (start == 0) startRequester else remember { FocusRequester() }
    var handledTick by remember { mutableIntStateOf(focusFirstTick) }
    LaunchedEffect(Unit) {
        if (start > 0) state.scrollToItem(start)
        focusUntil(startRequester) { focusedIndex >= 0 }
    }
    LaunchedEffect(focusFirstTick) {
        if (focusFirstTick == handledTick) return@LaunchedEffect
        handledTick = focusFirstTick
        state.scrollToItem(0)
        focusUntil(firstRequester) { focusedIndex == 0 }
    }
    BackHandler {
        if (focusedIndex == 0) onExit()
        else scope.launch {
            state.scrollToItem(0)
            focusUntil(firstRequester) { focusedIndex == 0 }
        }
    }
    ProvideTvCenteredScroll {
        LazyVerticalGrid(
            state = state,
            columns = GridCells.Fixed(TvGridColumns),
            contentPadding = PaddingValues(start = TvHorizontalMargin, end = TvHorizontalMargin, top = TvFocusScaleRoom, bottom = TvVerticalMargin),
            horizontalArrangement = Arrangement.spacedBy(20.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            itemsIndexed(entries, key = { _, it -> it.first }) { index, (key, item) ->
                val requester = when (index) {
                    start -> startRequester
                    0 -> firstRequester
                    else -> null
                }
                TvEpisodeCard(
                    item = item,
                    onClick = { onClick(key) },
                    modifier = (if (requester != null) Modifier.focusRequester(requester) else Modifier)
                        .onFocusChanged { if (it.hasFocus) focusedIndex = index else if (focusedIndex == index) focusedIndex = -1 }
                        .wrapRowFocus(index, entries.size),
                    episodic = episodic,
                )
            }
        }
    }
}
