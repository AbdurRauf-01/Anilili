package com.miruronative.diagnostics

import android.app.Activity
import android.os.Bundle
import android.widget.Toast
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/** Launcher entry that sends diagnostics without booting the normal Compose/WebView app. */
class ShareDiagnosticsActivity : Activity() {
    private val scope = MainScope()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        DiagnosticsLog.event("ShareDiagnosticsActivity.onCreate")
        scope.launch {
            val result = DiagnosticsUploadManager.send(
                this@ShareDiagnosticsActivity,
                DiagnosticTrigger.SHORTCUT,
            )
            Toast.makeText(
                this@ShareDiagnosticsActivity,
                result.userMessage(),
                Toast.LENGTH_LONG,
            ).show()
            finish()
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}
