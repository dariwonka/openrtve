package es.openrtve.ui

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

/**
 * Cuenta las tareas que piden información nueva a los servidores (catálogos, listas de capítulos,
 * novedades). La barra superior enseña un indicador mientras haya alguna; las carátulas y la
 * reproducción no cuentan.
 */
object SyncActivity {
    @PublishedApi
    internal val count = MutableStateFlow(0)

    /** Número de tareas de actualización en curso. */
    val busy: StateFlow<Int> get() = count

    inline fun <T> track(block: () -> T): T {
        count.update { it + 1 }
        try {
            return block()
        } finally {
            count.update { it - 1 }
        }
    }
}
