package es.openrtve.ui.mobile

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.clickable
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import es.openrtve.R
import es.openrtve.domain.CatalogItem
import es.openrtve.ui.ProgramTab

/**
 * Piezas comunes de las fichas (formato y vídeo). Las dos siguen la misma plantilla:
 *
 * 1. **Cabecera**: imagen a sangre con el título encima, línea de datos, botón
 *    grande y sinopsis plegada. Es lo que hace falta para decidir y para darle al
 *    play, así que nunca va en una pestaña.
 * 2. **Pestañas** sólo si hay más de un conjunto de contenido que recorrer
 *    (capítulos, más vídeos, detalles). Material 3: agrupan contenido del mismo
 *    nivel; van fijas cuando caben, y se quedan pegadas arriba al desplazar.
 * 3. **Detalles** es la ficha completa de una obra y la pestaña inicial de un vídeo.
 *    Los programas empiezan por Capítulos y los contenedores por su catálogo.
 *
 * La lista de capítulos pagina sin fin, así que nada puede ir debajo de ella: todo
 * lo que no es la lista va arriba o en una pestaña hermana.
 */

/** Alto de la barra superior de la ficha (botón de volver, título al desplazar, compartir). */
internal val FichaBarHeight = 56.dp

/**
 * Estructura de una ficha: barra superior flotante, cabecera con parallax y, si
 * hay, pestañas que se pegan bajo la barra al desplazar.
 *
 * La barra empieza transparente sobre la imagen y se vuelve opaca, con el título,
 * cuando la cabecera sale de la pantalla: así el botón de volver está siempre y
 * nunca se monta sobre las pestañas.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun FichaLayout(
    title: String,
    listState: LazyListState,
    onBack: () -> Unit,
    onShare: (() -> Unit)?,
    header: @Composable (scrollOffset: () -> Int) -> Unit,
    tabs: (@Composable () -> Unit)?,
    content: LazyListScope.() -> Unit,
) {
    // La ficha va a sangre: la barra flotante y las pestañas pegadas empiezan
    // debajo de la barra de estado.
    val topInset = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val barHeight = FichaBarHeight + topInset
    val barHeightPx = with(LocalDensity.current) { barHeight.roundToPx() }
    var headerHeightPx by remember { mutableIntStateOf(0) }
    // Cuánto se ha desplazado la cabecera; 0 en cuanto deja de ser el primer ítem
    // visible, para que el parallax no arrastre un valor de otro ítem.
    val scrollOffset = { if (listState.firstVisibleItemIndex == 0) listState.firstVisibleItemScrollOffset else 0 }
    // La barra se funde en los últimos 80dp de cabecera: opaca antes de que el
    // contenido pase por debajo.
    val fadePx = with(LocalDensity.current) { 80.dp.roundToPx() }
    val collapsed by remember {
        derivedStateOf {
            listState.firstVisibleItemIndex > 0 ||
                (headerHeightPx > 0 && listState.firstVisibleItemScrollOffset >= headerHeightPx - barHeightPx - fadePx)
        }
    }
    val barAlpha by animateFloatAsState(if (collapsed) 1f else 0f, label = "ficha-bar")

    Box(Modifier.fillMaxSize()) {
        LazyColumn(state = listState, contentPadding = PaddingValues(bottom = 32.dp), modifier = Modifier.fillMaxSize()) {
            item(key = "header") {
                Box(
                    // Con pestañas, la cabecera declara [FichaBarHeight] menos de lo que
                    // dibuja: el hueco que las pestañas reservan para la barra al pegarse
                    // se solapa con el final de la cabecera y en reposo no se nota.
                    Modifier.layout { measurable, constraints ->
                        val placeable = measurable.measure(constraints)
                        if (headerHeightPx != placeable.height) headerHeightPx = placeable.height
                        val overlap = if (tabs != null) barHeightPx else 0
                        layout(placeable.width, (placeable.height - overlap).coerceAtLeast(0)) { placeable.place(0, 0) }
                    },
                ) { header(scrollOffset) }
            }
            if (tabs != null) {
                stickyHeader(key = "tabs") {
                    Column {
                        Spacer(Modifier.height(barHeight))
                        Box(Modifier.background(MaterialTheme.colorScheme.background)) { tabs() }
                    }
                }
            }
            content()
        }
        FichaTopBar(title = title, alpha = barAlpha, onBack = onBack, onShare = onShare)
    }
}

/** Ficha sin cabecera que enseñar (cargando, o fallida): la barra opaca con el título y, debajo, [content]. */
@Composable
internal fun FichaPanel(title: String, onBack: () -> Unit, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxSize()) {
        FichaTopBar(title = title, alpha = 1f, onBack = onBack, onShare = null)
        content()
    }
}

