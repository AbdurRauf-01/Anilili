package com.anilili.ui

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Whether the first screen has finished trying to load.
 *
 * TV boxes have very little headroom, and standing up the hidden resolver browser is the single
 * most expensive optional thing the app does at startup — it builds a WebView and loads a full
 * page. A device report showed that happening while the home screen's own request was still in
 * flight (AniList was unreachable on that network and hung for 12s), so the two competed on a
 * box that was already dropping frames.
 *
 * Nothing is waiting on the resolver at startup: it only matters once the viewer picks something
 * to watch, which is always after the first screen has resolved one way or the other. So on TV it
 * waits for this. [mark] is called when the first screen settles — success *or* failure, because
 * a permanently unreachable home must not keep the resolver switched off forever.
 */
object FirstContent {
    private val _settled = MutableStateFlow(false)
    val settled = _settled.asStateFlow()

    fun mark() {
        _settled.value = true
    }
}
