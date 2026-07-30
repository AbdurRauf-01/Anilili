package com.miruronative.diagnostics

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
    AlertDialog(
        onDismissRequest = { if (!sending) onDiscard() },
        title = { Text("Send crash diagnostics?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    "This sends the full crash, device, performance, playback and network timing report " +
                        "to Anilili for debugging. Passwords, cookies, tokens and sensitive links are removed.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    report,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    lineHeight = 14.sp,
                    modifier = Modifier
                        .heightIn(max = 240.dp)
                        .verticalScroll(rememberScrollState()),
                )
                errorMessage?.let {
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !sending,
                onClick = {
                    sending = true
                    errorMessage = null
                    scope.launch {
                        when (val result = DiagnosticsUploadManager.send(context, DiagnosticTrigger.CRASH)) {
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
            ) {
                Text(if (sending) "Sending…" else "Send report")
            }
        },
        dismissButton = {
            TextButton(enabled = !sending, onClick = onDiscard) {
                Text("Don't send", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
    )
}