/** Barra flotante de la ficha: transparente sobre la imagen, opaca y con título al desplazar. */
@Composable
private fun FichaTopBar(title: String, alpha: Float, onBack: () -> Unit, onShare: (() -> Unit)?) {
    val background = MaterialTheme.colorScheme.background
    // Sobre la imagen, los botones llevan un velo redondo para que se vean sobre
    // cualquier foto; al volverse opaca la barra, el velo desaparece.
    val scrim = Color.Black.copy(alpha = 0.35f * (1f - alpha))
    val tint = androidx.compose.ui.graphics.lerp(Color.White, MaterialTheme.colorScheme.onBackground, alpha)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .background(background.copy(alpha = alpha))
            .statusBarsPadding()
            .height(FichaBarHeight)
            .padding(horizontal = 4.dp),
    ) {
        IconButton(onClick = onBack, modifier = Modifier.background(scrim, CircleShape)) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back), tint = tint)
        }
        // Sólo existe cuando se ve: invisible seguía en el árbol de accesibilidad y
        // el título de la ficha se anunciaba dos veces.
        if (alpha > 0f) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 4.dp)
                    .graphicsLayer { this.alpha = alpha },
            )
        } else {
            Spacer(Modifier.weight(1f))
        }
        if (onShare != null) {
            IconButton(onClick = onShare, modifier = Modifier.background(scrim, CircleShape)) {
                Icon(Icons.Filled.Share, contentDescription = stringResource(R.string.action_share), tint = tint)
            }
        }
    }
}

/**
 * Cabecera de ficha: imagen 16:9 a sangre con parallax y el título encima; debajo,
 * subtítulo, línea de datos, reclamo, aviso, acciones, progreso y sinopsis plegada.
 * Todo lo que falte se omite sin dejar hueco.
 */
