package es.openrtve.ui

import es.openrtve.R
import es.openrtve.domain.CatalogItem
import es.openrtve.domain.isMovie
import es.openrtve.domain.isContainer
import es.openrtve.domain.isMovieContainer

/** La API llama programas tanto a las series como a los ciclos de piezas independientes. */
enum class ProgramContents(val label: Int, val browseLabel: Int) {
    EPISODES(R.string.tab_episodes, R.string.action_browse_videos),
    MOVIES(R.string.tab_movies, R.string.action_browse_movies),
    VIDEOS(R.string.tab_videos, R.string.action_browse_videos);

    val isCollection: Boolean get() = this != EPISODES
}

val ProgramUiState.contents: ProgramContents
    get() = when {
        detail?.isMovieContainer == true -> ProgramContents.MOVIES
        detail?.isContainer == true -> ProgramContents.VIDEOS
        else -> ProgramContents.EPISODES
    }

/** Las piezas independientes se eligen en su ficha; los capítulos permiten reproducción directa. */
fun ProgramUiState.opensItemDetails(item: CatalogItem): Boolean = contents.isCollection || item.isMovie
