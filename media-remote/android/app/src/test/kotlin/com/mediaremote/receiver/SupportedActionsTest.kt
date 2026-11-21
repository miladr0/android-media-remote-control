package com.mediaremote.receiver

import android.media.session.PlaybackState
import com.mediaremote.receiver.media.MediaSessionCoordinator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class SupportedActionsTest {

    @Test
    fun `extractSupportedActionsFromMask omits stop when ACTION_STOP is absent`() {
        val spotifyLikeMask =
            PlaybackState.ACTION_PLAY or
                PlaybackState.ACTION_PAUSE or
                PlaybackState.ACTION_SKIP_TO_NEXT or
                PlaybackState.ACTION_SKIP_TO_PREVIOUS

        val supported = MediaSessionCoordinator.extractSupportedActionsFromMask(spotifyLikeMask)
        assertEquals(listOf("play", "pause", "next", "previous"), supported)
        assertFalse(supported.contains("stop"))
    }

    @Test
    fun `extractSupportedActionsFromMask includes stop when ACTION_STOP is present`() {
        val vlcLikeMask =
            PlaybackState.ACTION_PLAY or
                PlaybackState.ACTION_PAUSE or
                PlaybackState.ACTION_STOP or
                PlaybackState.ACTION_SKIP_TO_NEXT or
                PlaybackState.ACTION_SKIP_TO_PREVIOUS

        val supported = MediaSessionCoordinator.extractSupportedActionsFromMask(vlcLikeMask)
        assertEquals(listOf("play", "pause", "stop", "next", "previous"), supported)
    }
}
