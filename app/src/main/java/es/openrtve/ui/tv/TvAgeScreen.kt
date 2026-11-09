package es.openrtve.ui.tv

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import es.openrtve.R
import es.openrtve.data.CatalogRepository
import es.openrtve.ui.rememberExploreScreen

/**
 * Elección de la franja de edad: primer arranque y "Cambiar edad" de Ajustes.
 * Las franjas son las portadas del bloque infantil de la configuración de RTVE;
 * "Todas las edades" (la portada completa de Clan) está siempre, también si la
 * configuración no llega, para que la app no se quede sin entrada.
 */
@Composable
fun TvAgeScreen(
    repository: CatalogRepository,
    /** `url` nula es Clan completo. */
    onChoose: (url: String?, title: String?) -> Unit,
) {
    val screen = rememberExploreScreen(repository, tv = true)
    val ages = screen.state.groups.flatMap { it.categories }.distinctBy { it.contentUrl }

    Column(Modifier.fillMaxSize()) {
        TvScreenTitle(stringResource(R.string.age_title))
        Text(
            text = stringResource(if (screen.state.isLoading) R.string.state_loading else R.string.age_summary),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f),
            modifier = Modifier.padding(horizontal = TvHorizontalMargin),
        )
        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = 220.dp),
            contentPadding = PaddingValues(start = TvHorizontalMargin, end = TvHorizontalMargin, top = 28.dp, bottom = TvVerticalMargin),
            horizontalArrangement = Arrangement.spacedBy(20.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            item(key = "all") {
                TvImageTile(
                    title = stringResource(R.string.age_all),
                    imageUrl = null,
                    onClick = { onChoose(null, null) },
                    modifier = Modifier.initialFocus().fillMaxWidth(),
                )
            }
            items(ages, key = { it.contentUrl }) { age ->
                TvImageTile(
                    title = age.title,
                    imageUrl = age.imageUrl,
                    onClick = { onChoose(age.contentUrl, age.title) },
                    modifier = Modifier.fillMaxWidth(),
                    fallbackImageUrl = age.fallbackImageUrl,
                )
            }
        }
    }
}
