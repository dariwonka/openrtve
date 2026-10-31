package es.openrtve.ui

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import coil3.SingletonImageLoader
import es.openrtve.data.AppSettings
import es.openrtve.data.RawDocumentCache
import es.openrtve.data.Settings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SettingsViewModel(
    private val appSettings: AppSettings,
    private val documentCache: RawDocumentCache,
    private val appContext: Context,
) : ViewModel() {
    val settings: StateFlow<Settings> = appSettings.settings
    private val mutableCacheBytes = MutableStateFlow<Long?>(null)
    val cacheBytes: StateFlow<Long?> = mutableCacheBytes.asStateFlow()

    init {
        refreshCacheSize()
    }

    fun update(transform: (Settings) -> Settings) = appSettings.update(transform)

    /** Borra catálogo e imágenes y avisa con [onCleared] cuando de verdad ha terminado. */
    fun clearCache(onCleared: () -> Unit) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                documentCache.clear()
                SingletonImageLoader.get(appContext).apply {
                    memoryCache?.clear()
                    diskCache?.clear()
                }
            }
            refreshCacheSize()
            onCleared()
        }
    }

    private fun refreshCacheSize() {
        viewModelScope.launch {
            mutableCacheBytes.value = withContext(Dispatchers.IO) {
                documentCache.sizeBytes() + (SingletonImageLoader.get(appContext).diskCache?.size ?: 0L)
            }
        }
    }
}
