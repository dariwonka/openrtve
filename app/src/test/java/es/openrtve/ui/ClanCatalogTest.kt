package es.openrtve.ui

import es.openrtve.domain.CatalogModule
import es.openrtve.domain.ContentKind
import es.openrtve.domain.HomeFeed
import es.openrtve.domain.HomeRow
import es.openrtve.domain.RtveTypes
import es.openrtve.domain.RtveUrls
import es.openrtve.testing.FakeCatalogRepository
import es.openrtve.testing.MainDispatcherRule
import es.openrtve.testing.catalogItem
import es.openrtve.testing.loaded
import java.net.UnknownHostException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ClanCatalogTest {
    @get:Rule
    val mainDispatcher = MainDispatcherRule()

    private fun program(id: String, title: String, image: String? = "https://img.rtve.es/p/$id") =
        catalogItem(id, ContentKind.PROGRAM, title).copy(imageUrl = image, subtitle = "Último capítulo")

    private fun episode(id: String, programId: String, series: String) =
        catalogItem(id, title = "Capítulo $id", programId = programId).copy(subtitle = series, imageUrl = "https://img.rtve.es/v/$id")

    private fun movie(id: String, title: String) = catalogItem(id, title = title).copy(mediaTypeId = RtveTypes.COMPLETE, mediaSubtypeId = RtveTypes.MOVIE)

    @Test
    fun `editorial rows become one alphabetical list of unique series`() {
        val catalog = buildClanCatalog(
            listOf(
                LoadedRow("Princesas", listOf(program("3", "Peppa Pig"), program("1", "Érase una vez"))),
                LoadedRow("Para descubrir dinosaurios", listOf(program("2", "Bluey"), program("3", "Peppa Pig"))),
                LoadedRow("Lo último", listOf(program("4", "¡Zas! El show"), program("5", "ana y los robots"))),
            ),
        )

        // Sin tildes, mayúsculas ni signos de apertura en el orden; cada título una vez.
        assertEquals(listOf("ana y los robots", "Bluey", "Érase una vez", "Peppa Pig", "¡Zas! El show"), catalog.series.map { it.title })
        // La tarjeta solo lleva el título de la serie.
        assertTrue(catalog.series.all { it.subtitle == null })
        assertTrue(catalog.movies.isEmpty())
    }

    @Test
    fun `an episode contributes its series, not itself`() {
        val catalog = buildClanCatalog(
            listOf(LoadedRow("Nuevos capítulos", listOf(episode("100", "7", "Pocoyó"), episode("101", "7", "Pocoyó"), episode("102", "8", "Momonsters")))),
        )

        assertEquals(listOf("Momonsters" to "8", "Pocoyó" to "7"), catalog.series.map { it.title to it.id })
        assertTrue(catalog.series.all { it.kind == ContentKind.PROGRAM })
    }

    @Test
    fun `the programme image wins over an episode still, whatever the row order`() {
        val catalog = buildClanCatalog(
            listOf(
                LoadedRow("Nuevos capítulos", listOf(episode("100", "7", "Pocoyó"))),
                LoadedRow("Series", listOf(program("7", "Pocoyó"))),
            ),
        )

        assertEquals("https://img.rtve.es/p/7", catalog.series.single().imageUrl)
    }

    @Test
    fun `films go to their own list and never among the series`() {
        val catalog = buildClanCatalog(
            listOf(
                LoadedRow("Destacados", listOf(movie("50", "Tadeo Jones"), program("2", "Bluey"))),
                // Una fila de películas manda aunque el feed no clasifique sus piezas.
                LoadedRow("Películas para toda la familia", listOf(catalogItem("51", title = "Atrapa la bandera"), program("9", "El Cid, la leyenda"))),
                LoadedRow("Series", listOf(program("9", "El Cid, la leyenda"))),
            ),
        )

        assertEquals(listOf("Atrapa la bandera", "El Cid, la leyenda", "Tadeo Jones"), catalog.movies.map { it.title })
        assertEquals(listOf("Bluey"), catalog.series.map { it.title })
    }

    @Test
    fun `a trailer is not a film`() {
        val trailer = movie("60", "Tráiler").copy(mediaTypeId = RtveTypes.PREVIEW)

        assertTrue(buildClanCatalog(listOf(LoadedRow("Destacados", listOf(trailer)))).movies.isEmpty())
    }

    @Test
    fun `the home catalogue merges every row and survives one that fails`() = runTest(mainDispatcher.dispatcher) {
        val repository = FakeCatalogRepository()
        fun row(id: Int, title: String, url: String?) = HomeRow(id = id, title = title, order = id, moduleType = "Collection", presentation = "ColeccionPoster", contentUrl = url)
        repository.portada = { url, _, _ ->
            if (url == RtveUrls.CLAN_HOME) {
                loaded(HomeFeed(null, "Clan", listOf(row(0, "Princesas", "https://www.rtve.es/a.json"), row(1, "Rota", "https://www.rtve.es/b.json"), row(2, "Cine", "https://www.rtve.es/c.json"))))
            } else {
                loaded(HomeFeed(null, "Otra", emptyList()))
            }
        }
        repository.module = { row, _, _ ->
            when (row.id) {
                0 -> loaded(CatalogModule(row.title, listOf(program("3", "Peppa Pig"), program("2", "Bluey"))))
                2 -> loaded(CatalogModule(row.title, listOf(movie("50", "Tadeo Jones"))))
                else -> throw UnknownHostException()
            }
        }

        val viewModel = ClanCatalogViewModel(repository)
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertFalse(state.isLoading)
        assertNull(state.error)
        assertEquals(listOf("Bluey", "Peppa Pig"), state.catalog.titles(ClanSection.SERIES).map { it.title })
        assertEquals(listOf("Tadeo Jones"), state.catalog.titles(ClanSection.MOVIES).map { it.title })
    }

    @Test
    fun `with nothing loaded the failure is shown and can be retried`() = runTest(mainDispatcher.dispatcher) {
        val repository = FakeCatalogRepository()
        repository.portada = { _, _, _ -> throw UnknownHostException() }
        val viewModel = ClanCatalogViewModel(repository)
        advanceUntilIdle()
        assertEquals(LoadError.Offline, viewModel.uiState.value.error)

        repository.portada = { _, _, _ -> loaded(HomeFeed(null, "Clan", emptyList())) }
        viewModel.retry()
        advanceUntilIdle()
        assertNull(viewModel.uiState.value.error)
        assertFalse(viewModel.uiState.value.isLoading)
    }

    @Test
    fun `the A-Z listing completes the series without overriding what the portada shows`() = runTest(mainDispatcher.dispatcher) {
        val repository = FakeCatalogRepository()
        repository.portada = { _, _, _ ->
            loaded(HomeFeed(null, "Clan", listOf(HomeRow(id = 0, title = "Todo el cine", order = 0, moduleType = "Collection", presentation = "ColeccionPoster", contentUrl = "https://www.rtve.es/c.json"))))
        }
        repository.module = { row, _, _ -> loaded(CatalogModule(row.title, listOf(program("9", "El Cid, la leyenda")))) }
        repository.channelPrograms = { channel, _ ->
            assertEquals(RtveUrls.CLAN_CHANNEL_ID, channel)
            loaded(listOf(program("3", "Peppa Pig"), program("9", "El Cid, la leyenda"), program("2", "Bluey")))
        }

        val viewModel = ClanCatalogViewModel(repository)
        advanceUntilIdle()

        assertEquals(listOf("Bluey", "Peppa Pig"), viewModel.uiState.value.catalog.series.map { it.title })
        assertEquals(listOf("El Cid, la leyenda"), viewModel.uiState.value.catalog.movies.map { it.title })
    }

    @Test
    fun `the saved copy opens the app at once and is replaced when the catalogue changes`() = runTest(mainDispatcher.dispatcher) {
        val file = java.io.File(java.nio.file.Files.createTempDirectory("clan").toFile(), "catalog.json")
        val store = es.openrtve.data.ClanCatalogStore(file, mainDispatcher.dispatcher)
        store.write(es.openrtve.data.StoredCatalog(listOf(program("2", "Bluey").copy(subtitle = null)), listOf(movie("50", "Tadeo Jones"))))

        // Sin red: se enseña la copia y el fallo no se muestra.
        val offline = FakeCatalogRepository()
        offline.portada = { _, _, _ -> throw UnknownHostException() }
        offline.channelPrograms = { _, _ -> throw UnknownHostException() }
        val first = ClanCatalogViewModel(offline, store)
        advanceUntilIdle()
        assertEquals(listOf("Bluey"), first.uiState.value.catalog.series.map { it.title })
        assertEquals(listOf("50"), first.uiState.value.catalog.movies.map { it.id })
        assertTrue(first.uiState.value.catalog.movies.single().let { it.kind == ContentKind.VIDEO && it.playbackId == "50" })
        assertNull(first.uiState.value.error)
        assertFalse(first.uiState.value.isLoading)

        // Con red: llega el catálogo nuevo y queda guardado para el siguiente arranque.
        val online = FakeCatalogRepository()
        online.portada = { _, _, _ -> loaded(HomeFeed(null, "Clan", emptyList())) }
        online.channelPrograms = { _, _ -> loaded(listOf(program("2", "Bluey"), program("3", "Peppa Pig"))) }
        val second = ClanCatalogViewModel(online, store)
        advanceUntilIdle()
        assertEquals(listOf("Bluey", "Peppa Pig"), second.uiState.value.catalog.series.map { it.title })
        assertEquals(listOf("Bluey", "Peppa Pig"), store.read()?.series?.map { it.title })
    }

    @Test
    fun `age portadas add series and the films portada decides what is a film`() = runTest(mainDispatcher.dispatcher) {
        val repository = FakeCatalogRepository()
        fun row(id: Int, title: String, url: String) = HomeRow(id = id, title = title, order = id, moduleType = "Collection", presentation = "ColeccionPoster", contentUrl = url)
        repository.portada = { url, _, _ ->
            loaded(
                HomeFeed(
                    null, url,
                    when (url) {
                        RtveUrls.CLAN_HOME -> listOf(row(0, "Favoritas", "https://www.rtve.es/shared.json"))
                        RtveUrls.CLAN_AGE_PORTADAS.first() -> listOf(row(0, "Para los más peques", "https://www.rtve.es/peques.json"))
                        // Sin "cine" ni "películas" en el título, y con una colección que también está en la principal.
                        RtveUrls.CLAN_MOVIES_PORTADA -> listOf(row(0, "Aventuras", "https://www.rtve.es/aventuras.json"), row(1, "Favoritas", "https://www.rtve.es/shared.json"))
                        else -> emptyList()
                    },
                ),
            )
        }
        repository.module = { row, _, _ ->
            loaded(
                CatalogModule(
                    row.title,
                    when (row.contentUrl) {
                        "https://www.rtve.es/peques.json" -> listOf(program("3", "Peppa Pig"))
                        "https://www.rtve.es/aventuras.json" -> listOf(catalogItem("51", title = "Atrapa la bandera"))
                        else -> listOf(catalogItem("52", title = "Tadeo Jones"))
                    },
                ),
            )
        }

        val viewModel = ClanCatalogViewModel(repository)
        advanceUntilIdle()

        assertEquals(listOf("Peppa Pig"), viewModel.uiState.value.catalog.series.map { it.title })
        assertEquals(listOf("Atrapa la bandera", "Tadeo Jones"), viewModel.uiState.value.catalog.movies.map { it.title })
        // La colección compartida se descarga una sola vez.
        assertEquals(1, repository.calls.count { it.startsWith("module(https://www.rtve.es/shared.json") })
    }

    private fun online(vararg series: es.openrtve.domain.CatalogItem): FakeCatalogRepository {
        val repository = FakeCatalogRepository()
        repository.portada = { _, _, _ -> loaded(HomeFeed(null, "Clan", emptyList())) }
        repository.channelPrograms = { _, _ -> loaded(series.toList()) }
        return repository
    }

    private fun tempStore() = es.openrtve.data.ClanCatalogStore(
        java.io.File(java.nio.file.Files.createTempDirectory("clan").toFile(), "catalog.json"),
        mainDispatcher.dispatcher,
    )

    @Test
    fun `nothing known is ever dropped when a later download brings less`() = runTest(mainDispatcher.dispatcher) {
        val store = tempStore()
        ClanCatalogViewModel(online(program("2", "Bluey"), program("3", "Peppa Pig")), store)
        advanceUntilIdle()
        val later = ClanCatalogViewModel(online(program("3", "Peppa Pig")), store)
        advanceUntilIdle()
        assertEquals(listOf("Bluey", "Peppa Pig"), later.uiState.value.catalog.series.map { it.title })
    }

    @Test
    fun `a removed title stays away until RTVE drops it and offers it again, and leaves the pinned ones`() = runTest(mainDispatcher.dispatcher) {
        val store = tempStore()
        val bluey = program("2", "Bluey")
        val peppa = program("3", "Peppa Pig")
        val first = ClanCatalogViewModel(online(bluey, peppa), store)
        advanceUntilIdle()
        first.togglePin(bluey)
        assertEquals(setOf("2"), first.uiState.value.pinnedIds)
        first.remove(bluey)
        advanceUntilIdle()
        assertEquals(listOf("Peppa Pig"), first.uiState.value.catalog.series.map { it.title })
        assertTrue(first.uiState.value.pinnedIds.isEmpty())

        // Sigue en RTVE: sigue oculto.
        val still = ClanCatalogViewModel(online(bluey, peppa), store)
        advanceUntilIdle()
        assertEquals(listOf("Peppa Pig"), still.uiState.value.catalog.series.map { it.title })

        // RTVE lo retira (descarga completa sin él) y más tarde lo repone: vuelve.
        ClanCatalogViewModel(online(peppa), store)
        advanceUntilIdle()
        val back = ClanCatalogViewModel(online(bluey, peppa), store)
        advanceUntilIdle()
        // Vuelve, pero detrás: las primeras filas ya mostradas no se mueven en esta sesión.
        assertEquals(listOf("Peppa Pig", "Bluey"), back.uiState.value.catalog.series.map { it.title })
    }

    @Test
    fun `a search result that is not in the list is added by pinning and long videos are films`() = runTest(mainDispatcher.dispatcher) {
        val store = tempStore()
        val vm = ClanCatalogViewModel(online(program("1", "Antiguo")), store)
        advanceUntilIdle()
        val found = program("9", "Encontrado")
        assertEquals(false, "9" in vm.uiState.value.knownIds)
        vm.togglePin(found)
        assertEquals(true, "9" in vm.uiState.value.knownIds)
        assertEquals(true, "9" in vm.uiState.value.pinnedIds)
        assertEquals("Encontrado", vm.uiState.value.catalog.series.first().title)
        vm.add(program("8", "Otro"))
        assertEquals(true, "8" in vm.uiState.value.knownIds)
        vm.remove(found)
        assertEquals(false, "9" in vm.uiState.value.knownIds)

        val long = catalogItem("7", title = "Largo").copy(mediaTypeId = RtveTypes.COMPLETE, durationMs = 41 * 60_000L)
        assertEquals("7", catalogEntryOf(long)?.id)
        assertEquals(true, buildClanCatalog(listOf(LoadedRow("", listOf(long)))).movies.any { it.id == "7" })
    }

    @Test
    fun `RTVE pins new titles in front, the user unpins them and they never come back`() = runTest(mainDispatcher.dispatcher) {
        val store = tempStore()
        var now = 1_000_000_000_000L
        val old = program("1", "Antiguo")
        ClanCatalogViewModel(online(old), store, clock = { now })
        advanceUntilIdle()
        now += 1_000
        val zeta = program("5", "Zeta")
        val alfa = program("4", "Alfa")
        val day1 = ClanCatalogViewModel(online(old, alfa, zeta), store, clock = { now })
        advanceUntilIdle()
        // Con copia guardada las primeras filas no cambian durante la sesión: las novedades entran detrás...
        assertEquals(setOf("4", "5"), day1.uiState.value.pinnedIds)
        assertEquals(listOf("Antiguo", "Alfa", "Zeta"), day1.uiState.value.catalog.series.map { it.title })
        // ...y en el siguiente arranque van delante, sin límite.
        val restart1 = ClanCatalogViewModel(online(old, alfa, zeta), store, clock = { now })
        advanceUntilIdle()
        assertEquals(listOf("Alfa", "Zeta", "Antiguo"), restart1.uiState.value.catalog.series.map { it.title })

        // Una novedad más reciente va la primera al arrancar de nuevo.
        now += 1_000
        val nueva = program("6", "Nueva")
        val day2 = ClanCatalogViewModel(online(old, alfa, zeta, nueva), store, clock = { now })
        advanceUntilIdle()
        assertEquals(listOf("Alfa", "Zeta", "Antiguo", "Nueva"), day2.uiState.value.catalog.series.map { it.title })
        val restart2 = ClanCatalogViewModel(online(old, alfa, zeta, nueva), store, clock = { now })
        advanceUntilIdle()
        assertEquals(listOf("Nueva", "Alfa", "Zeta", "Antiguo"), restart2.uiState.value.catalog.series.map { it.title })

        // El usuario desancla Zeta: vuelve a su sitio y RTVE no la ancla otra vez.
        restart2.togglePin(zeta)
        advanceUntilIdle()
        assertEquals(listOf("Nueva", "Alfa", "Antiguo", "Zeta"), restart2.uiState.value.catalog.series.map { it.title })
        val day3 = ClanCatalogViewModel(online(old, alfa, zeta, nueva), store, clock = { now })
        advanceUntilIdle()
        assertEquals(setOf("4", "6"), day3.uiState.value.pinnedIds)

        // RTVE deja de ofrecer Alfa: sigue anclada hasta que el usuario la quite.
        val day4 = ClanCatalogViewModel(online(old, zeta, nueva), store, clock = { now })
        advanceUntilIdle()
        assertTrue("4" in day4.uiState.value.pinnedIds)
    }

    @Test
    fun `films inside a series go loose to the films list, and a series of only films disappears`() = runTest(mainDispatcher.dispatcher) {
        fun video(id: String, programId: String, title: String, minutes: Long) =
            catalogItem(id, title = title, programId = programId).copy(mediaTypeId = RtveTypes.COMPLETE, durationMs = minutes * 60_000L)
        val repository = online(program("1", "Los Cinco"), program("2", "Bluey"))
        repository.programVideos = { programId, _, _, _, _ ->
            loaded(
                es.openrtve.domain.CatalogPage(
                    when (programId) {
                        "1" -> listOf(video("10", "1", "Peligro en el tren nocturno", 86))
                        "2" -> listOf(video("20", "2", "El cartel", 45), video("21", "2", "Camping", 7))
                        else -> emptyList()
                    },
                    page = 1,
                    totalPages = 1,
                ),
            )
        }
        val store = tempStore()
        val viewModel = ClanCatalogViewModel(repository, store)
        advanceUntilIdle()
        val catalog = viewModel.uiState.value.catalog
        assertEquals(listOf("Bluey"), catalog.series.map { it.title })
        assertEquals(listOf("Bluey: El cartel", "Los Cinco: Peligro en el tren nocturno"), catalog.movies.map { it.title })
        // La serie vacía no vuelve en la siguiente descarga.
        val later = ClanCatalogViewModel(repository, store)
        advanceUntilIdle()
        assertEquals(listOf("Bluey"), later.uiState.value.catalog.series.map { it.title })
        assertEquals(2, later.uiState.value.catalog.movies.size)
    }

    @Test
    fun `the splash waits for the catalogue to settle`() = runTest(mainDispatcher.dispatcher) {
        val viewModel = ClanCatalogViewModel(online(program("2", "Bluey")), tempStore())
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value.ready)
    }

    @Test
    fun `a removed title is listed by name and can be restored`() = runTest(mainDispatcher.dispatcher) {
        val store = tempStore()
        val bluey = program("2", "Bluey")
        val viewModel = ClanCatalogViewModel(online(bluey, program("3", "Peppa Pig")), store)
        advanceUntilIdle()
        viewModel.remove(bluey)
        advanceUntilIdle()
        assertEquals(listOf("Bluey"), viewModel.uiState.value.removed.map { it.name })
        viewModel.restore("2")
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value.removed.isEmpty())
        assertEquals(listOf("Bluey", "Peppa Pig"), viewModel.uiState.value.catalog.series.map { it.title })
    }
}
