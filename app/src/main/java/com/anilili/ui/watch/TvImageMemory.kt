package com.anilili.ui.watch

import android.content.Context
import coil.Coil
import com.anilili.diagnostics.DiagnosticsLog

/**
 * Hands the decoded-image cache back before playback starts on TV.
 *
 * TV boxes run around 1 GB total and the video decoder wants a large slice of it, so the poster
 * bitmaps held for the home grid are the cheapest thing to give up. The cost is that they are
 * decoded again from Coil's disk cache when the user backs out — Coil 2's `MemoryCache` has no
 * partial trim and its key set carries no recency order, so there is no way to keep just the hot
 * entries. Phones keep their cache; this is TV-only.
 *
 * The size is logged so the trade can actually be judged from a diagnostics dump rather than
 * guessed at: if `beforeKb` is routinely small, this is churn and should go.
 */
internal fun releaseImageMemoryForPlayback(context: Context) {
    runCatching {
        val cache = Coil.imageLoader(context).memoryCache ?: return@runCatching
        val beforeKb = cache.size / 1024
        cache.clear()
        DiagnosticsLog.event(
            "TV image cache released beforeKb=$beforeKb maxKb=${cache.maxSize / 1024}",
        )
    }
}
