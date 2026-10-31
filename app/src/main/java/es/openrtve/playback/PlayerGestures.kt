package es.openrtve.playback

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioManager
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.view.Window
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.media3.ui.PlayerView
import kotlin.math.abs
import kotlin.math.roundToInt

/** Lo que el gesto en curso quiere mostrar en pantalla. */
sealed interface GestureFeedback {
    data class Brightness(val percent: Int) : GestureFeedback
    data class Volume(val percent: Int) : GestureFeedback
}

/**
 * Gestos del reproductor, como en Findroid: un toque muestra u oculta los
 * controles, doble toque salta atrás o adelante según el lado, y deslizar en
 * vertical ajusta el brillo (mitad izquierda) o el volumen (mitad derecha).
 */
@OptIn(UnstableApi::class)
class PlayerGestures(
    private val playerView: PlayerView,
    private val window: Window,
    private val isLocked: () -> Boolean,
    private val onLockedTap: () -> Unit,
    private val onFeedback: (GestureFeedback?) -> Unit,
) : View.OnTouchListener {
    private val audioManager = playerView.context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
    private val touchSlop = android.view.ViewConfiguration.get(playerView.context).scaledTouchSlop

    private var swipe: Swipe? = null
    private var swipeStartValue = 0f

    private enum class Swipe { BRIGHTNESS, VOLUME }

    private val detector = GestureDetector(
        playerView.context,
        object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean = true

            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                if (isLocked()) {
                    onLockedTap()
                } else if (playerView.isControllerFullyVisible) {
                    playerView.hideController()
                } else {
                    playerView.showController()
                }
                return true
            }

            override fun onDoubleTap(e: MotionEvent): Boolean {
                if (isLocked()) return true
                val player = playerView.player ?: return true
                val third = playerView.width / 3f
                when {
                    e.x < third -> player.seekBack()
                    e.x > third * 2 -> player.seekForward()
                    else -> if (player.isPlaying) player.pause() else player.play()
                }
                return true
            }

            override fun onScroll(e1: MotionEvent?, e2: MotionEvent, distanceX: Float, distanceY: Float): Boolean {
                if (isLocked() || e1 == null) return false
                if (swipe == null) {
                    // Solo gestos claramente verticales, y no desde el borde superior/inferior:
                    // ahí empiezan la cortina de notificaciones y el gesto de inicio del sistema.
                    val dx = abs(e2.x - e1.x)
                    val dy = abs(e2.y - e1.y)
                    if (dy < touchSlop * 2 || dx > dy || startsInSystemGestureArea(e1)) return false
                    swipe = if (e1.x < playerView.width / 2f) Swipe.BRIGHTNESS else Swipe.VOLUME
                    swipeStartValue = when (swipe) {
                        Swipe.BRIGHTNESS -> currentBrightness()
                        else -> audioManager.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat() / maxVolume
                    }
                }
                val travel = (e1.y - e2.y) / (playerView.height * 0.7f)
                val value = (swipeStartValue + travel).coerceIn(0f, 1f)
                when (swipe) {
                    Swipe.BRIGHTNESS -> {
                        window.attributes = window.attributes.apply { screenBrightness = value }
                        onFeedback(GestureFeedback.Brightness((value * 100).roundToInt()))
                    }
                    Swipe.VOLUME -> {
                        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, (value * maxVolume).roundToInt(), 0)
                        onFeedback(GestureFeedback.Volume((value * 100).roundToInt()))
                    }
                    null -> Unit
                }
                return true
            }
        },
    )

    // `PlayerView.performClick()` alterna los controles por su cuenta y pisaría el toque simple de aquí;
    // los botones del controlador siguen siendo accesibles con TalkBack.
    @SuppressLint("ClickableViewAccessibility")
    override fun onTouch(view: View, event: MotionEvent): Boolean {
        detector.onTouchEvent(event)
        if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
            if (swipe != null) onFeedback(null)
            swipe = null
        }
        return true
    }

    private fun startsInSystemGestureArea(event: MotionEvent): Boolean {
        val insets = ViewCompat.getRootWindowInsets(playerView)?.getInsets(WindowInsetsCompat.Type.systemGestures())
        val edge = edgePx
        val top = maxOf(insets?.top ?: 0, edge)
        val bottom = maxOf(insets?.bottom ?: 0, edge)
        return event.y < top || event.y > playerView.height - bottom
    }

    /** Margen mínimo si el sistema no declara zonas de gesto (navegación por botones). */
    private val edgePx = (EDGE_DP * playerView.resources.displayMetrics.density).toInt()

    private fun currentBrightness(): Float {
        val value = window.attributes.screenBrightness
        if (value in 0f..1f) return value
        // Sin override, el brillo actual es el del sistema.
        val system = runCatching {
            android.provider.Settings.System.getInt(playerView.context.contentResolver, android.provider.Settings.System.SCREEN_BRIGHTNESS)
        }.getOrDefault(128)
        return system / 255f
    }

}

private const val EDGE_DP = 32
