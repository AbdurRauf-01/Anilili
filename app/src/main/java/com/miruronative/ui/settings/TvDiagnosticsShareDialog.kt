package com.miruronative.ui.settings

import android.Manifest
import android.graphics.Bitmap
import android.os.Build
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.miruronative.diagnostics.DiagnosticsLog
import com.miruronative.diagnostics.DiagnosticsServer
import com.miruronative.ui.adaptive.focusHighlight
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * TV replacement for the ACTION_SEND diagnostics share (TV has no share targets): serves the
 * snapshot over the local network with a QR code + typable address. Saving a persistent copy to
 * public Downloads is a separate, explicit action. The server lives only while this dialog shows.
 */
@Composable
fun TvDiagnosticsShareDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    var url by remember { mutableStateOf<String?>(null) }
    var report by remember { mutableStateOf<File?>(null) }
    var downloadsPath by remember { mutableStateOf<String?>(null) }
    var downloadsSaving by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    val closeFocus = remember { FocusRequester() }
    val scope = rememberCoroutineScope()

    fun saveToDownloads() {
        val currentReport = report ?: return
        scope.launch {
            downloadsSaving = true
            errorMessage = null
            withContext(Dispatchers.IO) {
                DiagnosticsLog.saveToDownloads(context, currentReport)
            }.onSuccess { downloadsPath = it }
                .onFailure { errorMessage = it.message ?: "Couldn't save diagnostics" }
            downloadsSaving = false
        }
    }

    val storagePermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) saveToDownloads() else errorMessage = "Storage permission is required on this Android version."
    }

    LaunchedEffect(Unit) {
        val snapshotResult = withContext(Dispatchers.IO) {
            runCatching { DiagnosticsLog.createShareSnapshot(context.applicationContext) }
        }
        snapshotResult
            .onSuccess { snapshot ->
                report = snapshot
                withContext(Dispatchers.IO) { DiagnosticsServer.start(snapshot) }
                    .onSuccess { url = it }
                    .onFailure { errorMessage = it.message ?: "Couldn't start the local server" }
            }
            .onFailure { errorMessage = it.message ?: "Couldn't prepare diagnostics" }
        runCatching { closeFocus.requestFocus() }
    }
    DisposableEffect(Unit) {
        onDispose { DiagnosticsServer.stop() }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surface,
            modifier = Modifier
                .widthIn(max = 780.dp)
                .padding(24.dp),
        ) {
            Column(Modifier.padding(28.dp)) {
                Text(
                    "Share diagnostics",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Black,
                )
                Spacer(Modifier.height(16.dp))

                val currentUrl = url
                when {
                    currentUrl != null -> ShareInstructions(currentUrl)
                    errorMessage != null -> Text(
                        errorMessage.orEmpty(),
                        color = MaterialTheme.colorScheme.error,
                    )
                    else -> Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                        Text("Preparing diagnostics…", Modifier.padding(start = 10.dp))
                    }
                }

                downloadsPath?.let { path ->
                    Spacer(Modifier.height(14.dp))
                    Text(
                        "A copy was also saved on this device: $path (open it with a file " +
                            "manager or copy it off with a USB stick).",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                Spacer(Modifier.height(18.dp))
                Row(
                    modifier = Modifier.align(Alignment.End),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Button(
                        enabled = report != null && downloadsPath == null && !downloadsSaving,
                        onClick = {
                            val needsPermission = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q &&
                                ContextCompat.checkSelfPermission(
                                    context,
                                    Manifest.permission.WRITE_EXTERNAL_STORAGE,
                                ) != PackageManager.PERMISSION_GRANTED
                            if (needsPermission) {
                                storagePermission.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                            } else {
                                saveToDownloads()
                            }
                        },
                        modifier = Modifier.focusHighlight(RoundedCornerShape(24.dp)),
                    ) {
                        Text(
                            when {
                                downloadsSaving -> "Saving…"
                                downloadsPath != null -> "Saved to Downloads"
                                else -> "Save to Downloads"
                            },
                            fontWeight = FontWeight.Bold,
                        )
                    }
                    Button(
                        onClick = onDismiss,
                        modifier = Modifier
                            .focusRequester(closeFocus)
                            .focusHighlight(RoundedCornerShape(24.dp)),
                    ) {
                        Text("Close", fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

@Composable
private fun ShareInstructions(url: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
        QrCode(url)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                url,
                style = MaterialTheme.typography.titleLarge,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
            )
            Text("1. Connect your phone or computer to the same Wi-Fi network as this TV.")
            Text("2. Scan the QR code with your phone camera, or type the address into a browser.")
            Text("3. Download the ZIP and send it to the developers, for example in the Telegram group linked in Settings.")
            Text(
                "The address contains a private access token, expires after 10 minutes, and stops " +
                    "working when you close this dialog. Common sensitive values are redacted; " +
                    "Android native crash traces can contain low-level device or process details.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun QrCode(content: String) {
    val bitmap = remember(content) {
        runCatching { qrBitmap(content, sizePx = 512) }.getOrNull()
    }
    if (bitmap != null) {
        Image(
            bitmap = bitmap.asImageBitmap(),
            contentDescription = "QR code for $content",
            modifier = Modifier
                .size(230.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(Color.White)
                .padding(10.dp),
        )
    }
}

private fun qrBitmap(content: String, sizePx: Int): Bitmap {
    val matrix = QRCodeWriter().encode(
        content,
        BarcodeFormat.QR_CODE,
        sizePx,
        sizePx,
        mapOf(EncodeHintType.MARGIN to 1),
    )
    val pixels = IntArray(sizePx * sizePx) { index ->
        val x = index % sizePx
        val y = index / sizePx
        if (matrix.get(x, y)) android.graphics.Color.BLACK else android.graphics.Color.WHITE
    }
    return Bitmap.createBitmap(pixels, sizePx, sizePx, Bitmap.Config.ARGB_8888)
}
