package com.miruronative.diagnostics

import android.os.SystemClock
import java.io.File
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.security.SecureRandom

/** Short-lived, token-protected LAN download server used by the Android TV share dialog. */
object DiagnosticsServer {
    private const val PREFERRED_PORT = 38_500
    private const val SERVER_LIFETIME_MS = 10 * 60 * 1_000L

    @Volatile private var server: ServerSocket? = null
    @Volatile private var accessToken: String? = null
    @Volatile private var expiresAtElapsedMs: Long = 0

    /** Starts serving [file] and returns an unguessable URL valid while the dialog remains open. */
    fun start(file: File): Result<String> = runCatching {
        stop()
        val host = localIpv4()
            ?: error("This TV has no local network address. Connect it to Wi-Fi or Ethernet and try again.")
        val socket = runCatching { ServerSocket(PREFERRED_PORT) }.getOrElse { ServerSocket(0) }
        socket.soTimeout = 1_000
        val token = DiagnosticsShareSecurity.newToken()
        accessToken = token
        expiresAtElapsedMs = SystemClock.elapsedRealtime() + SERVER_LIFETIME_MS
        server = socket
        Thread { serveLoop(socket, file, token) }.apply {
            name = "anilili-diagnostics-http"
            isDaemon = true
            start()
        }
        DiagnosticsLog.event(
            "share",
            "lan_server.started",
            mapOf("port" to socket.localPort, "expiresInMs" to SERVER_LIFETIME_MS),
        )
        "http://${host.hostAddress}:${socket.localPort}${DiagnosticsShareSecurity.path(token)}"
    }.onFailure { DiagnosticsLog.throwable("diagnostics server start failed", it) }

    fun stop() {
        val active = server
        server = null
        accessToken = null
        expiresAtElapsedMs = 0
        if (active != null) {
            runCatching { active.close() }
            DiagnosticsLog.event("share", "lan_server.stopped")
        }
    }

    private fun serveLoop(socket: ServerSocket, file: File, token: String) {
        while (server === socket && !socket.isClosed) {
            if (SystemClock.elapsedRealtime() >= expiresAtElapsedMs) {
                DiagnosticsLog.event("share", "lan_server.expired")
                stop()
                break
            }
            val client = try {
                socket.accept()
            } catch (_: SocketTimeoutException) {
                continue
            } catch (_: Throwable) {
                break
            }
            runCatching { client.use { respond(it, file, token) } }
                .onFailure { DiagnosticsLog.throwable("diagnostics server request failed", it) }
        }
    }

    private fun respond(client: Socket, file: File, token: String) {
        client.soTimeout = 10_000
        val reader = client.getInputStream().bufferedReader()
        val requestLine = reader.readLine().orEmpty()
        var headerCount = 0
        while (headerCount < 64) {
            val line = reader.readLine() ?: break
            if (line.isBlank()) break
            headerCount++
        }
        if (!DiagnosticsShareSecurity.isAuthorized(requestLine, token)) {
            writeResponse(client, "404 Not Found", "text/plain; charset=utf-8", "Not found".toByteArray())
            DiagnosticsLog.event("share", "lan_server.rejected_request")
            return
        }
        writeFileResponse(
            client = client,
            status = "200 OK",
            contentType = "application/zip",
            file = file,
            attachmentName = "Anilili-diagnostics.zip",
        )
        DiagnosticsLog.event("share", "lan_server.report_downloaded", mapOf("bytes" to file.length()))
    }

    private fun writeResponse(
        client: Socket,
        status: String,
        contentType: String,
        body: ByteArray,
        attachmentName: String? = null,
    ) {
        client.getOutputStream().use { out ->
            out.write(responseHeaders(status, contentType, body.size.toLong(), attachmentName))
            out.write(body)
            out.flush()
        }
    }

    private fun writeFileResponse(
        client: Socket,
        status: String,
        contentType: String,
        file: File,
        attachmentName: String,
    ) {
        require(file.isFile) { "Diagnostics report is unavailable" }
        val length = file.length()
        client.getOutputStream().use { out ->
            out.write(responseHeaders(status, contentType, length, attachmentName))
            file.inputStream().buffered().use { input -> input.copyTo(out, DEFAULT_BUFFER_SIZE) }
            out.flush()
        }
    }

    private fun responseHeaders(
        status: String,
        contentType: String,
        contentLength: Long,
        attachmentName: String?,
    ): ByteArray {
        val disposition = attachmentName?.let {
            "Content-Disposition: attachment; filename=\"$it\"\r\n"
        }.orEmpty()
        return buildString {
            append("HTTP/1.1 ").append(status).append("\r\n")
            append("Content-Type: ").append(contentType).append("\r\n")
            append(disposition)
            append("Cache-Control: no-store\r\n")
            append("X-Content-Type-Options: nosniff\r\n")
            append("Content-Length: ").append(contentLength).append("\r\n")
            append("Connection: close\r\n\r\n")
        }.toByteArray()
    }

    private fun localIpv4(): InetAddress? = runCatching {
        NetworkInterface.getNetworkInterfaces().asSequence()
            .filter { runCatching { it.isUp && !it.isLoopback }.getOrDefault(false) }
            .flatMap { it.inetAddresses.asSequence() }
            .filterIsInstance<Inet4Address>()
            .firstOrNull { it.isSiteLocalAddress }
    }.getOrNull()
}

internal object DiagnosticsShareSecurity {
    private val random = SecureRandom()

    fun newToken(): String = ByteArray(16)
        .also(random::nextBytes)
        .joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xff) }

    fun path(token: String): String = "/diagnostics/$token/Anilili-diagnostics.zip"

    fun isAuthorized(requestLine: String, token: String): Boolean {
        val parts = requestLine.split(' ')
        return parts.size >= 3 && parts[0] == "GET" && parts[1] == path(token) &&
            parts[2].startsWith("HTTP/1.")
    }
}
