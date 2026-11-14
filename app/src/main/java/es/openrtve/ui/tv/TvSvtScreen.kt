package es.openrtve.ui.tv

import android.content.Context
import androidx.activity.ComponentActivity
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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import es.openrtve.AppContainer
import es.openrtve.R
import es.openrtve.domain.CatalogItem
import es.openrtve.domain.RowLayout
import es.openrtve.playback.PlayerActivity
import es.openrtve.svt.SvtApi
import es.openrtve.svt.SvtBarn
import es.openrtve.svt.SvtBarn.asItem
import es.openrtve.svt.SvtEpisode
import es.openrtve.svt.SvtProgram
import es.openrtve.svt.SvtState
import es.openrtve.svt.SvtStreamResolver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * SVT Barn: los programas infantiles de SVT Play (en sueco) y, al abrir uno, sus episodios.
 * Se comporta como Series: lo último que se ve va el primero, los favoritos y las novedades de SVT
 * van delante, y Atrás hace lo mismo en los dos sitios.
 */
@Composable
fun TvSvtScreen(container: AppContainer, onMessage: (String) -> Unit, focusFirstTick: Int = 0) {
    val context = LocalContext.current
    val api = remember(container) { SvtApi(container.mediaHttpClient) }
    val resolver = remember(container) { SvtStreamResolver(container.mediaHttpClient) }
    val store = container.svtStore
    // Primero la copia guardada (sale al momento); luego la lista actual de SVT la sustituye.
    val saved by produceSvtPrograms(context)
    var fresh by remember { mutableStateOf<List<SvtProgram>?>(null) }
    var svtState by remember { mutableStateOf(SvtState()) }
    LaunchedEffect(Unit) {
        svtState = store.read()
        fresh = withContext(Dispatchers.IO) { runCatching { api.programs() }.getOrNull()?.takeIf { it.isNotEmpty() } }
    }
    // Los programas que SVT trae de nuevo se anclan delante, como las novedades de RTVE.
    LaunchedEffect(fresh) {
        val list = fresh ?: return@LaunchedEffect
        svtState = store.update { it.discover(list.map { p -> p.id }, System.currentTimeMillis()) }
    }
    val programs = remember(saved, fresh, svtState) {
        val bySlug = saved.associateBy { it.slug }
        val base = fresh?.map { p -> p.copy(image = p.image.ifBlank { bySlug[p.slug]?.image.orEmpty() }, episodes = bySlug[p.slug]?.episodes.orEmpty()) } ?: saved
        svtState.order(base)
    }
    var openId by rememberSaveable { mutableStateOf<String?>(null) }
    // Solo vale al volver de un programa a la lista; entrar de nuevo en SVT empieza por el primero.
    var lastProgramId by remember { mutableStateOf<String?>(null) }
    var lastEpisodeId by remember { mutableStateOf<String?>(null) }
    // Pulsar SVT Barn estando dentro de un programa vuelve a la lista.
    var handledTick by remember { mutableIntStateOf(focusFirstTick) }
    LaunchedEffect(focusFirstTick) {
        if (focusFirstTick == handledTick) return@LaunchedEffect
        handledTick = focusFirstTick
        openId = null
        lastProgramId = null
        lastEpisodeId = null
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
            open == null -> ProgramGrid(
                programs = programs,
                pinnedIds = svtState.pinned,
                focusId = lastProgramId,
                focusFirstTick = focusFirstTick,
                onExit = { (context as? ComponentActivity)?.finish() },
                onTogglePin = { id -> svtState = store.update { it.toggled(id) } },
            ) { program ->
                lastProgramId = program.id
                lastEpisodeId = null
                openId = program.id
            }
            else -> {
                val episodes = loadedEpisodes[open.id] ?: open.episodes
                when {
                    episodes.isNotEmpty() -> EpisodeGrid(open.id, episodes, lastEpisodeId, focusFirstTick, onExit = { openId = null }) { episode ->
                        scope.launch {
                            val decision = runCatching { withContext(Dispatchers.IO) { resolver.resolve(episode.videoId, episode.title) } }.getOrNull()
                            if (decision == null) {
                                onMessage("No se pudo cargar este vídeo de SVT")
                            } else {
                                lastEpisodeId = episode.videoId
                                // Lo que se ve se ancla y pasa a ser el primero de la lista.
                                svtState = store.update { it.watched(open.id, System.currentTimeMillis()) }
                                context.startActivity(PlayerActivity.intent(context, decision))
                            }
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
private fun produceSvtPrograms(context: Context) = produceState(emptyList<SvtProgram>()) {
    value = withContext(Dispatchers.IO) {
        runCatching { context.assets.open("svt_barn.json").bufferedReader().use { SvtBarn.parse(it.readText()) } }.getOrDefault(emptyList())
    }
}

@Composable
private fun ProgramGrid(
    programs: List<SvtProgram>,
    pinnedIds: Set<String>,
    focusId: String?,
    focusFirstTick: Int,
    onExit: () -> Unit,
    onTogglePin: suspend (String) -> Unit,
    onOpen: (SvtProgram) -> Unit,
) {
    val items = remember(programs) { programs.map { it.id to it.asItem() } }
    val byId = remember(programs) { programs.associateBy { it.id } }
    SvtCardGrid(
        entries = items, startKey = focusId, focusFirstTick = focusFirstTick, episodic = false,
        pinnedIds = pinnedIds, backToFirstFirst = true, onExit = onExit, onTogglePin = onTogglePin,
    ) { id -> byId[id]?.let(onOpen) }
}

@Composable
private fun EpisodeGrid(
    programId: String,
    episodes: List<SvtEpisode>,
    focusId: String?,
    focusFirstTick: Int,
    onExit: () -> Unit,
    onPlay: (SvtEpisode) -> Unit,
) {
    val items = remember(programId, episodes) { episodes.map { it.videoId to it.asItem() } }
    val byId = remember(programId, episodes) { episodes.associateBy { it.videoId } }
    // Como en una serie: Atrás desde los episodios vuelve directo a la lista de programas.
    SvtCardGrid(
        entries = items, startKey = focusId, focusFirstTick = focusFirstTick, episodic = true,
        pinnedIds = emptySet(), backToFirstFirst = false, onExit = onExit, onTogglePin = null,
    ) { id -> byId[id]?.let(onPlay) }
}

/**
 * Cuadrícula de SVT con el mismo manejo del cursor que Series y Películas: al entrar queda en
 * [startKey] (o el primero); al pulsar la pestaña otra vez, en el primero. Con [backToFirstFirst],
 * Atrás lleva al primero y, estando ya en él, llama a [onExit]; sin él, Atrás llama a [onExit] directo.
 * Con [onTogglePin], la pulsación larga ofrece anclar o desanclar, y el cursor se queda en su posición.
 */
@Composable
private fun SvtCardGrid(
    entries: List<Pair<String, CatalogItem>>,
    startKey: String?,
    focusFirstTick: Int,
    episodic: Boolean,
    pinnedIds: Set<String>,
    backToFirstFirst: Boolean,
    onExit: () -> Unit,
    onTogglePin: (suspend (String) -> Unit)?,
    onClick: (String) -> Unit,
) {
    val context = LocalContext.current
    val state = rememberLazyGridState()
    val scope = rememberCoroutineScope()
    val latest by rememberUpdatedState(entries)
    var focusedIndex by remember { mutableIntStateOf(-1) }
    var menuKey by remember { mutableStateOf<String?>(null) }
    var refocusIndex by remember { mutableIntStateOf(-1) }
    val refocus = remember { FocusRequester() }
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
    // Al volver del reproductor el cursor queda en el episodio (o programa) que se estaba viendo.
    val lifecycle = (context as? ComponentActivity)?.lifecycle
    LaunchedEffect(lifecycle) {
        lifecycle?.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            if (focusedIndex < 0 && startKey != null) focusUntil(startRequester) { focusedIndex >= 0 }
        }
    }
    BackHandler(enabled = menuKey == null) {
        if (!backToFirstFirst || focusedIndex == 0) onExit()
        else scope.launch {
            state.scrollToItem(0)
            focusUntil(firstRequester) { focusedIndex == 0 }
        }
    }
    // Al anclar o desanclar, el cursor se queda en la misma posición de la lista, no en el programa.
    val pinAndStay: (String) -> Unit = { key ->
        val before = latest.indexOfFirst { it.first == key }
        scope.launch {
            onTogglePin?.invoke(key)
            withFrameNanos { }
            withFrameNanos { }
            if (latest.isNotEmpty() && before >= 0) refocusIndex = before.coerceAtMost(latest.size - 1)
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
                if (index == refocusIndex) {
                    LaunchedEffect(key, index) {
                        withFrameNanos { }
                        runCatching { refocus.requestFocus() }
                        refocusIndex = -1
                    }
                }
                val requester = when (index) {
                    start -> startRequester
                    0 -> firstRequester
                    else -> null
                }
                val cardModifier = (if (requester != null) Modifier.focusRequester(requester) else Modifier)
                    .then(if (index == refocusIndex) Modifier.focusRequester(refocus) else Modifier)
                    .onFocusChanged { if (it.hasFocus) focusedIndex = index else if (focusedIndex == index) focusedIndex = -1 }
                    .wrapRowFocus(index, entries.size)
                if (onTogglePin != null) {
                    // Igual que la lista de Series: misma tarjeta, estrella si es favorito, menú con pulsación larga.
                    TvItemCard(
                        item, RowLayout.LANDSCAPE, { onClick(key) }, cardModifier,
                        onLongClick = { menuKey = key },
                        favorite = key in pinnedIds,
                    )
                } else {
                    TvEpisodeCard(item = item, onClick = { onClick(key) }, modifier = cardModifier, episodic = episodic)
                }
            }
        }
    }
    menuKey?.let { key ->
        val item = entries.firstOrNull { it.first == key }?.second
        if (item != null) {
            TvItemMenu(
                title = item.title,
                options = listOf(stringResource(if (key in pinnedIds) R.string.menu_unpin else R.string.menu_pin) to { pinAndStay(key) }),
                onDismiss = { menuKey = null },
            )
        }
    }
}