@Composable
internal fun FichaHeader(
    backdropUrl: String?,
    title: String,
    scrollOffset: () -> Int,
    subtitle: String? = null,
    meta: String? = null,
    claim: String? = null,
    notice: String? = null,
    synopsis: AnnotatedString? = null,
    progress: Float? = null,
    actions: (@Composable () -> Unit)? = null,
) {
    val background = MaterialTheme.colorScheme.background
    Column {
        if (backdropUrl != null) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .aspectRatio(16f / 9f)
                    .clipToBounds(),
            ) {
                AsyncImage(
                    model = backdropUrl,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    alignment = Alignment.TopCenter,
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer { translationY = scrollOffset() / 2f }
                        .background(MaterialTheme.colorScheme.surface),
                )
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(Brush.verticalGradient(0f to Color.Black.copy(alpha = 0.15f), 1f to background)),
                )
                FichaTitle(title, Modifier.align(Alignment.BottomStart))
            }
        } else {
            // Sin imagen no se reserva un 16:9 en negro: sólo el sitio de la barra.
            Spacer(Modifier.height(FichaBarHeight + WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + 8.dp))
            FichaTitle(title)
        }
        Column(Modifier.padding(horizontal = ScreenPadding)) {
            subtitle?.takeIf { it.isNotBlank() }?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            meta?.takeIf { it.isNotBlank() }?.let {
                Spacer(Modifier.height(4.dp))
                Text(text = it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            claim?.takeIf { it.isNotBlank() }?.let {
                Spacer(Modifier.height(8.dp))
                Text(text = it, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            }
            notice?.takeIf { it.isNotBlank() }?.let {
                Spacer(Modifier.height(8.dp))
                Text(text = it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (actions != null) {
                Spacer(Modifier.height(12.dp))
                actions()
            }
            progress?.let {
                Spacer(Modifier.height(8.dp))
                ProgressStrip(it)
            }
            synopsis?.takeIf { it.isNotBlank() }?.let {
                Spacer(Modifier.height(12.dp))
                ExpandableText(it)
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun FichaTitle(title: String, modifier: Modifier = Modifier) {
    Text(
        text = title,
        style = MaterialTheme.typography.headlineMedium,
        fontWeight = FontWeight.Bold,
        maxLines = 3,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier.padding(horizontal = ScreenPadding),
    )
}

/** Acción secundaria junto al botón de reproducir, como icono tonal. */
internal data class SecondaryAction(val icon: ImageVector, val label: String, val onClick: () -> Unit)

/**
 * Botón grande de reproducir, a todo el ancho, con las acciones secundarias
 * ("desde el inicio") como iconos tonales a su derecha: así el rótulo largo del
 * principal ("Continuar · T2 E4") no aplasta a los de al lado.
 */
@Composable
internal fun PlayActions(label: String, onPlay: () -> Unit, secondary: List<SecondaryAction> = emptyList()) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        Button(onClick = onPlay, modifier = Modifier.weight(1f)) {
            Icon(Icons.Filled.PlayArrow, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        secondary.forEach { action ->
            FilledTonalIconButton(onClick = action.onClick) {
                Icon(action.icon, contentDescription = action.label)
            }
        }
    }
}

/**
 * Pestañas fijas de la ficha: son tres como mucho y caben a ancho igual.
 *
 * Material 3: la activa en `primary`, las demás en `onSurfaceVariant`. `Tab` pinta
 * por defecto las no seleccionadas del mismo color que la seleccionada, con lo que
 * salían las tres del color de acento y sólo las distinguía el indicador. Y sobre
 * el fondo de la ficha, no sobre `surface`: la fila no es una franja aparte.
 */
@Composable
internal fun FichaTabs(
    tabs: List<ProgramTab>,
    current: ProgramTab?,
    onSelect: (ProgramTab) -> Unit,
    contentsLabel: Int = R.string.tab_episodes,
) {
    PrimaryTabRow(
        selectedTabIndex = tabs.indexOf(current).coerceAtLeast(0),
        containerColor = MaterialTheme.colorScheme.background,
    ) {
        tabs.forEach { tab ->
            Tab(
                selected = tab == current,
                onClick = { onSelect(tab) },
                selectedContentColor = MaterialTheme.colorScheme.primary,
                unselectedContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                text = { Text(text = tab.label(contentsLabel), maxLines = 1, overflow = TextOverflow.Ellipsis) },
            )
        }
    }
}

/** La pestaña elegida, recordada por identidad aunque otras aparezcan al terminar de cargar. */
@Composable
internal fun rememberSelectedTab(tabs: List<ProgramTab>): Pair<ProgramTab?, (ProgramTab) -> Unit> {
    var selectedName by rememberSaveable { mutableStateOf<String?>(null) }
    val current = selectedName
        ?.let { name -> tabs.firstOrNull { it.name == name } }
        ?: tabs.firstOrNull()
    return current to { tab -> selectedName = tab.name }
}

/**
 * "Similares" como rejilla de pósters dentro de la lista de la ficha: filas de
 * [columns] tarjetas a ancho igual, para no anidar una rejilla perezosa en otra lista.
 */
internal fun LazyListScope.relatedGrid(items: List<CatalogItem>, columns: Int, onOpen: (CatalogItem) -> Unit) {
    items(items.chunked(columns), key = { row -> "related-${row.first().id}" }) { row ->
        Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.padding(horizontal = ScreenPadding, vertical = 8.dp),
        ) {
            row.forEach { item -> PosterCard(item, { onOpen(item) }, Modifier.weight(1f)) }
            // La última fila conserva el ancho de columna aunque venga incompleta.
            repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
        }
    }
}

/** Columnas de la rejilla de similares: pósters de unos 120 dp, tres como mínimo. */
@Composable
internal fun relatedColumns(): Int {
    val widthDp = with(LocalDensity.current) { LocalWindowInfo.current.containerSize.width.toDp() }
    return ((widthDp - ScreenPadding * 2) / 120.dp).toInt().coerceAtLeast(3)
}

@Composable
internal fun ProgramTab.label(contentsLabel: Int = R.string.tab_episodes): String = stringResource(
    when (this) {
        ProgramTab.EPISODES -> contentsLabel
        ProgramTab.MORE_VIDEOS -> R.string.tab_more_videos
        ProgramTab.RELATED -> R.string.tab_related
        ProgramTab.DETAILS -> R.string.tab_details
    },
)

/** Sinopsis plegada a tres líneas, con "más" alineado con el párrafo. */
@Composable
internal fun ExpandableText(text: String) = ExpandableText(AnnotatedString(text))

@Composable
internal fun ExpandableText(text: AnnotatedString) {
    var expanded by rememberSaveable(text.text) { mutableStateOf(false) }
    var hasOverflow by remember(text) { mutableStateOf(false) }
    Column {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = if (expanded) Int.MAX_VALUE else 3,
            overflow = TextOverflow.Ellipsis,
            onTextLayout = { result -> if (!expanded) hasOverflow = result.hasVisualOverflow },
        )
        // Texto pulsable y no TextButton: el botón centra su texto en un ancho mínimo
        // y "más" quedaba sangrado respecto al párrafo. El alto de 48 dp se mantiene.
        if (hasOverflow || expanded) {
            Text(
                text = stringResource(if (expanded) R.string.action_less else R.string.action_more),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .heightIn(min = 48.dp)
                    .clickable(role = Role.Button) { expanded = !expanded }
                    .wrapContentHeight(Alignment.CenterVertically),
            )
        }
    }
}
