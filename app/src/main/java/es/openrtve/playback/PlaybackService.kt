package es.openrtve.playback

import android.app.PendingIntent
import android.content.Intent
import androidx.media3.common.AudioAttributes
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.drm.DefaultDrmSessionManagerProvider
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import es.openrtve.OpenRtveApplication
import es.openrtve.data.SafeHttpsClient

class PlaybackService : MediaSessionService() {
    private var mediaSession: MediaSession? = null

    @OptIn(UnstableApi::class)
    override fun onCreate() {
        super.onCreate()
        // Manifiestos, segmentos y licencias por el OkHttp de la app: mismo pool de
        // conexiones (HTTP/2) y el mismo User-Agent que el resto de peticiones.
        val http = OkHttpDataSource.Factory((application as OpenRtveApplication).container.mediaHttpClient)
            .setUserAgent(SafeHttpsClient.USER_AGENT)
        val mediaSources = DefaultMediaSourceFactory(DefaultDataSource.Factory(this, http))
            .setDrmSessionManagerProvider(DefaultDrmSessionManagerProvider().apply { setDrmHttpDataSourceFactory(http) })
        val player = ExoPlayer.Builder(this)
            .setMediaSourceFactory(mediaSources)
            .setSeekBackIncrementMs(SEEK_INCREMENT_MS)
            .setSeekForwardIncrementMs(SEEK_INCREMENT_MS)
            .build().apply {
            setAudioAttributes(
                AudioAttributes.Builder()
                    .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                    .setUsage(C.USAGE_MEDIA)
                    .build(),
                true,
            )
            setHandleAudioBecomingNoisy(true)
        }
        // Tocar la notificación vuelve al reproductor con lo que ya está en sesión.
        val sessionActivity = PendingIntent.getActivity(
            this,
            0,
            Intent(this, PlayerActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        mediaSession = MediaSession.Builder(this, player)
            .setSessionActivity(sessionActivity)
            .build()
    }

    private companion object {
        const val SEEK_INCREMENT_MS = 10_000L
    }

    override fun onGetSession(
        controllerInfo: MediaSession.ControllerInfo,
    ): MediaSession? = mediaSession

    override fun onDestroy() {
        mediaSession?.let { session ->
            session.player.release()
            session.release()
        }
        mediaSession = null
        super.onDestroy()
    }
}
