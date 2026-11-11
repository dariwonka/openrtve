package es.openrtve.ui.tv

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import es.openrtve.domain.CatalogItem
import es.openrtve.ui.metaLine

@Composable
internal fun TvEpisodeCard(
    item: CatalogItem,
    onClick: () -> Unit,
    modifier: Modifier,
    progress: Float? = null,
    badge: String? = null,
    episodic: Boolean = true,
) {
    val context = LocalContext.current
    Column(modifier) {
        TvFocusSurface(onClick = onClick, modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f)) {
            TvArtwork(item.imageUrl, Modifier.fillMaxSize())
            progress?.let { TvProgressStrip(it, Modifier.align(Alignment.BottomCenter)) }
        }
        Spacer(Modifier.height(TvCardCaptionGap))
        badge?.let {
            Text(it, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
        }
        Text(item.title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
        item.metaLine(context, episodic)?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

