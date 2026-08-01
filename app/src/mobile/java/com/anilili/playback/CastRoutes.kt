package com.anilili.playback

import android.content.Context
import androidx.media3.cast.CastPlayer
import androidx.media3.cast.SessionAvailabilityListener
import androidx.media3.common.Player
import com.google.android.gms.cast.framework.CastContext
import com.anilili.diagnostics.DiagnosticsLog

/** Mobile builds carry Google Cast. */
internal object CastRoutes {
    fun create(context: Context): CastRoute? {
        // Play Services can be present but unable to serve the cast module (an OEM shell, a
        // stale install). That is a missing capability, not a reason to fail the service —
        // playback simply runs local.
        val castPlayer = runCatching {
            CastPlayer(CastContext.getSharedInstance(context), MiruroCastMediaItemConverter())
        }
            .onFailure { DiagnosticsLog.throwable("PlaybackService cast unavailable", it) }
            .getOrNull()
            ?: return null
        return GoogleCastRoute(castPlayer)
    }
}

private class GoogleCastRoute(private val castPlayer: CastPlayer) : CastRoute {
    override val player: Player get() = castPlayer

    override fun onSessionChanged(onAvailable: () -> Unit, onUnavailable: () -> Unit) {
        castPlayer.setSessionAvailabilityListener(object : SessionAvailabilityListener {
            override fun onCastSessionAvailable() = onAvailable()
            override fun onCastSessionUnavailable() = onUnavailable()
        })
    }

    override fun release() {
        castPlayer.release()
    }
}
