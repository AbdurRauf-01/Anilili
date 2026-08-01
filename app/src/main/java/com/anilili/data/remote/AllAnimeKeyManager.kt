package com.anilili.data.remote

import android.content.Context
import com.anilili.diagnostics.DiagnosticsLog
import com.anilili.util.Base64Compat
import java.util.concurrent.atomic.AtomicReference
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request

internal interface AllAnimeBuildStore {
    fun load(): String
    fun save(value: String)
    fun clear()

    companion object {
        fun memory(): AllAnimeBuildStore = object : AllAnimeBuildStore {
            private val value = AtomicReference("")
            override fun load(): String = value.get()
            override fun save(value: String) { this.value.set(value) }
            override fun clear() { value.set("") }
        }

        fun preferences(context: Context): AllAnimeBuildStore {
            val applicationContext = context.applicationContext
            return object : AllAnimeBuildStore {
                private val preferences by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
                    applicationContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
                }

                override fun load(): String = preferences.getString(BUILD_KEY, "").orEmpty()
                override fun save(value: String) { preferences.edit().putString(BUILD_KEY, value).apply() }
                override fun clear() { preferences.edit().remove(BUILD_KEY).apply() }
            }
        }

        private const val PREFERENCES_NAME = "allanime_protocol"
        private const val BUILD_KEY = "mkissa_client_build"
    }
}

/**
 * Resolves and caches the live MKissa build, authenticated bootstrap, and episode decryption key.
 * Protocol behavior is adapted under Apache-2.0 from yuzono/anime-extensions revision
 * d4d64d315f127b9cf2ae60e2f2d53754ce722c02.
 */
