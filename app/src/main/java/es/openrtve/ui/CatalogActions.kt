package es.openrtve.ui

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import es.openrtve.AppContainer
import es.openrtve.domain.BlockReason
import es.openrtve.domain.CatalogItem
import es.openrtve.domain.ContentKind
import es.openrtve.domain.ExploreCategory
import es.openrtve.domain.HomeLink
import es.openrtve.domain.HomeRow
import es.openrtve.domain.PlaybackDecision
import es.openrtve.domain.referenceItem
import es.openrtve.playback.PlayerActivity

/**
 * Lo que la UI puede hacer con el catálogo, común a móvil y TV: abrir fichas,
 * reproducir (o explicar por qué no se puede) y mostrar mensajes.
 */
class CatalogActions(
    private val container: AppContainer,
    private val navigation: NavigationViewModel,
    private val context: Context,
) {
    fun play(item: CatalogItem, restart: Boolean = false) {
        when (val decision = container.playbackResolver.resolve(item)) {
            is PlaybackDecision.Ready -> {
                container.watchHistory.register(item)
                context.startActivity(PlayerActivity.intent(context, decision, restart))
            }
            is PlaybackDecision.Blocked -> {
                val schedule = item.live?.scheduleLabel(context, System.currentTimeMillis())
                if (decision.reason == BlockReason.NOT_STARTED_YET && schedule != null) {
                    navigation.show(UiMessage.StartsAt(schedule))
                } else {
                    navigation.show(UiMessage.Blocked(decision.reason))
                }
            }
        }
    }

    val playItem: (CatalogItem) -> Unit = { play(it) }

    /** Quita un item de "Seguir viendo" (la portada lo refleja al momento) y ofrece deshacerlo. */
    val removeFromHistory: (CatalogItem) -> Unit = { item ->
        container.watchHistory.remove(item.id)?.let { navigation.show(UiMessage.RemovedFromHistory(it)) }
    }

    fun undoRemoveFromHistory(message: UiMessage.RemovedFromHistory) = container.watchHistory.restore(message.entry)

    /** Programas y vídeos abren su ficha; los directos se reproducen al momento. */
    val openItem: (CatalogItem) -> Unit = { item ->
        when (item.kind) {
            ContentKind.PROGRAM -> navigation.push(Destination.Program(item))
            ContentKind.VIDEO -> navigation.push(Destination.Video(item))
            else -> play(item)
        }
    }

    val openRow: (HomeRow, String) -> Unit = { row, title -> navigation.push(Destination.Module(row, title)) }

    val openLink: (HomeLink) -> Unit = { link -> link.destination()?.let(navigation::push) }

    val openCategory: (ExploreCategory) -> Unit = { navigation.push(it.destination()) }

    val openProgram: (programId: String, title: String) -> Unit = { id, title ->
        navigation.push(Destination.Program(referenceItem(id, ContentKind.PROGRAM, title)))
    }

    val openSettings: () -> Unit = { navigation.openGlobal(GlobalDestination.Settings) }

    val showMessage: (String) -> Unit = { navigation.show(UiMessage.Text(it)) }

    /** Enlace recibido (VIEW o Compartir): construye Inicio -> ficha, como una navegación manual. */
    suspend fun openIncomingLink(url: String) {
        // Resolver un permalink puede costar una búsqueda: se avisa en vez de no hacer nada.
        navigation.show(UiMessage.OpeningLink)
        val item = try {
            container.deepLinkResolver.resolve(url)
        } catch (error: kotlinx.coroutines.CancellationException) {
            navigation.dismissMessage(UiMessage.OpeningLink)
            throw error
        } catch (error: Exception) {
            // Sin red el enlace no es "inexistente": se dice qué pasó.
            navigation.show(UiMessage.Error(error.toLoadError()))
            return
        }
        if (item == null) {
            navigation.show(UiMessage.LinkNotFound)
            return
        }
        navigation.dismissMessage(UiMessage.OpeningLink)
        when (item.kind) {
            ContentKind.PROGRAM -> navigation.openDeepLink(Destination.Program(item))
            ContentKind.VIDEO -> navigation.openDeepLink(Destination.Video(item))
            else -> play(item)
        }
    }
}

@Composable
fun rememberCatalogActions(container: AppContainer, navigation: NavigationViewModel): CatalogActions {
    val context = LocalContext.current
    return remember(container, navigation, context) { CatalogActions(container, navigation, context) }
}
