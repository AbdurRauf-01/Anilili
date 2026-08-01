package com.anilili.playback

import android.content.Context

/**
 * TV builds ship no Cast stack.
 *
 * A television is the destination of a cast, not a source, and on Fire TV the Play Services shell
 * cannot serve the cast dynamite module at all — every device report showed `CastContext` throwing
 * during service creation. Declining here keeps `media3-cast`, `play-services-cast-framework` and
 * `mediarouter` out of the TV build entirely.
 */
internal object CastRoutes {
    fun create(context: Context): CastRoute? = null
}