internal class AllAnimeKeyManager(
    private val client: OkHttpClient,
    private val json: Json,
    private val protocol: AllAnimeProtocolVersion,
    private val buildStore: AllAnimeBuildStore = AllAnimeBuildStore.memory(),
    private val nowMs: () -> Long = System::currentTimeMillis,
) {
    data class Material(
        val key: ByteArray,
        val epoch: Long,
        val buildId: String,
        val expiresAtMs: Long,
    )

    private data class Bootstrap(
        val epoch: Long,
        val partB: String,
        val lane: String?,
    )

    private data class BootstrapAttempt(val bootstrap: Bootstrap?, val stale: Boolean)
    private data class BuildMaterial(val build: AllAnimeBundleParser.BuildInfo, val mask: ByteArray)

    @Volatile
    private var cachedMaterial: Material? = null

    @Synchronized
    fun material(forceRefresh: Boolean = false): Material {
        if (!forceRefresh) cachedMaterial?.takeIf { nowMs() < it.expiresAtMs }?.let { return it }
        var buildMaterial = resolveBuildMaterial(forceRefresh)
            ?: error("Unable to obtain MKissa build material")
        var bootstrap = authenticatedBootstrap(buildMaterial)
        if (bootstrap == null && protocol.buildId.isBlank()) {
            // A cached bundle can rotate between launches. Only after its signed bootstrap is
            // rejected do we pay for the live HTML/app/chunk discovery path.
            buildStore.clear()
            buildMaterial = resolveBuildMaterial(forceRefresh = true)
                ?: error("Unable to refresh MKissa build material")
            bootstrap = authenticatedBootstrap(buildMaterial)
        }
        val acceptedBootstrap = bootstrap ?: error("Unable to obtain MKissa crypto material")
        val partB = runCatching { Base64Compat.decode(acceptedBootstrap.partB) }.getOrNull()
            ?: error("MKissa returned invalid key material")
        val key = AllAnimeMkissaCrypto.deriveKey(buildMaterial.mask, partB)
            ?: error("MKissa returned incomplete key material")
        if (buildMaterial.build.seeds.isNotEmpty()) buildStore.save(buildMaterial.build.serialize())
        return Material(
            key = key,
            epoch = acceptedBootstrap.epoch,
            buildId = buildMaterial.build.buildId,
            expiresAtMs = nowMs() + MATERIAL_TTL_MS,
        ).also {
            cachedMaterial = it
            DiagnosticsLog.event("AllAnime MKissa crypto ready build=${it.buildId} epoch=${it.epoch}")
        }
    }

    @Synchronized
    fun invalidate(clearBuild: Boolean = false) {
        cachedMaterial = null
        if (clearBuild) buildStore.clear()
    }

    private fun resolveBuildMaterial(forceRefresh: Boolean): BuildMaterial? {
        pinnedBuild()?.let { return it }
        if (!forceRefresh) cachedBuild()?.let { build ->
            AllAnimeMkissaCrypto.deriveMask(build.buildId, build.seeds)?.let { return BuildMaterial(build, it) }
        }
        val fresh = resolveBuild() ?: return null
        val mask = AllAnimeMkissaCrypto.deriveMask(fresh.buildId, fresh.seeds) ?: return null
        return BuildMaterial(fresh, mask)
    }

    private fun pinnedBuild(): BuildMaterial? {
        if (protocol.buildId.isBlank() || protocol.cryptoMask.isBlank()) return null
        val mask = runCatching { protocol.cryptoMask.chunked(2).map { it.toInt(16).toByte() }.toByteArray() }
            .getOrNull()
            ?: return null
        return BuildMaterial(AllAnimeBundleParser.BuildInfo(protocol.buildId, emptyList()), mask)
    }

    private fun authenticatedBootstrap(material: BuildMaterial): Bootstrap? {
        val first = bootstrap(material.build.buildId, material.mask, AllAnimeMkissaCrypto.epochCandidates(nowMs()))
        first.bootstrap?.let { return it }
        if (!first.stale) return null
        return bootstrap(
            material.build.buildId,
            material.mask,
            AllAnimeMkissaCrypto.skewedEpochCandidates(nowMs()),
        ).bootstrap
    }

    private fun bootstrap(buildId: String, mask: ByteArray, epochs: List<Long>): BootstrapAttempt {
        val siteHost = protocol.siteUrl.toHttpUrl().host
        var stale = false
        epochs.forEach { epoch ->
            val request = Request.Builder()
                .url(protocol.bootstrapUrl(buildId))
                .get()
                .siteHeaders()
                .header("x-build-id", buildId)
                .header(
                    "x-aa-boot",
                    AllAnimeMkissaCrypto.bootToken(
                        mask = mask,
                        buildId = buildId,
                        epoch = epoch,
                        keyGroup = protocol.keyGroup,
                        refererHost = siteHost,
                        lane = protocol.contentLane,
                    ),
                )
                .header("Origin", protocol.siteUrl)
                .header("Referer", "${protocol.siteUrl}/")
                .build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    val errorBody = response.body?.string().orEmpty().take(512)
                    if (response.code == 403 || response.code == 404) {
                        stale = true
                    } else {
                        throw AllAnimeHttpException(response.code, errorBody)
                    }
                    return@use
                }
                val root = runCatching { json.parseToJsonElement(response.body?.string().orEmpty()).jsonObject }
                    .getOrNull()
                    ?: return@use
                val parsed = Bootstrap(
                    epoch = root.long("epoch") ?: return@use,
                    partB = root.string("partB") ?: return@use,
                    lane = root.string("k"),
                )
                if (parsed.lane != null && parsed.lane != protocol.contentLane) return@use
                return BootstrapAttempt(parsed, stale = false)
            }
        }
        return BootstrapAttempt(null, stale)
    }

    private fun cachedBuild(): AllAnimeBundleParser.BuildInfo? {
        val stored = buildStore.load()
        val buildId = stored.substringBefore(FIELD_SEPARATOR, "").takeIf(String::isNotBlank) ?: return null
        val seeds = stored.substringAfter(FIELD_SEPARATOR, "").split(',').filter(String::isNotBlank)
        if (seeds.size != AllAnimeMkissaCrypto.SEED_COUNT) return null
        return AllAnimeBundleParser.BuildInfo(buildId, seeds)
    }

    private fun resolveBuild(): AllAnimeBundleParser.BuildInfo? {
        val html = execute(Request.Builder().url("${protocol.siteUrl}/").get().siteHeaders().build()) ?: return null
        val entry = APP_ENTRY_REGEX.find(html)?.groupValues?.get(1)?.toHttpUrl() ?: return null
        val applicationJs = execute(Request.Builder().url(entry).get().siteHeaders().build()) ?: return null
        val references = CHUNK_REF_REGEX.findAll(applicationJs)
            .map { it.groupValues[1] }
            .distinct()
            .sortedByDescending { it.contains("/chunks/") }
            .take(MAX_BUILD_CHUNKS)
        references.forEach { reference ->
            val url = entry.resolve(reference) ?: return@forEach
            val body = execute(Request.Builder().url(url).get().siteHeaders().build()) ?: return@forEach
            if (!body.contains(CRYPTO_CHUNK_MARKER)) return@forEach
            AllAnimeBundleParser.parse(body)?.let {
                DiagnosticsLog.event("AllAnime MKissa live build discovered build=${it.buildId}")
                return it
            }
        }
        return null
    }

    private fun execute(request: Request): String? = runCatching {
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return null
            response.body?.string()
        }
    }.getOrNull()

    private fun Request.Builder.siteHeaders(): Request.Builder = this
        .header("User-Agent", ALLANIME_USER_AGENT)
        .header("Accept", "text/html,application/xhtml+xml,application/json,*/*")

    private fun JsonObject.string(name: String): String? = (this[name] as? JsonPrimitive)?.contentOrNull
    private fun JsonObject.long(name: String): Long? = (this[name] as? JsonPrimitive)?.longOrNull
    private fun AllAnimeBundleParser.BuildInfo.serialize(): String =
        "$buildId$FIELD_SEPARATOR${seeds.joinToString(",")}"

    companion object {
        private const val MATERIAL_TTL_MS = 6L * 60L * 60L * 1_000L
        private const val FIELD_SEPARATOR = "|"
        private const val MAX_BUILD_CHUNKS = 40
        private const val CRYPTO_CHUNK_MARKER = "aaReq"
        private val APP_ENTRY_REGEX = Regex("""import\("([^"]*/entry/app\.[^"]*\.js)"\)""")
        private val CHUNK_REF_REGEX = Regex("""["'](\.\.?/[\w./-]+\.js)["']""")
    }
}
