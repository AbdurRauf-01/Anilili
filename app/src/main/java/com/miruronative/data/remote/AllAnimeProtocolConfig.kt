package com.miruronative.data.remote

internal const val ALLANIME_USER_AGENT =
    "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"

/** Current MKissa route plus optional pinned crypto material used by deterministic tests. */
internal data class AllAnimeProtocolVersion(
    val version: String,
    val buildId: String,
    val currentSourcesHash: String,
    val legacySourcesHash: String,
    val cryptoMask: String,
    val currentApiOrigin: String,
    val legacyApi: String,
    val apiReferer: String,
    val apiOrigin: String,
    val playerReferer: String,
    val siteUrl: String = "https://mkissa.to",
    val contentLane: String = "k7",
    val keyGroup: String = "mkissa",
) {
    val currentApi: String get() = "$currentApiOrigin/api"
    val currentReferer: String get() = "$siteUrl/anime/"
    fun bootstrapUrl(build: String): String =
        "$currentApiOrigin/client-crypto/v1/bootstrap?buildId=$build&k=$contentLane"
}

internal object AllAnimeProtocolConfig {
    val active = AllAnimeProtocolVersion(
        version = "mkissa-dynamic-v1",
        // The live site rotates both values on rebuild. Empty means discover them from its bundle.
        buildId = "",
        currentSourcesHash = "f4662f4b7510b26795dd53ef824a0bf1740fbbc5d1273fab18222ac831bca8d0",
        legacySourcesHash = "d405d0edd690624b66baba3068e0edc3ac90f1597d898a1ec8db4e5c43c00fec",
        cryptoMask = "",
        currentApiOrigin = "https://api.mkissa.net",
        legacyApi = "https://api.allanime.day/api",
        apiReferer = "https://youtu-chan.com/",
        apiOrigin = "https://youtu-chan.com",
        playerReferer = "https://allanime.day/",
    )
}
