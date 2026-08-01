package com.anilili.diagnostics

import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.launch

/** Explicit crash consent. Nothing is uploaded until the user chooses Send report. */
@Composable
fun CrashReportDialog(
    report: String,
    onAccepted: () -> Unit,
    onDiscard: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var sending by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    DiagnosticSubmissionDialog(
        title = "Send crash diagnostics?",
        introduction = "This sends the full crash, device, performance, playback and network timing " +
            "report to Anilili for debugging. Passwords, cookies, tokens and sensitive links are removed.",
        technicalDetails = report,
        descriptionRequired = false,
        dismissLabel = "Don't send",
        sending = sending,
        errorMessage = errorMessage,
        onDismiss = onDiscard,
        onSend = { submission ->
            sending = true
            errorMessage = null
            scope.launch {
                when (
                    val result = DiagnosticsUploadManager.send(
                        context,
                        DiagnosticTrigger.CRASH,
                        submission,
                    )
                ) {
                    is DiagnosticSendResult.Failed -> {
                        errorMessage = result.reason
                        sending = false
                    }
                    else -> {
                        Toast.makeText(context, result.userMessage(), Toast.LENGTH_LONG).show()
                        onAccepted()
                    }
                }
            }
        },
    )
}
