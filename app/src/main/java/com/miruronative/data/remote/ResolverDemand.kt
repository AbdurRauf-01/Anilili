package com.miruronative.data.remote

import com.miruronative.diagnostics.ResolverDiagnostics
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Reference-counted request for a hidden resolver WebView.
 *
 * A Boolean alone is not sufficient: episode discovery and source resolution can overlap, and
 * closing the first request must not tear the shared browser out from under the second one.
 */
internal class ResolverDemand(private val name: String) {
    private val lock = Any()
    private var activeLeases = 0
    private val mutableRequired = MutableStateFlow(false)

    val required: StateFlow<Boolean> = mutableRequired.asStateFlow()

    fun acquire(): Lease {
        synchronized(lock) {
            activeLeases++
            if (activeLeases == 1) mutableRequired.value = true
            ResolverDiagnostics.demandChanged(name, activeLeases)
        }
        return Lease()
    }

    inner class Lease internal constructor() : AutoCloseable {
        private val closed = AtomicBoolean(false)

        override fun close() {
            if (!closed.compareAndSet(false, true)) return
            synchronized(lock) {
                activeLeases = (activeLeases - 1).coerceAtLeast(0)
                if (activeLeases == 0) mutableRequired.value = false
                ResolverDiagnostics.demandChanged(name, activeLeases)
            }
        }
    }
}
