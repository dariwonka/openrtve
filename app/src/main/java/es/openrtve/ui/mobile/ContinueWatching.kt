package es.openrtve.ui.mobile

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import es.openrtve.R
import es.openrtve.data.WatchEntry
import es.openrtve.domain.CatalogItem

/**
 * "Seguir viendo", como en las apps de streaming: tocar la tarjeta continúa donde
 * se dejó; el menú de la tarjeta lleva a la ficha o la quita de la fila.
 */
@Composable
internal fun ContinueWatchingRow(
    entries: List<WatchEntry>,
    onResume: (CatalogItem) -> Unit,
    onOpenDetails: (CatalogItem) -> Unit,
    onRemove: (CatalogItem) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier) {
        Text(
            text = stringResource(R.string.continue_watching),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(start = ScreenPadding, end = 4.dp, bottom = 12.dp),
        )
        LazyRow(
            contentPadding = PaddingValues(horizontal = ScreenPadding),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(entries, key = { it.item.id }) { entry ->
                ContinueWatchingCard(
                    entry = entry,
                    onResume = { onResume(entry.item) },
                    onOpenDetails = { onOpenDetails(entry.item) },
                    onRemove = { onRemove(entry.item) },
                    modifier = Modifier.width(ContinueCardWidth).animateItem(),
                )
            }
        }
    }
}

@Composable
private fun ContinueWatchingCard(
    entry: WatchEntry,
    onResume: () -> Unit,
    onOpenDetails: () -> Unit,
    onRemove: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val resume = stringResource(R.string.action_continue)
    Column(modifier) {
        Artwork(
            url = entry.item.imageUrl,
            aspectRatio = 16f / 9f,
            modifier = Modifier
                .fillMaxWidth()
                .clip(CardShape)
                .clickable(onClickLabel = resume, role = Role.Button, onClick = onResume),
        ) {
            ProgressStrip(entry.progress, Modifier.align(Alignment.BottomCenter))
        }
        Row(verticalAlignment = Alignment.Top) {
            Column(
                Modifier
                    .weight(1f)
                    .padding(top = 6.dp)
                    // El texto también continúa: el objetivo táctil es toda la tarjeta menos el menú.
                    .clickable(onClickLabel = resume, onClick = onResume),
            ) {
                Text(
                    text = entry.item.title,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                remainingMinutes(entry)?.let { minutes ->
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = pluralStringResource(R.plurals.continue_remaining_minutes, minutes, minutes),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            ContinueWatchingMenu(onResume, onOpenDetails, onRemove)
        }
    }
}

@Composable
private fun ContinueWatchingMenu(onResume: () -> Unit, onOpenDetails: () -> Unit, onRemove: () -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    // El menú de Material 3 se ancla al botón que lo abre.
    Box {
        IconButton(onClick = { expanded = true }, modifier = Modifier.size(40.dp)) {
            Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.continue_more_options))
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.action_continue)) },
                leadingIcon = { Icon(Icons.Filled.PlayArrow, contentDescription = null) },
                onClick = { expanded = false; onResume() },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.continue_open_details)) },
                leadingIcon = { Icon(Icons.AutoMirrored.Filled.List, contentDescription = null) },
                onClick = { expanded = false; onOpenDetails() },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.continue_remove)) },
                leadingIcon = { Icon(Icons.Filled.Delete, contentDescription = null) },
                onClick = { expanded = false; onRemove() },
            )
        }
    }
}

/** Minutos que faltan, redondeando hacia arriba; nada si no se conoce la duración. */
internal fun remainingMinutes(entry: WatchEntry): Int? =
    (entry.durationMs - entry.positionMs).takeIf { entry.durationMs > 0 && it > 0 }
        ?.let { ((it + 59_999L) / 60_000L).toInt() }

private val ContinueCardWidth = 220.dp
