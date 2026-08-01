package com.anilili.ui.watch

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * TV builds have no Cast affordance.
 *
 * The device already is the screen, and the mobile implementation depends on `mediarouter`,
 * `play-services-cast-framework` and an AppCompat-derived dialog theme — none of which the TV
 * build carries. Rendering nothing keeps the control bar's layout code identical across flavors.
 */
@Composable
internal fun CastButton(modifier: Modifier = Modifier) = Unit

/** No Cast session exists to move a subtitle track onto. */
internal fun applyCastTextTrack(context: Context, contentId: String?): Boolean = false
