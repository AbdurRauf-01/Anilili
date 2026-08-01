package com.anilili.data.update

import org.junit.Assert.assertEquals
import org.junit.Test

class UpdateApkSelectionTest {
    private val assets = listOf(
        "Anilili-armeabi-v7a.apk",
        "Anilili.apk",
        "Anilili-arm64-v8a.apk",
    )

    /** Releases published before v0.1.34 used all-lowercase asset names. */
    private val legacyAssets = listOf(
        "anilili-armeabi-v7a.apk",
        "anilili.apk",
        "anilili-arm64-v8a.apk",
    )

    @Test
    fun arm64DeviceGetsArm64Split() {
        assertEquals(
            "Anilili-arm64-v8a.apk",
            preferredReleaseApkName(assets, listOf("arm64-v8a", "armeabi-v7a")),
        )
    }

    @Test
    fun armV7DeviceGetsArmV7Split() {
        assertEquals(
            "Anilili-armeabi-v7a.apk",
            preferredReleaseApkName(assets, listOf("armeabi-v7a")),
        )
    }

    @Test
    fun unknownAbiFallsBackToUniversal() {
        assertEquals("Anilili.apk", preferredReleaseApkName(assets, listOf("x86_64")))
    }

    /** Asset names since the underscore rename that keeps Anilili.apk first alphabetically. */
    private val underscoreAssets = listOf(
        "Anilili.apk",
        "Anilili_arm64-v8a.apk",
        "Anilili_armeabi-v7a.apk",
    )

    @Test
    fun underscoreNamedSplitsResolvePerAbi() {
        assertEquals(
            "Anilili_arm64-v8a.apk",
            preferredReleaseApkName(underscoreAssets, listOf("arm64-v8a", "armeabi-v7a")),
        )
        assertEquals(
            "Anilili_armeabi-v7a.apk",
            preferredReleaseApkName(underscoreAssets, listOf("armeabi-v7a")),
        )
        assertEquals("Anilili.apk", preferredReleaseApkName(underscoreAssets, listOf("x86_64")))
    }

    @Test
    fun legacyLowercaseAssetsStillResolve() {
        assertEquals(
            "anilili-arm64-v8a.apk",
            preferredReleaseApkName(legacyAssets, listOf("arm64-v8a")),
        )
        assertEquals("anilili.apk", preferredReleaseApkName(legacyAssets, listOf("x86_64")))
    }

    /** A release published after the mobile/TV flavor split carries both families. */
    private val splitFormFactorAssets = listOf(
        "Anilili.apk",
        "Anilili_arm64-v8a.apk",
        "Anilili_armeabi-v7a.apk",
        "Anilili_tv.apk",
        "Anilili_tv_arm64-v8a.apk",
        "Anilili_tv_armeabi-v7a.apk",
    )

    @Test
    fun phoneNeverTakesATvAsset() {
        assertEquals(
            "Anilili_arm64-v8a.apk",
            preferredReleaseApkName(splitFormFactorAssets, listOf("arm64-v8a"), isTv = false),
        )
        assertEquals(
            "Anilili.apk",
            preferredReleaseApkName(splitFormFactorAssets, listOf("x86_64"), isTv = false),
        )
    }

    @Test
    fun tvTakesTheTvAsset() {
        assertEquals(
            "Anilili_tv_armeabi-v7a.apk",
            preferredReleaseApkName(splitFormFactorAssets, listOf("armeabi-v7a"), isTv = true),
        )
        assertEquals(
            "Anilili_tv.apk",
            preferredReleaseApkName(splitFormFactorAssets, listOf("x86"), isTv = true),
        )
    }

    /** Releases from before the split have no TV family; a TV must still be able to update. */
    @Test
    fun tvFallsBackToTheOnlyFamilyAPreSplitReleaseHas() {
        assertEquals(
            "Anilili_arm64-v8a.apk",
            preferredReleaseApkName(underscoreAssets, listOf("arm64-v8a"), isTv = true),
        )
    }

    /** The legacy "first .apk by name" updaters must still land on the universal phone build. */
    @Test
    fun universalPhoneAssetStillSortsFirst() {
        assertEquals("Anilili.apk", splitFormFactorAssets.sorted().first())
    }

    @Test
    fun versionComparisonDistinguishesNewerEqualAndOlderReleases() {
        assertEquals(1, compareAppVersions(remote = "0.1.39", installed = "0.1.38"))
        assertEquals(0, compareAppVersions(remote = "0.1.38", installed = "0.1.38"))
        assertEquals(-1, compareAppVersions(remote = "0.1.37", installed = "0.1.38"))
        assertEquals(0, compareAppVersions(remote = "0.1.38.0", installed = "0.1.38"))
        assertEquals(0, compareAppVersions(remote = "v0.1.38", installed = "0.1.38-debug"))
    }
}
