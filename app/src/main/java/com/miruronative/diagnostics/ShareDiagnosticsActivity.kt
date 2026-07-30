package com.miruronative.diagnostics

import android.app.Activity
import android.os.Bundle
import android.widget.Toast
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/** Launcher entry that shares diagnostics without booting the normal Compose/WebView app. */
class ShareDiagnosticsActivity : Activity() {
    private val scope = MainScope()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        DiagnosticsLog.event("ShareDiagnosticsActivity.onCreate")
        scope.launch {
            DiagnosticsLog.share(this@ShareDiagnosticsActivity)
                .onFailure { error ->
                    Toast.makeText(
                        this@ShareDiagnosticsActivity,
                        error.message ?: "Couldn't share diagnostics",
                        Toast.LENGTH_LONG,
                    ).show()
                }
            finish()
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}
