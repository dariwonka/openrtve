package es.openrtve.playback

import android.app.PictureInPictureParams
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.util.Rational
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.annotation.OptIn
import androidx.annotation.RequiresApi
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.HttpDataSource
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.media3.common.Tracks
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.DefaultTimeBar
import androidx.media3.ui.TimeBar
import es.openrtve.domain.CatalogItem
import es.openrtve.domain.canHaveNextEpisode
import es.openrtve.domain.PreviewSprite
import androidx.media3.ui.PlayerView
import android.content.res.Configuration
import android.view.LayoutInflater
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.TextView
import androidx.compose.material3.IconButton
import androidx.compose.material3.Icon
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.runtime.LaunchedEffect
import kotlinx.coroutines.delay
import com.google.common.util.concurrent.ListenableFuture
import android.media.MediaDrm
import es.openrtve.OpenRtveApplication
import es.openrtve.R
import es.openrtve.domain.PlaybackDecision
import es.openrtve.ui.theme.OpenRtveTheme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible

@OptIn(UnstableApi::class)
class PlayerActivity : ComponentActivity() {
    private var controllerFuture: ListenableFuture<MediaController>? = null
    private var controller by mutableStateOf<MediaController?>(null)
    private var errorRes by mutableStateOf<Int?>(null)
    private var inPictureInPicture by mutableStateOf(false)
    private var locked by mutableStateOf(false)
    private var showUnlock by mutableStateOf(false)
    private var feedback by mutableStateOf<GestureFeedback?>(null)
    private var title by mutableStateOf("")
    private var liveState by mutableStateOf<LiveState?>(null)
    private var menu by mutableStateOf<PlayerMenu?>(null)
    private var sprite by mutableStateOf<PreviewSprite?>(null)
    /** Posición y fracción de la barra mientras el usuario arrastra. */
    private var scrub by mutableStateOf<Pair<Long, Float>?>(null)
    private var nextItem by mutableStateOf<CatalogItem?>(null)
    private var nextCountdown by mutableStateOf<Int?>(null)
    private var showNext by mutableStateOf(false)
    private var extrasJob: Job? = null
    private var request: PlaybackRequest? = null
    /** Una orden del usuario se aplica una vez; reconectar conserva pausa y posición. */
    private var pendingPlay = false
    private var extrasForUri: String? = null
    internal var playerView: PlayerView? = null
    private var liveTicker: Job? = null

    /** Estado del directo: en el borde en vivo o rezagado dentro de la ventana DVR. */
    internal data class LiveState(val atLiveEdge: Boolean)
    private var startJob: Job? = null
    /** Licencia de una petición, pedida mientras se conecta la sesión: el arranque no encadena las dos esperas. */
    private var license: Pair<PlaybackRequest, Deferred<String?>>? = null
    /** Ya se probó la variante sin DRM de la petición actual; no hay más alternativas. */
    private var usedFallback = false

