package es.openrtve.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackResolverTest {
    private val resolver = PlaybackResolver()

    @Test
    fun `video resolves to dash with a license reference and a compatibility fallback`() {
        val result = resolver.resolve(item(kind = ContentKind.VIDEO)) as PlaybackDecision.Ready

        assertEquals("https://ztnr.rtve.es/ztnr/100.mpd", result.uri)
        assertEquals(DrmSpec("https://api.rtve.es/api/token/100"), result.drm)
        assertEquals("https://ztnr.rtve.es/ztnr/tv/100.mpd", result.fallbackUri)
    }

    @Test
    fun `public live resolves to hls and drm live to dash`() {
        val plain = resolver.resolve(item(kind = ContentKind.LIVE).copy(assetId = "live-1")) as PlaybackDecision.Ready
        val drm = resolver.resolve(item(kind = ContentKind.LIVE).copy(assetId = "la1", drm = true)) as PlaybackDecision.Ready

        assertEquals("https://ztnr.rtve.es/ztnr/live-1.m3u8", plain.uri)
        assertNull(plain.drm)
        assertEquals("https://ztnr.rtve.es/ztnr/la1.mpd", drm.uri)
        assertEquals(DrmSpec("https://api.rtve.es/api/token/la1"), drm.drm)
    }

    @Test
    fun `rights are checked before constructing a stream`() {
        assertEquals(
            PlaybackDecision.Blocked(BlockReason.GEO_RESTRICTED),
            resolver.resolve(item(kind = ContentKind.VIDEO).copy(allowedInCountry = false)),
        )
        assertEquals(
            PlaybackDecision.Blocked(BlockReason.SUBSCRIPTION_REQUIRED),
            resolver.resolve(item(kind = ContentKind.VIDEO).copy(paid = true)),
        )
    }

    @Test
    fun `a scheduled live that has not started is blocked until its start time`() {
        val live = item(kind = ContentKind.LIVE).copy(
            assetId = "a",
            live = LiveInfo(isOnAir = false, startsAtMillis = 2_000L, durationMinutes = 60, progressPercent = 0, channelLogoUrl = null, category = null),
        )

        assertEquals(PlaybackDecision.Blocked(BlockReason.NOT_STARTED_YET), PlaybackResolver(nowMillis = { 1_000L }).resolve(live))
        assertTrue(PlaybackResolver(nowMillis = { 3_000L }).resolve(live) is PlaybackDecision.Ready)
        assertNull((PlaybackResolver(nowMillis = { 3_000L }).resolve(live) as PlaybackDecision.Ready).historyKey)
    }

    @Test
    fun `login gate is a client policy and only applies when enforced`() {
        val gated = item(kind = ContentKind.VIDEO).copy(loginRequired = true)

        assertTrue(resolver.resolve(gated) is PlaybackDecision.Ready)
        assertEquals(
            PlaybackDecision.Blocked(BlockReason.LOGIN_REQUIRED),
            PlaybackResolver(enforceLoginGate = true).resolve(gated),
        )
    }

    @Test
    fun `program without a media id is blocked instead of guessing a stream`() {
        val result = resolver.resolve(item(kind = ContentKind.PROGRAM).copy(playbackId = null))

        assertEquals(PlaybackDecision.Blocked(BlockReason.NO_SOURCE), result)
    }

    private fun item(kind: ContentKind) = CatalogItem(
        id = "item-1",
        playbackId = "100",
        assetId = null,
        title = "Contenido",
        subtitle = null,
        imageUrl = null,
        kind = kind,
        allowedInCountry = true,
        loginRequired = false,
        paid = false,
        drm = false,
    )
}
