package es.openrtve.ui

import androidx.lifecycle.SavedStateHandle
import es.openrtve.domain.ContentKind
import es.openrtve.domain.HomeLink
import es.openrtve.domain.HomeRow
import es.openrtve.domain.LinkKind
import es.openrtve.domain.RowLayout
import es.openrtve.testing.catalogItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NavigationViewModelTest {
    private val viewModel = NavigationViewModel(SavedStateHandle())
    private val state get() = viewModel.state.value

    @Test
    fun `each tab keeps its own stack`() {
        val program = Destination.Program(catalogItem("p"))
        viewModel.push(program)
        viewModel.selectTab(Tab.SEARCH)
        val video = Destination.Video(catalogItem("v"))
        viewModel.push(video)

        assertEquals(listOf(video), state.stack)
        viewModel.selectTab(Tab.HOME)
        assertEquals(listOf(program), state.stack)
        assertEquals(program, state.current)
        assertTrue(state.canGoBack)
    }

    @Test
    fun `only reselection requests a root action and each section has its own counter`() {
        assertEquals(0, state.rootRequests.getValue(Tab.HOME))
        viewModel.selectTab(Tab.HOME)
        assertEquals(1, state.rootRequests.getValue(Tab.HOME))
        viewModel.selectTab(Tab.SEARCH)
        assertEquals(0, state.rootRequests.getValue(Tab.SEARCH))
        viewModel.selectTab(Tab.SEARCH)
        assertEquals(1, state.rootRequests.getValue(Tab.SEARCH))
        viewModel.selectTab(Tab.SEARCH)
        assertEquals(2, state.rootRequests.getValue(Tab.SEARCH))
        viewModel.selectTab(Tab.HOME)
        assertEquals(1, state.rootRequests.getValue(Tab.HOME))
    }

    @Test
    fun `root requests survive recreation so a consumed action is not replayed on return`() {
        val saved = SavedStateHandle()
        val original = NavigationViewModel(saved)
        original.selectTab(Tab.HOME)
        val restored = NavigationViewModel(saved)
        assertEquals(1, restored.state.value.rootRequests.getValue(Tab.HOME))
        restored.selectTab(Tab.SEARCH)
        restored.selectTab(Tab.HOME)
        assertEquals(1, restored.state.value.rootRequests.getValue(Tab.HOME))
        restored.selectTab(Tab.HOME)
        assertEquals(2, restored.state.value.rootRequests.getValue(Tab.HOME))
    }

    @Test
    fun `pop removes the top and is harmless on an empty stack`() {
        val program = Destination.Program(catalogItem("p"))
        viewModel.push(program)
        viewModel.push(Destination.Video(catalogItem("v")))
        viewModel.pop()
        assertEquals(listOf(program), state.stack)
        viewModel.pop()
        viewModel.pop()
        assertTrue(state.stack.isEmpty())
        assertFalse(state.canGoBack)
        assertNull(state.current)
    }

    @Test
    fun `entry keys are stable per position and the visible one follows the stack`() {
        assertEquals("HOME/root", state.currentKey)
        viewModel.push(Destination.Program(catalogItem("p")))
        viewModel.push(Destination.Video(catalogItem("v")))
        assertEquals("HOME/1", state.currentKey)
        assertEquals(setOf("HOME/root", "HOME/0", "HOME/1", "SEARCH/root", "EXPLORE/root"), state.liveKeys)
        viewModel.pop()
        assertEquals("HOME/0", state.currentKey)
    }

    @Test
    fun `back at the root of another tab returns home before leaving`() {
        viewModel.selectTab(Tab.SEARCH)
        assertTrue(state.canGoBack)
        viewModel.pop()
        assertEquals(Tab.HOME, state.tab)
        assertFalse(state.canGoBack)
    }

    @Test
    fun `leaving an entry clears its ViewModelStore and the root stores survive`() {
        viewModel.push(Destination.Program(catalogItem("p")))
        val entryStore = viewModel.storeOwner(state.currentKey).viewModelStore
        val rootStore = viewModel.storeOwner("HOME/root").viewModelStore
        val entryModel = TrackedViewModel().also { entryStore.put("m", it) }
        val rootModel = TrackedViewModel().also { rootStore.put("m", it) }

        viewModel.pop()
        assertTrue("el ViewModel de la ficha se libera al salir", entryModel.cleared)
        assertFalse(rootModel.cleared)
        assertTrue("una entrada nueva en la misma posición empieza de cero", viewModel.storeOwner("HOME/0").viewModelStore !== entryStore)

        viewModel.push(Destination.Program(catalogItem("p2")))
        viewModel.push(Destination.Video(catalogItem("v2")))
        val nested = TrackedViewModel().also { viewModel.storeOwner("HOME/1").viewModelStore.put("m", it) }
        viewModel.selectTab(Tab.HOME)
        assertTrue("volver a la raíz libera toda la pila", nested.cleared)
        assertFalse(rootModel.cleared)
    }

    @Test
    fun `back from global settings reveals the exact underlying destination`() {
        val video = Destination.Video(catalogItem("v"))
        viewModel.push(video)
        viewModel.openGlobal(GlobalDestination.Settings)
        val settingsStore = viewModel.storeOwner(state.currentKey).viewModelStore
        val settingsModel = TrackedViewModel().also { settingsStore.put("m", it) }

        viewModel.pop()

        assertEquals(video, state.current)
        assertNull(state.global)
        assertTrue("el estado global se libera al cerrarlo", settingsModel.cleared)
    }

    @Test
    fun `module and media routes retain the identifiers needed to reload`() {
        val savedState = SavedStateHandle()
        val original = NavigationViewModel(savedState)
        val module = Destination.Module(
            row = HomeRow(
                id = 7,
                title = "Colección",
                order = 2,
                moduleType = "Collection",
                presentation = "ColeccionPoster",
                contentUrl = "https://api.rtve.es/api/collection/7.json",
                layout = RowLayout.POSTER,
                links = listOf(HomeLink("RTVE", null, "https://www.rtve.es/play/", LinkKind.PORTADA)),
            ),
            title = "Ver todo",
        )
        val video = Destination.Video(
            catalogItem("video", kind = ContentKind.VIDEO, programId = "programa")
                .copy(imageUrl = "https://img.rtve.es/video.jpg"),
        )

        original.push(module)
        original.push(video)

        assertEquals(listOf(module, video), NavigationViewModel(savedState).state.value.stack)
    }

    private class TrackedViewModel : androidx.lifecycle.ViewModel() {
        var cleared = false
            private set

        override fun onCleared() {
            cleared = true
        }
    }

    @Test
    fun `home links open a collection, a portada or a detail by its api id`() {
        val collection = HomeLink("Maratón", null, "https://api.rtve.es/api/collection/1900.json", LinkKind.COLLECTION).destination()
        assertTrue(collection is Destination.Module)
        assertEquals("https://api.rtve.es/api/collection/1900.json", (collection as Destination.Module).row.contentUrl)

        val portada = HomeLink("Playz", null, "https://api.rtve.es/play/playz/index_apps.json", LinkKind.PORTADA).destination()
        assertEquals(Destination.Portada("https://api.rtve.es/play/playz/index_apps.json", "Playz"), portada)

        val program = HomeLink("Programa", null, "https://api.rtve.es/api/programas/1234.json", LinkKind.PROGRAM).destination()
        assertEquals("1234", (program as Destination.Program).item.programId)
        assertEquals(ContentKind.PROGRAM, program.item.kind)

        val video = HomeLink("Vídeo", null, "https://api.rtve.es/api/videos/99.json", LinkKind.VIDEO).destination()
        assertEquals(ContentKind.VIDEO, (video as Destination.Video).item.kind)
        assertEquals("99", video.item.playbackId)

        // Sin id reconocible no hay destino: el enlace no lleva a ninguna parte en vez de abrir una ficha vacía.
        assertNull(HomeLink("Raro", null, "https://www.rtve.es/play/algo/", LinkKind.VIDEO).destination())
    }
}
