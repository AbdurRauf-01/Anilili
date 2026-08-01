package com.anilili.ui

import android.annotation.SuppressLint
import android.graphics.Color
import android.net.Uri
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.anilili.data.remote.ALLANIME_USER_AGENT
import com.anilili.data.remote.AllAnimeCaptchaCoordinator
import com.anilili.data.remote.AllAnimeCaptchaSolution
import com.anilili.diagnostics.DiagnosticsLog
import com.anilili.diagnostics.WebViewProcessController
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** App-level host so a provider request can suspend without coupling networking to the watch UI. */
@Composable
fun AllAnimeCaptchaHost() {
    val challenge by AllAnimeCaptchaCoordinator.challenge.collectAsState()
    challenge?.let { current ->
        AllAnimeCaptchaDialog(
            challenge = current,
            onSolved = { AllAnimeCaptchaCoordinator.submit(current.id, it) },
            onCancel = { AllAnimeCaptchaCoordinator.cancel(current.id) },
        )
    }
}

@Composable
private fun AllAnimeCaptchaDialog(
    challenge: AllAnimeCaptchaCoordinator.Challenge,
    onSolved: (AllAnimeCaptchaSolution) -> Unit,
    onCancel: () -> Unit,
) {
    Dialog(
        onDismissRequest = onCancel,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(
                modifier = Modifier.fillMaxSize().padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text("AllAnime security check", style = MaterialTheme.typography.headlineSmall)
                Text(
                    "Complete the provider's security check once to load this episode. " +
                        "The result is used for this request only and is not saved.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Box(
                    modifier = Modifier.fillMaxWidth().weight(1f).heightIn(min = 260.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    CaptchaWebView(challenge.url, onSolved)
                }
                Button(onClick = onCancel, modifier = Modifier.align(Alignment.End)) {
                    Text("Cancel")
                }
            }
        }
    }
}

@SuppressLint("SetJavaScriptEnabled", "JavascriptInterface")
@Composable
private fun CaptchaWebView(url: String, onSolved: (AllAnimeCaptchaSolution) -> Unit) {
    AndroidView(
        modifier = Modifier.fillMaxSize().focusProperties { canFocus = true },
        factory = { context ->
            WebView(context).apply {
                setBackgroundColor(Color.rgb(245, 245, 245))
                isFocusable = true
                isFocusableInTouchMode = true
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                )
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.allowFileAccess = false
                settings.allowContentAccess = false
                settings.setSupportMultipleWindows(false)
                settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                settings.userAgentString = ALLANIME_USER_AGENT
                CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                webChromeClient = WebChromeClient()
                webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView?, finishedUrl: String?) {
                        super.onPageFinished(view, finishedUrl)
                        view?.requestFocus()
                        DiagnosticsLog.event("AllAnime CAPTCHA page ready")
                    }
                }
                val completed = AtomicBoolean(false)
                addJavascriptInterface(
                    CaptchaResultBridge(this) { solution ->
                        if (completed.compareAndSet(false, true)) onSolved(solution)
                    },
                    BRIDGE_NAME,
                )
                WebViewProcessController.register(this, "allanime-captcha")
                val challengeUri = Uri.parse(url)
                if (challengeUri.scheme == "https" && challengeUri.host == CAPTCHA_HOST) {
                    loadDataWithBaseURL(
                        "https://mkissa.to/",
                        captchaWrapper(url),
                        "text/html",
                        "UTF-8",
                        null,
                    )
                } else {
                    DiagnosticsLog.event("AllAnime CAPTCHA rejected unexpected host")
                }
            }
        },
        onRelease = { view ->
            val web = view as WebView
            DiagnosticsLog.event("AllAnime CAPTCHA WebView release")
            web.stopLoading()
            web.removeJavascriptInterface(BRIDGE_NAME)
            web.webChromeClient = null
            web.webViewClient = WebViewClient()
            web.settings.javaScriptEnabled = false
            web.clearHistory()
            WebViewProcessController.release(web, "allanime-captcha")
            web.destroy()
        },
    )
}

private class CaptchaResultBridge(
    private val webView: WebView,
    private val onSolved: (AllAnimeCaptchaSolution) -> Unit,
) {
    @JavascriptInterface
    fun onResult(raw: String) {
        if (raw.length !in 1..MAX_CALLBACK_CHARS) return
        val root = runCatching { Json.parseToJsonElement(raw) as? JsonObject }.getOrNull() ?: return
        val token = (root["token"] as? JsonPrimitive)?.contentOrNull?.takeIf(String::isNotBlank) ?: return
        val provider = (root["provider"] as? JsonPrimitive)?.contentOrNull?.lowercase() ?: return
        if (provider !in setOf("turnstile", "turnstile1", "google")) return
        webView.post { onSolved(AllAnimeCaptchaSolution(token, provider)) }
    }
}

private fun captchaWrapper(url: String): String {
    val safeUrl = url.replace("&", "&amp;").replace("\"", "&quot;").replace("<", "&lt;")
    return """
        <!doctype html><html><head>
        <meta name="viewport" content="width=device-width,initial-scale=1">
        <style>html,body,iframe{width:100%;height:100%;margin:0;border:0;background:#f5f5f5}</style>
        </head><body><iframe src="$safeUrl" title="AllAnime security check"></iframe>
        <script>
        window.addEventListener('message', function(event) {
          if (event.origin !== 'https://api.mkissa.net') return;
          var data = event.data;
          if (!data || data.type !== 'sitea-captcha-ready' || !data.token) return;
          window.$BRIDGE_NAME.onResult(JSON.stringify({token:data.token,provider:data.provider||'turnstile'}));
        });
        </script></body></html>
    """.trimIndent()
}

private const val CAPTCHA_HOST = "api.mkissa.net"
private const val BRIDGE_NAME = "AnililiCaptchaResult"
private const val MAX_CALLBACK_CHARS = 8_192
