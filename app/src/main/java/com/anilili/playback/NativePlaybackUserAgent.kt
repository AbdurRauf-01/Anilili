package com.anilili.playback

/**
 * Browser-compatible identity for native Media3 and download HTTP requests.
 *
 * Keep this independent of android.webkit APIs: even the seemingly harmless WebSettings user-agent
 * lookup can start a WebView renderer and reserve more than 100 MB on a low-memory TV.
 */
internal const val NATIVE_PLAYBACK_USER_AGENT =
    "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 " +
        "(KHTML, like Gecko) Chrome/137.0.0.0 Mobile Safari/537.36"