    private val playerListener = object : Player.Listener {
        override fun onMediaMetadataChanged(mediaMetadata: MediaMetadata) {
            title = mediaMetadata.title?.toString().orEmpty()
        }

        override fun onTimelineChanged(timeline: androidx.media3.common.Timeline, reason: Int) = refreshLiveState()

        override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) = refreshLiveState()

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            refreshLiveState()
            updateKeepScreenOn()
        }

        override fun onTracksChanged(tracks: Tracks) {
            if (menu != null) controller?.let { menu = buildPlayerMenu(this@PlayerActivity, it) }
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            if (playbackState == Player.STATE_ENDED && nextItem != null && settings.current.autoplayNext) playNext()
        }

        override fun onPlayerError(error: PlaybackException) {
            val player = controller ?: return
            if (error.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW) {
                // Contrato documentado: reconstruir el item y volver al borde en directo.
                player.seekToDefaultPosition()
                player.prepare()
                return
            }
            val fallback = request?.fallbackUri
            if (error.isDrmFailure() && fallback != null && !usedFallback) {
                // Igual que el cliente oficial en dispositivos sin Widevine: variante de compatibilidad.
                usedFallback = true
                play(player, request?.toMediaItem(uri = fallback, licenseUrl = null) ?: return)
                return
            }
            errorRes = error.toMessageRes()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        onBackPressedDispatcher.addCallback(this, hideControlsOnBack)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )
        request = savedInstanceState?.getBundle(SAVED_REQUEST)?.let(PlaybackRequest::from)
            ?: PlaybackRequest.from(intent)
        pendingPlay = savedInstanceState == null && request != null
        if (pendingPlay) request?.let(::licenseFor)
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) { controller?.let(::startRequested) }
        }
        applyPresentation()

        setContent {
            OpenRtveTheme {
                PlaybackScreen(
                    controller = controller,
                    errorRes = errorRes,
                    title = title,
                    liveState = liveState,
                    onGoLive = { controller?.seekToDefaultPosition() },
                    menu = menu,
                    onOpenMenu = { controller?.let { menu = buildPlayerMenu(this, it) } },
                    onSelectOption = { option ->
                        controller?.let { c ->
                            option.apply(c)
                            menu = buildPlayerMenu(this, c)
                        }
                    },
                    onCloseMenu = { menu = null },
                    sprite = sprite,
                    scrub = scrub,
                    onScrub = { scrub = it },
                    nextTitle = nextItem?.takeIf { showNext }?.title,
                    nextCountdown = nextCountdown,
                    onPlayNext = ::playNext,
                    controlsEnabled = !inPictureInPicture && !locked,
                    locked = locked,
                    showUnlock = showUnlock && !inPictureInPicture,
                    feedback = feedback,
                    canPictureInPicture = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && canEnterPictureInPicture(),
                    onBack = ::finish,
                    onRetry = ::retry,
                    onPictureInPicture = ::enterPictureInPictureNow,
                    onLock = { locked = true; showUnlock = true },
                    onUnlock = { locked = false; showUnlock = false },
                    onLockedTap = { showUnlock = true },
                    onFeedback = { feedback = it },
                    onUnlockShown = { showUnlock = false },
                )
            }
        }
        connect()
    }

    /**
     * En TV el mando envía teclas a la vista con foco, que suele ser la raíz de
     * Compose: se reenvían al PlayerView para que muestre los controles y, una
     * vez visibles, el foco pasa a sus botones.
     */
    internal val hideControlsOnBack = object : androidx.activity.OnBackPressedCallback(false) {
        override fun handleOnBackPressed() {
            playerView?.hideController()
        }
    }

    private var seekTarget = 0L
    private var seekHoldStart = 0L
    private var seekLastStep = 0L

    /**
     * Con los controles a la vista, izquierda/derecha saltan un minuto por pulsación y,
     * manteniendo la tecla, el salto crece poco a poco; el botón central pausa/reanuda.
     */
    @android.annotation.SuppressLint("RestrictedApi")
    override fun dispatchKeyEvent(event: android.view.KeyEvent): Boolean {
        val view = playerView
        if (view != null && menu == null && !locked && view.useController && view.isControllerFullyVisible) {
            val focused = currentFocus?.id
            val onBar = focused == null || focused == androidx.media3.ui.R.id.exo_progress ||
                focused == androidx.media3.ui.R.id.exo_play_pause
            val key = event.keyCode
            if (onBar && (key == android.view.KeyEvent.KEYCODE_DPAD_LEFT || key == android.view.KeyEvent.KEYCODE_DPAD_RIGHT)) {
                if (event.action == android.view.KeyEvent.ACTION_DOWN) seekByKey(view, key == android.view.KeyEvent.KEYCODE_DPAD_RIGHT, event.repeatCount)
                return true
            }
            if (focused == androidx.media3.ui.R.id.exo_progress &&
                (key == android.view.KeyEvent.KEYCODE_DPAD_CENTER || key == android.view.KeyEvent.KEYCODE_ENTER)
            ) {
                if (event.action == android.view.KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                    view.player?.let { if (it.isPlaying) it.pause() else it.play() }
                    view.showController()
                }
                return true
            }
        }
        return super.dispatchKeyEvent(event)
    }

    private fun seekByKey(view: PlayerView, forward: Boolean, repeat: Int) {
        val player = view.player ?: return
        val now = android.os.SystemClock.uptimeMillis()
        if (repeat == 0) {
            seekTarget = player.currentPosition
            seekHoldStart = now
            seekLastStep = 0L
        } else if (now - seekLastStep < SEEK_REPEAT_INTERVAL_MS || now - seekHoldStart < SEEK_HOLD_DELAY_MS) {
            return
        }
        val heldSeconds = if (repeat == 0) 0L else (now - seekHoldStart) / 1000
        val step = SEEK_STEP_MS * (1 + heldSeconds).coerceAtMost(SEEK_MAX_FACTOR)
        val duration = player.duration
        var target = seekTarget + if (forward) step else -step
        target = target.coerceAtLeast(0L)
        if (duration > 0) target = target.coerceAtMost(duration)
        seekTarget = target
        seekLastStep = now
        player.seekTo(target)
        view.showController()
    }

    override fun onKeyDown(keyCode: Int, event: android.view.KeyEvent): Boolean {
        if (menu != null) return super.onKeyDown(keyCode, event)
        val view = playerView
        if (view != null && !locked && view.useController && view.dispatchKeyEvent(event)) return true
        return super.onKeyDown(keyCode, event)
    }



    private fun enterPictureInPictureNow() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && canEnterPictureInPicture()) {
            enterPictureInPictureMode(pictureInPictureParams().build())
        }
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        inPictureInPicture = isInPictureInPictureMode
    }

    override fun onSaveInstanceState(outState: Bundle) {
        request?.let { outState.putBundle(SAVED_REQUEST, it.toBundle()) }
        super.onSaveInstanceState(outState)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        PlaybackRequest.from(intent)?.let { incoming ->
            saveProgress()
            setIntent(intent)
            request = incoming
            pendingPlay = true
            usedFallback = false
            dropLicense()
            licenseFor(incoming)
        }
        // La notificación no trae una orden nueva: conserva el contexto de la sesión.
        applyPresentation()
        errorRes = null
        controller?.let(::startRequested)
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (Build.VERSION.SDK_INT in Build.VERSION_CODES.O until Build.VERSION_CODES.S &&
            canEnterPictureInPicture() &&
            settings.current.pictureInPictureOnLeave &&
            controller?.isPlaying == true
        ) {
            enterPictureInPictureMode(pictureInPictureParams().build())
        }
    }

    /**
     * Comportamiento estándar de un reproductor de vídeo: al salir de la pantalla
     * se pausa (salvo en imagen en imagen), y al cerrarla (atrás, o cerrar la
     * ventana PiP) se detiene.
     */
    override fun onStop() {
        super.onStop()
        saveProgress()
        val inPictureInPicture = Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && isInPictureInPictureMode
        if (!inPictureInPicture || isFinishing) {
            startJob?.cancel()
            startJob = null
            dropLicense()
        }
        val player = controller ?: return
        when {
            isFinishing -> {
                player.stop()
                player.clearMediaItems()
            }
            !inPictureInPicture -> player.pause()
        }
    }

    /** La pantalla sigue encendida solo mientras se reproduce; en pausa, al terminar o con un error se apaga como siempre. */
    private fun updateKeepScreenOn() {
        if (controller?.isPlaying == true) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    private fun refreshLiveState() {
        val player = controller ?: return
        liveState = if (player.isCurrentMediaItemLive) {
            LiveState(atLiveEdge = player.currentLiveOffset in 0..LIVE_EDGE_TOLERANCE_MS || player.currentLiveOffset == C.TIME_UNSET)
        } else {
            null
        }
    }

    override fun onDestroy() {
        extrasJob?.cancel()
        liveTicker?.cancel()
        startJob?.cancel()
        controller?.removeListener(playerListener)
        controller = null
        controllerFuture?.let(MediaController::releaseFuture)
        controllerFuture = null
        super.onDestroy()
    }

    /** Reintento tras un error: reconecta con la sesión si falló la conexión; si no, vuelve a preparar el item. */
    private fun retry() {
        errorRes = null
        usedFallback = false
        val player = controller
        if (player == null) {
            controllerFuture?.let(MediaController::releaseFuture)
            connect()
            return
        }
        pendingPlay = true
        startRequested(player)
    }

    private fun connect() {
        val token = SessionToken(this, ComponentName(this, PlaybackService::class.java))
        val future = MediaController.Builder(this, token).buildAsync()
        controllerFuture = future
        future.addListener(
            {
                runCatching { future.get() }
                    .onSuccess { connected ->
                        connected.addListener(playerListener)
                        title = connected.mediaMetadata.title?.toString().orEmpty()
                        applyTrackPreferences(connected)
                        controller = connected
                        startRequested(connected)
                        updateKeepScreenOn()
                        liveTicker?.cancel()
                        // Solo con la pantalla visible (también en PiP): en segundo plano no hay
                        // nada que pintar y onStop ya guarda la posición.
                        liveTicker = lifecycleScope.launch {
                            repeatOnLifecycle(Lifecycle.State.STARTED) {
                                var ticks = 0
                                while (true) {
                                    delay(LIVE_TICK_MS)
                                    refreshLiveState()
                                    refreshNextState()
                                    if (++ticks % PROGRESS_SAVE_EVERY_TICKS == 0 && controller?.isPlaying == true) saveProgress()
                                }
                            }
                        }
                    }
                    .onFailure { errorRes = R.string.player_error_connect }
            },
            ContextCompat.getMainExecutor(this),
        )
    }

    /** Reconectar conserva la sesión; una orden explícita aplica continuar o reiniciar. */
    private fun startRequested(player: MediaController) {
        if (!lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED) || isFinishing) return
        val target = request ?: player.currentMediaItem?.mediaMetadata?.extras
            ?.let(PlaybackRequest::from)?.also { request = it }
        if (target == null) {
            if (player.currentMediaItem == null) errorRes = R.string.player_error_nothing
            return
        }
        if (extrasForUri != target.uri) {
            extrasForUri = target.uri
            loadExtras(target.videoId)
        }
        val current = player.currentMediaItem?.mediaId
        if (current == target.uri || (target.fallbackUri != null && current == target.fallbackUri)) {
            if (pendingPlay) {
                if (target.restart || player.playbackState == Player.STATE_ENDED) player.seekTo(0L)
                if (player.playbackState == Player.STATE_IDLE) player.prepare()
                player.play()
                pendingPlay = false
            }
            return
        }
        startJob?.cancel()
        startJob = lifecycleScope.launch {
            if (target.drmTokenUrl != null && !isWidevineAvailable() && target.fallbackUri != null) {
                usedFallback = true
                play(player, target.toMediaItem(uri = target.fallbackUri, licenseUrl = null))
                pendingPlay = false
                return@launch
            }
            val licenseUrl = licenseFor(target)?.await()
            if (license?.first === target) license = null
            if (request === target && lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED) && !isFinishing) {
                play(player, target.toMediaItem(uri = target.uri, licenseUrl = licenseUrl))
                pendingPlay = false
            }
        }
    }

    /**
     * URL de licencia de [target], si la necesita. Es temporal: se pide al recibir la
     * orden, se usa una vez y se descarta al salir, nunca se guarda.
     */
    private fun licenseFor(target: PlaybackRequest): Deferred<String?>? {
        val tokenUrl = target.drmTokenUrl ?: return null
        if (target.fallbackUri != null && !isWidevineAvailable()) return null
        license?.let { (owner, pending) -> if (owner === target) return pending }
        return lifecycleScope.async {
            try {
                runInterruptible(Dispatchers.IO) { container.drmTokenClient.widevineLicenseUrl(tokenUrl) }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                null
            }
        }.also { license = target to it }
    }

    private fun dropLicense() {
        license?.second?.cancel()
        license = null
    }

    private val settings get() = (application as OpenRtveApplication).container.settings

    private fun applyTrackPreferences(player: MediaController) {
        val prefs = settings.current
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .apply {
                if (prefs.maxVideoHeight > 0) setMaxVideoSize(Int.MAX_VALUE, prefs.maxVideoHeight) else clearVideoSizeConstraints()
                setPreferredTextLanguages(*if (prefs.subtitlesByDefault) arrayOf("es", "spa") else emptyArray())
                // Siempre en español cuando el vídeo lo trae: muchas series llegan con la versión
                // original (`qaa`) como primera pista. Elegir otra en el panel sigue funcionando.
                setPreferredAudioLanguages("es", "spa")
            }
            .build()
    }

    private fun play(player: MediaController, item: MediaItem) {
        errorRes = null
        val resumeAt = request?.takeUnless { it.restart }?.historyKey
            ?.let { history.entryFor(it)?.takeIf { entry -> entry.inProgress }?.positionMs }
            ?: 0L
        if (resumeAt > 0) player.setMediaItem(item, resumeAt) else player.setMediaItem(item)
        player.prepare()
        player.play()
    }

    private val history get() = (application as OpenRtveApplication).container.watchHistory
    private val container get() = (application as OpenRtveApplication).container

    /** Previews de la barra y siguiente episodio: extras que no bloquean la reproducción. */
    private fun loadExtras(videoId: String?) {
        extrasJob?.cancel()
        sprite = null
        nextItem = null
        showNext = false
        nextCountdown = null
        if (videoId == null) return
        extrasJob = lifecycleScope.launch {
            launch {
                sprite = try {
                    container.catalogRepository.loadPreviewSprite(videoId)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    null
                }
            }
            launch {
                val current = container.watchHistory.entryFor(videoId)?.item
                nextItem = try {
                    if (current?.canHaveNextEpisode == false) null else container.catalogRepository.loadNextVideo(videoId)
                        ?.takeIf { next ->
                            next.canHaveNextEpisode &&
                                (current?.programId == null || next.programId == null || current.programId == next.programId)
                        }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    null
                }
            }
        }
    }

    private fun refreshNextState() {
        val player = controller ?: return
        val next = nextItem
        if (next == null || player.isCurrentMediaItemLive || player.duration == C.TIME_UNSET) {
            showNext = false
            return
        }
        val remaining = player.duration - player.currentPosition
        showNext = remaining in 0..NEXT_PROMPT_BEFORE_END_MS
        nextCountdown = if (showNext && settings.current.autoplayNext) (remaining / 1_000L).toInt().coerceAtLeast(0) else null
    }

    private fun playNext() {
        val next = nextItem ?: return
        val player = controller ?: return
        val decision = container.playbackResolver.resolve(next) as? PlaybackDecision.Ready ?: return
        // El ticker guarda cada 10 s: sin esto, el episodio que acaba puede no quedar como visto.
        saveProgress()
        history.register(next)
        request = PlaybackRequest.from(decision, restart = false)
        setIntent(intent(this, decision))
        pendingPlay = true
        usedFallback = false
        errorRes = null
        showNext = false
        applyPresentation()
        startRequested(player)
    }

    /** Guarda la posición para "Seguir viendo"; el ticker lo llama cada segundo y onStop al salir. */
    private fun saveProgress() {
        val player = controller ?: return
        val target = request ?: return
        val key = target.historyKey ?: return
        val active = player.currentMediaItem?.mediaId
        if (active != target.uri && active != target.fallbackUri) return
        if (player.isCurrentMediaItemLive) return
        val duration = player.duration
        if (duration == C.TIME_UNSET || duration <= 0) return
        history.updateProgress(key, player.currentPosition, duration)
    }

    private fun isWidevineAvailable(): Boolean =
        runCatching { MediaDrm.isCryptoSchemeSupported(C.WIDEVINE_UUID) }.getOrDefault(false)

    private fun PlaybackException.isDrmFailure(): Boolean =
        errorCode in PlaybackException.ERROR_CODE_DRM_UNSPECIFIED..PlaybackException.ERROR_CODE_DRM_LICENSE_EXPIRED

    private fun applyPresentation() {
        // Pantalla completa inmersiva; la orientación apaisada la fija el manifiesto.
        val insets = WindowCompat.getInsetsController(window, window.decorView)
        insets.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        insets.hide(WindowInsetsCompat.Type.systemBars())
        refreshPictureInPictureParams()
    }

    /**
     * Parámetros de PiP para el sistema: entrada automática al salir (si el ajuste
     * lo permite) y, con la vista ya medida, el recuadro del vídeo como
     * origen para que la transición salga de él y no de toda la pantalla.
     */
    internal fun refreshPictureInPictureParams() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        setPictureInPictureParams(
            pictureInPictureParams()
                .setAutoEnterEnabled(canEnterPictureInPicture() && settings.current.pictureInPictureOnLeave)
                .setSeamlessResizeEnabled(false)
                .build(),
        )
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun pictureInPictureParams(): PictureInPictureParams.Builder =
        PictureInPictureParams.Builder()
            .setAspectRatio(Rational(16, 9))
            .apply {
                val rect = Rect()
                if (playerView?.videoSurfaceView?.getGlobalVisibleRect(rect) == true) setSourceRectHint(rect)
            }

    @RequiresApi(Build.VERSION_CODES.N)
    private fun canEnterPictureInPicture(): Boolean =
        request != null && packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)

    @StringRes
    private fun PlaybackException.toMessageRes(): Int {
        val httpStatus = (cause as? HttpDataSource.InvalidResponseCodeException)?.responseCode
        return when {
            isDrmFailure() -> R.string.player_error_drm
            httpStatus == 403 -> R.string.player_error_geo
            httpStatus == 404 || httpStatus == 410 -> R.string.player_error_not_found
            errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED ||
                errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT -> R.string.player_error_offline
            else -> R.string.player_error_generic
        }
    }

    private data class PlaybackRequest(
        val uri: String,
        val mimeType: String,
        val title: String,
        val drmTokenUrl: String?,
        val fallbackUri: String?,
        val historyKey: String?,
        val restart: Boolean,
        val videoId: String?,
    ) {

        fun toBundle(): Bundle = Bundle().apply {
            putString(EXTRA_URI, uri)
            putString(EXTRA_MIME_TYPE, mimeType)
            putString(EXTRA_TITLE, title)
            putString(EXTRA_DRM_TOKEN_URL, drmTokenUrl)
            putString(EXTRA_FALLBACK_URI, fallbackUri)
            putString(EXTRA_HISTORY_KEY, historyKey)
            putString(EXTRA_VIDEO_ID, videoId)
            // Reiniciar es una orden transitoria, no parte del contenido activo.
        }

        fun toMediaItem(uri: String, licenseUrl: String?): MediaItem = MediaItem.Builder()
            .setMediaId(uri)
            .setUri(uri)
            .setMimeType(mimeType)
            .setMediaMetadata(MediaMetadata.Builder().setTitle(title).setExtras(toBundle()).build())
            .apply {
                if (licenseUrl != null) {
                    setDrmConfiguration(
                        MediaItem.DrmConfiguration.Builder(C.WIDEVINE_UUID)
                            .setLicenseUri(licenseUrl)
                            .build(),
                    )
                }
            }
            .build()

        companion object {
            fun from(decision: PlaybackDecision.Ready, restart: Boolean) = PlaybackRequest(
                uri = decision.uri,
                mimeType = decision.mimeType,
                title = decision.title,
                drmTokenUrl = decision.drm?.tokenUrl,
                fallbackUri = decision.fallbackUri,
                historyKey = decision.historyKey,
                restart = restart,
                videoId = decision.videoId,
            )

            fun from(intent: Intent): PlaybackRequest? = intent.extras?.let(::from)

            fun from(extras: Bundle): PlaybackRequest? {
                val uri = extras.getString(EXTRA_URI)?.takeIf(String::isNotBlank) ?: return null
                val mimeType = extras.getString(EXTRA_MIME_TYPE)?.takeIf(String::isNotBlank) ?: return null
                return PlaybackRequest(
                    uri = uri,
                    mimeType = mimeType,
                    title = extras.getString(EXTRA_TITLE).orEmpty(),
                    drmTokenUrl = extras.getString(EXTRA_DRM_TOKEN_URL),
                    fallbackUri = extras.getString(EXTRA_FALLBACK_URI),
                    historyKey = extras.getString(EXTRA_HISTORY_KEY),
                    restart = extras.getBoolean(EXTRA_RESTART, false),
                    videoId = extras.getString(EXTRA_VIDEO_ID),
                )
            }
        }
    }

    companion object {
        private const val SEEK_STEP_MS = 60_000L
        private const val SEEK_MAX_FACTOR = 10L
        private const val SEEK_HOLD_DELAY_MS = 450L
        private const val SEEK_REPEAT_INTERVAL_MS = 300L
        private const val SAVED_REQUEST = "active-playback"
        private const val EXTRA_URI = "playback_uri"
        private const val EXTRA_MIME_TYPE = "playback_mime_type"
        private const val EXTRA_TITLE = "playback_title"
        private const val EXTRA_DRM_TOKEN_URL = "playback_drm_token_url"
        private const val EXTRA_FALLBACK_URI = "playback_fallback_uri"
        private const val EXTRA_HISTORY_KEY = "playback_history_key"
        private const val EXTRA_RESTART = "playback_restart"
        private const val EXTRA_VIDEO_ID = "playback_video_id"

        fun intent(context: Context, decision: PlaybackDecision.Ready, restart: Boolean = false): Intent =
            Intent(context, PlayerActivity::class.java).apply {
                putExtra(EXTRA_URI, decision.uri)
                putExtra(EXTRA_MIME_TYPE, decision.mimeType)
                putExtra(EXTRA_TITLE, decision.title)
                putExtra(EXTRA_DRM_TOKEN_URL, decision.drm?.tokenUrl)
                putExtra(EXTRA_FALLBACK_URI, decision.fallbackUri)
                putExtra(EXTRA_HISTORY_KEY, decision.historyKey)
                putExtra(EXTRA_RESTART, restart)
                putExtra(EXTRA_VIDEO_ID, decision.videoId)
            }
    }
}

