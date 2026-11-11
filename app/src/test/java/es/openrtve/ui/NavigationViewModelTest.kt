package es.openrtve.ui

import androidx.lifecycle.SavedStateHandle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NavigationViewModelTest {
    private val viewModel = NavigationViewModel(SavedStateHandle())
    private val state get() = viewModel.state.value

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
    fun `back at the root of another tab returns home before leaving`() {
        viewModel.selectTab(Tab.SEARCH)
        assertTrue(state.canGoBack)
        viewModel.pop()
        assertEquals(Tab.HOME, state.tab)
        assertFalse(state.canGoBack)
    }

    private class TrackedViewModel : androidx.lifecycle.ViewModel() {
        var cleared = false
            private set

        override fun onCleared() {
            cleared = true
        }
    }

}
