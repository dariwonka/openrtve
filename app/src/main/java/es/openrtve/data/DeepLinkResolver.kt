package es.openrtve.data

import es.openrtve.domain.CatalogItem
import es.openrtve.domain.ContentKind
import es.openrtve.domain.DeepLink
import es.openrtve.domain.liveRow
import es.openrtve.domain.parseDeepLink
import es.openrtve.domain.referenceItem

/** Convierte un enlace en el item del catálogo al que apunta, con la red que haga falta. */
class DeepLinkResolver(
    private val repository: CatalogRepository,
) {
    suspend fun resolve(url: String): CatalogItem? {
        val link = parseDeepLink(url) ?: return null
        return when (link) {
            is DeepLink.Video -> repository.loadVideo(link.id).value.item
            is DeepLink.Program -> repository.loadProgram(link.id).value.let { detail ->
                referenceItem(detail.id, ContentKind.PROGRAM, detail.title, detail.imageUrl)
            }
            is DeepLink.Live -> repository.loadModule(liveRow("$LIVES_BASE/${link.assetId}.json")).value.items
                .firstOrNull { it.kind == ContentKind.LIVE }
            is DeepLink.ProgramPermalink -> {
                val marker = "/play/videos/${link.permalink}/"
                repository.search(link.permalink.replace('-', ' ')).programs
                    .firstOrNull { it.webUrl?.endsWith(marker) == true }
            }
            is DeepLink.LivePermalink -> repository.loadModule(liveRow("$LIVES_BASE/agr-directos/1/directos.json")).value.items
                .firstOrNull { it.webUrl?.trimEnd('/')?.endsWith("/directo/${link.permalink}") == true }
        }
    }

    private companion object {
        const val LIVES_BASE = "https://api.rtve.es/api/lives"
    }
}