@OptIn(UnstableApi::class)
@Composable
private fun PlaybackScreen(
    controller: MediaController?,
    @StringRes errorRes: Int?,
    title: String,
    liveState: PlayerActivity.LiveState?,
    onGoLive: () -> Unit,
    menu: PlayerMenu?,
    onOpenMenu: () -> Unit,
    onSelectOption: (PlayerOption) -> Unit,
    onCloseMenu: () -> Unit,
    sprite: PreviewSprite?,
    scrub: Pair<Long, Float>?,
    onScrub: (Pair<Long, Float>?) -> Unit,
    nextTitle: String?,
    nextCountdown: Int?,
    onPlayNext: () -> Unit,
    controlsEnabled: Boolean,
    locked: Boolean,
    showUnlock: Boolean,
    feedback: GestureFeedback?,
    canPictureInPicture: Boolean,
    onBack: () -> Unit,
    onRetry: () -> Unit,
    onPictureInPicture: () -> Unit,
    onLock: () -> Unit,
    onUnlock: () -> Unit,
    onLockedTap: () -> Unit,
    onFeedback: (GestureFeedback?) -> Unit,
    onUnlockShown: () -> Unit,
) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
    ) {
        when {
            errorRes != null -> PlaybackError(errorRes, onRetry, onBack)
            controller == null -> CircularProgressIndicator()
            else -> AndroidView(
                factory = { context ->
                    val view = LayoutInflater.from(context).inflate(R.layout.player_view, FrameLayout(context), false) as PlayerView
                    val activity = context as PlayerActivity
                    view.addOnLayoutChangeListener { _, l, t, r, b, oldL, oldT, oldR, oldB ->
                        if (l != oldL || t != oldT || r != oldR || b != oldB) activity.refreshPictureInPictureParams()
                    }
                    view.findViewById<ImageButton>(R.id.player_back).setOnClickListener { onBack() }
                    view.findViewById<ImageButton>(R.id.player_lock).setOnClickListener {
                        view.hideController()
                        onLock()
                    }
                    view.findViewById<ImageButton>(R.id.player_pip).apply {
                        visibility = if (canPictureInPicture) View.VISIBLE else View.GONE
                        setOnClickListener { onPictureInPicture() }
                    }
                    view.findViewById<ImageButton>(R.id.player_settings).setOnClickListener {
                        view.hideController()
                        onOpenMenu()
                    }
                    view.findViewById<ImageButton>(R.id.player_next).setOnClickListener { onPlayNext() }
                    view.findViewById<DefaultTimeBar>(androidx.media3.ui.R.id.exo_progress).addListener(
                        object : TimeBar.OnScrubListener {
                            override fun onScrubStart(timeBar: TimeBar, position: Long) = report(position)
                            override fun onScrubMove(timeBar: TimeBar, position: Long) = report(position)
                            override fun onScrubStop(timeBar: TimeBar, position: Long, canceled: Boolean) = onScrub(null)
                            private fun report(position: Long) {
                                val duration = view.player?.duration ?: return
                                if (duration > 0) onScrub(position to (position.toFloat() / duration))
                            }
                        },
                    )
                    view.findViewById<ImageButton>(R.id.player_aspect).setOnClickListener {
                        view.resizeMode = if (view.resizeMode == AspectRatioFrameLayout.RESIZE_MODE_FIT) {
                            AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                        } else {
                            AspectRatioFrameLayout.RESIZE_MODE_FIT
                        }
                    }
                    activity.playerView = view
                    view.setControllerVisibilityListener(
                        PlayerView.ControllerVisibilityListener { visibility ->
                            activity.hideControlsOnBack.isEnabled = visibility == View.VISIBLE
                            if (visibility == View.VISIBLE) {
                                view.post {
                                    if (view.isControllerFullyVisible) {
                                        view.findViewById<View>(androidx.media3.ui.R.id.exo_progress)?.requestFocus()
                                    }
                                }
                            }
                        },
                    )
                    view.setOnTouchListener(
                        PlayerGestures(
                            playerView = view,
                            window = activity.window,
                            isLocked = { !view.useController },
                            onLockedTap = onLockedTap,
                            onFeedback = onFeedback,
                        ),
                    )
                    view
                },
                update = { view ->
                    view.player = controller
                    view.findViewById<TextView>(R.id.player_title).text = title
                    view.useController = controlsEnabled && menu == null
                    if (!controlsEnabled || menu != null) view.hideController()
                    view.findViewById<ImageButton>(R.id.player_next).visibility = if (nextTitle != null) View.VISIBLE else View.GONE
                    // Directo: sin tiempos absolutos (la ventana DVR no es una duración) y con indicador en vivo.
                    val live = view.findViewById<TextView>(R.id.player_live)
                    val isLive = liveState != null
                    view.findViewById<TextView>(androidx.media3.ui.R.id.exo_position).visibility = if (isLive) View.GONE else View.VISIBLE
                    view.findViewById<TextView>(androidx.media3.ui.R.id.exo_duration).visibility = if (isLive) View.GONE else View.VISIBLE
                    live.visibility = if (isLive) View.VISIBLE else View.GONE
                    if (liveState != null) {
                        live.setText(if (liveState.atLiveEdge) R.string.player_live else R.string.player_go_live)
                        live.alpha = if (liveState.atLiveEdge) 1f else 0.6f
                        live.setOnClickListener { if (!liveState.atLiveEdge) onGoLive() }
                    }
                },
                onRelease = { view ->
                    view.player = null
                    (view.context as? PlayerActivity)?.playerView = null
                },
                modifier = Modifier.fillMaxSize(),
            )
        }
        if (sprite != null && scrub != null) {
            ScrubPreview(sprite, scrub.first, scrub.second)
        }
        if (nextTitle != null && menu == null) {
            NextEpisodeCard(nextTitle, nextCountdown, onPlayNext)
        }
        menu?.let {
            androidx.activity.compose.BackHandler(onBack = onCloseMenu)
            PlayerSettingsPanel(it, onSelectOption, onCloseMenu)
        }
        feedback?.let {
            Text(
                text = when (it) {
                    is GestureFeedback.Brightness -> stringResource(R.string.player_brightness, it.percent)
                    is GestureFeedback.Volume -> stringResource(R.string.player_volume, it.percent)
                },
                color = Color.White,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier
                    .background(Color.Black.copy(alpha = 0.6f), MaterialTheme.shapes.small)
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }
        if (locked && showUnlock) {
            LaunchedEffect(showUnlock) {
                delay(UNLOCK_VISIBLE_MS)
                onUnlockShown()
            }
            IconButton(
                onClick = onUnlock,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(12.dp)
                    .background(Color.Black.copy(alpha = 0.6f), MaterialTheme.shapes.small),
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_player_unlock),
                    contentDescription = stringResource(R.string.player_unlock),
                    tint = Color.White,
                )
            }
        }
    }
}

/**
 * Error de reproducción con salida: los controles del reproductor no se pintan
 * aquí y, en modo inmersivo, sin botones solo quedaba el gesto de atrás.
 */
@Composable
private fun PlaybackError(@StringRes errorRes: Int, onRetry: () -> Unit, onBack: () -> Unit) {
    // En TV el mando necesita un botón con foco desde el principio.
    val retryFocus = remember { FocusRequester() }
    LaunchedEffect(errorRes) { runCatching { retryFocus.requestFocus() } }
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
        modifier = Modifier.padding(24.dp),
    ) {
        Text(
            text = stringResource(errorRes),
            color = Color.White,
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(onClick = onBack) { Text(stringResource(R.string.action_back)) }
            Button(onClick = onRetry, modifier = Modifier.focusRequester(retryFocus)) { Text(stringResource(R.string.action_retry)) }
        }
    }
}

private const val UNLOCK_VISIBLE_MS = 3_000L
private const val LIVE_TICK_MS = 1_000L
private const val PROGRESS_SAVE_EVERY_TICKS = 10
private const val LIVE_EDGE_TOLERANCE_MS = 20_000L
private const val NEXT_PROMPT_BEFORE_END_MS = 20_000L
