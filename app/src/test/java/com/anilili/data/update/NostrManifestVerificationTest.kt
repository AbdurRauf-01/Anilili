package com.anilili.data.update

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Verifies the Nostr fallback against a real, live-signed manifest event.
 *
 * The event below was captured from wss://nos.lol on 2026-08-01 — the actual 0.1.54 release
 * manifest, with its real BIP-340 signature. It exercises the whole chain the app runs:
 * NIP-01 id re-serialization, the d-tag check, and the secp256k1 Schnorr verification.
 *
 * This is the test that would have caught the release being silently broken: R8 had shrunk the
 * secp256k1 JNI backend away, `Secp256k1.verifySchnorr` threw ExceptionInInitializerError, and
 * `runCatching { ... }.getOrDefault(false)` turned that into "signature invalid" — so every
 * release build rejected every manifest and the channel was inert.
 */
class NostrManifestVerificationTest {

    private val json = Json { ignoreUnknownKeys = true }
    private val liveEventJson = """{"content":"{\"version\":\"0.1.54\",\"changelog\":\"Grouped downloads by series, pinned caption appearance preview, robust stream resolution fallback for embeds & HLS, and cold-start recovery.\",\"apkUrl\":\"https://github.com/kompoti121/Anilili/releases/latest/download/Anilili.apk\",\"sizeBytes\":15022786}","created_at":1785533738,"id":"5a06c71dc1b40ae21b0b32b9f958627c0f305b55c6b44fd639454b209e95933d","kind":30078,"pubkey":"f1abd1ac685aed1f5145271283cd9418bf13aa92ed26399df191ae0c08b22207","sig":"fe9c7200df30a27345f55c3f6485be84a565bbf4b9c68231c1eb378ec48fd77c9631513c144931b832d0dd75a54bc99b70ffb322adbb6dc24f8d7cfa1110b94e","tags":[["d","anilili-update"]]}"""

    private fun event() = json.parseToJsonElement(liveEventJson).jsonObject.toNostrEvent()

    @Test
    fun `the published manifest event passes full NIP-01 verification`() {
        val event = event()
        assertEquals(NostrUpdateSource.MANIFEST_PUBKEY_HEX, event.pubkey)
        assertEquals(NostrUpdateSource.MANIFEST_KIND, event.kind)
        // Re-serializing the event exactly as NIP-01 specifies must reproduce its id.
        assertEquals(event.id, event.computeEventIdHex())
        // ...and the Schnorr signature must check out against the project key.
        assertTrue("live manifest must verify", event.isValidManifest())
    }

    @Test
    fun `the manifest parses into an installable update`() {
        val info = event().parseManifest(json)
        assertEquals("0.1.54", info.version)
        assertTrue("apk must be fetched over TLS", info.apkUrl.startsWith("https://"))
        assertTrue("size should be populated", info.sizeBytes > 0)
    }

    /** Manifests published before the flavor split have no variants; a TV must still update. */
    @Test
    fun `a legacy manifest still gives a TV something to install`() {
        val info = event().parseManifest(json, isTv = true)
        assertEquals("0.1.54", info.version)
        assertTrue("falls back to the single apkUrl", info.apkUrl.endsWith("Anilili.apk"))
    }

    @Test
    fun `each form factor takes its own variant when the manifest offers them`() {
        val withVariants = event().copy(
            content = """
                {"version":"0.1.56","apkUrl":"https://example.test/Anilili.apk","sizeBytes":11,
                 "variants":{
                   "mobile":{"url":"https://example.test/Anilili.apk","sizeBytes":11},
                   "tv":{"url":"https://example.test/Anilili_tv.apk","sizeBytes":22}}}
            """.trimIndent(),
        )
        val tv = withVariants.parseManifest(json, isTv = true)
        assertEquals("https://example.test/Anilili_tv.apk", tv.apkUrl)
        assertEquals(22L, tv.sizeBytes)

        val phone = withVariants.parseManifest(json, isTv = false)
        assertEquals("https://example.test/Anilili.apk", phone.apkUrl)
        assertEquals(11L, phone.sizeBytes)
    }

    @Test
    fun `a manifest with only a mobile variant does not strand a TV`() {
        val mobileOnly = event().copy(
            content = """
                {"version":"0.1.56","apkUrl":"https://example.test/Anilili.apk",
                 "variants":{"mobile":{"url":"https://example.test/Anilili.apk"}}}
            """.trimIndent(),
        )
        assertEquals(
            "https://example.test/Anilili.apk",
            mobileOnly.parseManifest(json, isTv = true).apkUrl,
        )
    }

    @Test
    fun `a tampered manifest is rejected`() {
        // Same signature, different content: the id no longer matches, so it must not be trusted.
        val forged = event().copy(content = event().content.replace("0.1.54", "9.9.9"))
        assertFalse("content tampering must fail verification", forged.isValidManifest())
    }

    @Test
    fun `an event from another key is rejected`() {
        val impostor = event().copy(pubkey = "0".repeat(64))
        assertFalse("only the project key is trusted", impostor.isValidManifest())
    }
}
