package com.anilili.ui.watch

/** What the current episode's skip-intro/outro capability is doing. */
enum class SkipTimingStatus(val playerMessage: String) {
    PROVIDER("Skip timing supplied by this server"),
    WAITING_FOR_DURATION("Skip timing will be checked when playback starts"),
    CHECKING("Checking skip timing for this video…"),
    ANISKIP("Skip timing matched by AniSkip"),
    UNAVAILABLE("Skip timing is unavailable for this episode"),
    SERVICE_ERROR("Skip timing service is temporarily unavailable"),
}
