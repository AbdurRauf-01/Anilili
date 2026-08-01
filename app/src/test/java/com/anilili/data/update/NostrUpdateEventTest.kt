package com.anilili.data.update

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The fixture event is a real Nostr manifest signed by the project update key
 * (see nostr-update-key.properties / scripts/nostr_update.py) with a fixed
 * created_at so the expected id stays stable.
 */
class NostrUpdateEventTest {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private val fixtureJson = """
        {
          "id": "61ddae93a60885d570184110cf84502678c54d0ea84d319c911abb482dfdd82b",
          "pubkey": "f1abd1ac685aed1f5145271283cd9418bf13aa92ed26399df191ae0c08b22207",
          "created_at": 1754000000,
          "kind": 30078,
          "tags": [["d", "anilili-update"]],
          "content": "{\"version\":\"0.1.54\",\"changelog\":\"Fixture release for unit tests\",\"apkUrl\":\"https://example.com/Anilili.apk\",\"sizeBytes\":12345}",
          "sig": "b0e84789a9862b50e814a1e43a4b8bff34ac2bbb9e5ce0faeb8b06076bcdb41ebbb5e306ee86eac01acb653324bde0c325b90268f6130cd83a403655b3fded94"
        }
    """.trimIndent()

    private fun fixture(): NostrEvent =
        json.parseToJsonElement(fixtureJson).jsonObject.toNostrEvent()

    @Test
    fun eventIdMatchesNip01Serialization() {
        assertEquals(
            "61ddae93a60885d570184110cf84502678c54d0ea84d319c911abb482dfdd82b",
            fixture().computeEventIdHex(),
        )
    }

    @Test
    fun correctlySignedManifestIsAccepted() {
        assertTrue(fixture().isValidManifest())
    }

    @Test
    fun tamperedContentIsRejected() {
        val tampered = fixture().copy(content = fixture().content.replace("0.1.54", "9.9.9"))
        assertFalse(tampered.isValidManifest())
    }

    @Test
    fun tamperedDTagIsRejected() {
        val tags = kotlinx.serialization.json.buildJsonArray {
            add(kotlinx.serialization.json.buildJsonArray {
                add(kotlinx.serialization.json.JsonPrimitive("d"))
                add(kotlinx.serialization.json.JsonPrimitive("other-app"))
            })
        }
        assertFalse(fixture().copy(tags = tags).isValidManifest())
    }

    @Test
    fun foreignPubkeyIsRejected() {
        assertFalse(
            fixture().copy(pubkey = "0".repeat(64)).isValidManifest(),
        )
    }

    @Test
    fun garbageSignatureIsRejected() {
        assertFalse(fixture().copy(sig = "f".repeat(128)).isValidManifest())
    }

    @Test
    fun manifestParsesIntoUpdateInfo() {
        val info = fixture().parseManifest(json)
        assertEquals("0.1.54", info.version)
        assertEquals("Fixture release for unit tests", info.changelog)
        assertEquals("https://example.com/Anilili.apk", info.apkUrl)
        assertEquals(12345L, info.sizeBytes)
    }

    @Test
    fun compareVersionsHandlesNostrVersionFormat() {
        assertTrue(compareAppVersions("0.1.54", "0.1.53") > 0)
        assertEquals(0, compareAppVersions("v0.1.54", "0.1.54"))
    }
}
