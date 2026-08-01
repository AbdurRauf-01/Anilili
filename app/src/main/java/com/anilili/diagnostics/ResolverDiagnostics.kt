package com.anilili.diagnostics

import android.os.SystemClock

/** Small, process-local resolver lifecycle ledger included in diagnostic snapshots. */
object ResolverDiagnostics {
    private data class State(
        var activeDemands: Int = 0,
        var attachedViews: Int = 0,
        var activeSinceMs: Long = 0,
        var requestCount: Long = 0,
        var lastActiveMs: Long = 0,
        var maxActiveMs: Long = 0,
        var rendererGoneCount: Long = 0,
    )

    private val lock = Any()
    private val states = linkedMapOf<String, State>()

    fun demandChanged(name: String, active: Int) {
        val now = SystemClock.elapsedRealtime()
        val attributes = synchronized(lock) {
            val state = states.getOrPut(name, ::State)
            val previous = state.activeDemands
            state.activeDemands = active.coerceAtLeast(0)
            if (state.activeDemands > previous) state.requestCount++
            if (previous == 0 && state.activeDemands > 0) {
                state.activeSinceMs = now
            } else if (previous > 0 && state.activeDemands == 0) {
                val duration = (now - state.activeSinceMs).coerceAtLeast(0)
                state.lastActiveMs = duration
                state.maxActiveMs = maxOf(state.maxActiveMs, duration)
                state.activeSinceMs = 0
            }
            mapOf(
                "resolver" to name,
                "activeRequests" to state.activeDemands,
                "attachedViews" to state.attachedViews,
                "requestCount" to state.requestCount,
            )
        }
        DiagnosticsLog.event("resolver", "demand.changed", attributes)
    }

    fun viewChanged(name: String, attached: Boolean) {
        val attributes = synchronized(lock) {
            val state = states.getOrPut(name, ::State)
            state.attachedViews = if (attached) 1 else 0
            mapOf(
                "resolver" to name,
                "attached" to attached,
                "activeRequests" to state.activeDemands,
            )
        }
        DiagnosticsLog.event("resolver", "webview.${if (attached) "attached" else "detached"}", attributes)
    }

    fun rendererGone(name: String, crashed: Boolean?) {
        val count = synchronized(lock) {
            states.getOrPut(name, ::State).run {
                rendererGoneCount++
                rendererGoneCount
            }
        }
        DiagnosticsLog.event(
            "resolver",
            "renderer.gone",
            mapOf("resolver" to name, "crashed" to crashed, "count" to count),
        )
    }

    fun snapshotAttributes(): Map<String, String> = synchronized(lock) {
        val now = SystemClock.elapsedRealtime()
        buildMap {
            put("resolverActiveRequests", states.values.sumOf(State::activeDemands).toString())
            put("resolverAttachedWebViews", states.values.sumOf(State::attachedViews).toString())
            put(
                "resolverActiveNames",
                states.filterValues { it.activeDemands > 0 }.keys.joinToString("/").ifBlank { "none" },
            )
            states.forEach { (name, state) ->
                val safeName = name.lowercase().replace(Regex("[^a-z0-9]"), "")
                val currentDuration = if (state.activeSinceMs > 0) now - state.activeSinceMs else 0
                put("resolver${safeName}Requests", state.requestCount.toString())
                put("resolver${safeName}CurrentMs", currentDuration.coerceAtLeast(0).toString())
                put("resolver${safeName}LastMs", state.lastActiveMs.toString())
                put("resolver${safeName}MaxMs", state.maxActiveMs.toString())
                put("resolver${safeName}RendererGone", state.rendererGoneCount.toString())
            }
        }
    }
}
