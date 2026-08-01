package com.anilili.playback

import android.content.Context
import androidx.media3.common.Player

/**
 * A remote playback route the service can hand the queue to.
 *
 * Google Cast only exists in the mobile flavor: a TV is where casting *ends up*, and Fire TV's
 * cut-down Play Services throws on every attempt to build a CastContext. Rather than guard each
 * call site, the TV flavor supplies a factory that always declines, and the whole Cast dependency
 * stays out of that build.
 */
internal interface CastRoute {
    /** The player to hand playback to while a receiver is connected. */
    val player: Player

    /** Registers callbacks for a receiver connecting and disconnecting. */
    fun onSessionChanged(onAvailable: () -> Unit, onUnavailable: () -> Unit)

    fun release()
}

// The factory itself is `CastRoutes.create(context)`, declared once in each flavor source set:
// `src/mobile` builds a real route, `src/tv` always returns null. Only one is ever on the
// compile path, so shared code can call it unconditionally.
