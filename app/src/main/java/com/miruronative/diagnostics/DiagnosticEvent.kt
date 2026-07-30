package com.miruronative.diagnostics

import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

internal const val DIAGNOSTIC_SCHEMA_VERSION = 1

@Serializable
internal data class DiagnosticEvent(
    val schemaVersion: Int = DIAGNOSTIC_SCHEMA_VERSION,
    val timestampUtc: String,
    val elapsedRealtimeMs: Long,
    val processUptimeMs: Long,
    val sessionId: String,
    val sequence: Long,
    val process: String,
    val pid: Int,
    val thread: String,
    val threadId: Long,
    val level: String,
    val category: String,
    val name: String,
    val message: String? = null,
    val attributes: Map<String, String> = emptyMap(),
    val exception: DiagnosticException? = null,
)

@Serializable
internal data class DiagnosticException(
    val type: String,
    val message: String? = null,
    val stackTrace: String,
)

@Serializable
internal data class DiagnosticManifest(
    val schemaVersion: Int = DIAGNOSTIC_SCHEMA_VERSION,
    val generatedUtc: String,
    val appVersion: String,
    val versionCode: Int,
    val buildType: String,
    val buildSha: String,
    val packageName: String,
    val device: Map<String, String>,
    val diagnostics: Map<String, String>,
)

internal object DiagnosticEventCodec {
    val json = Json {
        encodeDefaults = true
        explicitNulls = false
        ignoreUnknownKeys = true
    }

    fun encode(event: DiagnosticEvent): String = json.encodeToString(event)

    fun encode(manifest: DiagnosticManifest): String = json.encodeToString(manifest)

    fun decode(line: String): DiagnosticEvent = json.decodeFromString(line)

    fun timestampOf(line: String): String? = runCatching {
        json.parseToJsonElement(line).jsonObject["timestampUtc"]?.jsonPrimitive?.content
    }.getOrNull()
}
