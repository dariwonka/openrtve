package es.openrtve.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay

/**
 * Reloj compartido (cada 30 s) para el progreso de los directos y las etiquetas de
 * horario. Se reparte el `State`, no su valor: así el tic solo recompone a quien
 * lo lee ([currentNowMillis]) y no a toda la app desde la raíz.
 */
private val LocalClock = staticCompositionLocalOf<State<Long>> { mutableLongStateOf(System.currentTimeMillis()) }

/** Hora actual del reloj compartido; quien la lee se recompone con cada tic. */
@Composable
@ReadOnlyComposable
fun currentNowMillis(): Long = LocalClock.current.value

/** Da el reloj a [content]. Solo avanza mientras la app se ve: en segundo plano no despierta la CPU. */
@Composable
fun ProvideClock(content: @Composable () -> Unit) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val clock = produceState(System.currentTimeMillis(), lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                value = System.currentTimeMillis()
                delay(TICK_MS)
            }
        }
    }
    CompositionLocalProvider(LocalClock provides clock, content = content)
}

private const val TICK_MS = 30_000L
