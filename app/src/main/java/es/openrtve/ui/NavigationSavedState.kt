package es.openrtve.ui

import androidx.lifecycle.SavedStateHandle
import es.openrtve.domain.CatalogItem
import es.openrtve.domain.ContentKind
import es.openrtve.domain.referenceItem
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

private const val SAVED_TAB = "navigation.tab"
private const val SAVED_GLOBAL = "navigation.global"
private const val SAVED_GENERATION = "navigation.generation"
private const val SETTINGS_DESTINATION = "settings"
private const val SVT_DESTINATION = "svt"
private val navigationJson = Json { ignoreUnknownKeys = true }

private fun savedStack(tab: Tab) = "navigation.stack.${tab.name.lowercase()}"
private fun savedRootRequest(tab: Tab) = "navigation.rootRequest.${tab.name.lowercase()}"

/**
 * Guarda solo la ruta necesaria para reconstruir cada pantalla. El contenido
 * completo vuelve a cargarse desde el repositorio y no ocupa el Bundle de
 * estado guardado.
 */
private fun Destination.encodeRoute(): String = buildJsonObject {
    when (this@encodeRoute) {
        is Destination.Program -> {
            put("type", "program")
            putItemRoute(item)
        }
    }
}.toString()

private fun JsonObjectBuilder.putItemRoute(item: CatalogItem) {
    put("id", item.id)
    put("playbackId", item.playbackId)
    put("programId", item.programId)
    put("title", item.title)
    put("imageUrl", item.imageUrl)
    put("kind", item.kind.name)
}

private fun decodeRoute(raw: String): Destination? = runCatching {
    val route = navigationJson.parseToJsonElement(raw).jsonObject
    when (route.text("type")) {
        "program" -> {
            val kind = route.text("kind")?.let { ContentKind.valueOf(it) } ?: ContentKind.UNKNOWN
            val item = referenceItem(
                id = route.requiredText("id"),
                kind = kind,
                title = route.requiredText("title"),
                imageUrl = route.text("imageUrl"),
            ).copy(
                playbackId = route.text("playbackId"),
                programId = route.text("programId"),
            )
            Destination.Program(item)
        }
        else -> null
    }
}.getOrNull()

private fun JsonObject.text(key: String): String? = this[key]?.jsonPrimitive?.contentOrNull

private fun JsonObject.requiredText(key: String): String = requireNotNull(text(key)) { "Missing $key" }

private fun JsonObject.number(key: String): Int? = this[key]?.jsonPrimitive?.intOrNull

internal fun SavedStateHandle.restoreNavigationState(): NavigationState {
    val tab = get<String>(SAVED_TAB)?.let { runCatching { Tab.valueOf(it) }.getOrNull() } ?: Tab.HOME
    val stacks = Tab.entries.associateWith { currentTab ->
        get<ArrayList<String>>(savedStack(currentTab)).orEmpty().mapNotNull(::decodeRoute)
    }
    val global = when (get<String>(SAVED_GLOBAL)) {
        SETTINGS_DESTINATION -> GlobalDestination.Settings
        SVT_DESTINATION -> GlobalDestination.Svt
        else -> null
    }
    return NavigationState(
        tab = tab, stacks = stacks, global = global, generation = get<Int>(SAVED_GENERATION) ?: 0,
        rootRequests = Tab.entries.associateWith { get<Int>(savedRootRequest(it)) ?: 0 },
    )
}

internal fun SavedStateHandle.saveNavigationState(state: NavigationState) {
    this[SAVED_TAB] = state.tab.name
    this[SAVED_GENERATION] = state.generation
    Tab.entries.forEach { tab ->
        this[savedStack(tab)] = ArrayList(state.stacks.getValue(tab).map(Destination::encodeRoute))
        this[savedRootRequest(tab)] = state.rootRequests.getValue(tab)
    }
    if (state.global == GlobalDestination.Settings) {
        this[SAVED_GLOBAL] = SETTINGS_DESTINATION
    } else if (state.global == GlobalDestination.Svt) {
        this[SAVED_GLOBAL] = SVT_DESTINATION
    } else {
        remove<String>(SAVED_GLOBAL)
    }
}
