package com.anilili.ui.watch

import android.content.Context
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.appcompat.view.ContextThemeWrapper
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import android.os.Bundle
import androidx.mediarouter.app.MediaRouteChooserDialog
import androidx.mediarouter.app.MediaRouteChooserDialogFragment
import androidx.mediarouter.app.MediaRouteControllerDialog
import androidx.mediarouter.app.MediaRouteControllerDialogFragment
import androidx.mediarouter.app.MediaRouteDialogFactory
import androidx.mediarouter.app.MediaRouteButton
import androidx.mediarouter.media.MediaRouter
import com.google.android.gms.cast.framework.CastButtonFactory
import com.google.android.gms.cast.framework.CastContext
import com.anilili.R
import com.anilili.diagnostics.DiagnosticsLog

/**
 * The Cast button as a Compose element. Media3's own controller is off, so we inflate the
 * MediaRouteButton ourselves (with the AppCompat-derived theme its dialogs require) and place it
 * in the custom control bar.
 *
 * Two behaviors behind one icon:
 * - While Chromecast endpoints exist on the network, this is the real MediaRouteButton with its
 *   chooser/controller dialogs. A discovery callback keeps scanning active the whole time the
 *   control bar is visible — without it, MediaRouter only scans passively and nearby devices
 *   can take a long time (or forever) to appear in the chooser. A long press still opens Android's
 *   screen-mirroring settings, so discovering one Chromecast never hides the Miracast/OEM route.
 * - When there are none — most TVs only speak the system's Miracast/"Smart View" cast, not
 *   Google Cast — the icon opens the phone's native cast picker instead of a dead chooser, so
 *   the user can mirror the whole screen to the TVs their phone already finds.
 */
@OptIn(UnstableApi::class)
@Composable
internal fun CastButton(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val router = remember(context) { runCatching { MediaRouter.getInstance(context) }.getOrNull() }
    val selector = remember(context) {
        runCatching { CastContext.getSharedInstance(context).mergedSelector }.getOrNull()
    }
    var castRoutesAvailable by remember { mutableStateOf(false) }

    DisposableEffect(router, selector) {
        if (router == null || selector == null) {
            castRoutesAvailable = false
            onDispose { }
        } else {
            fun refresh() {
                castRoutesAvailable = router.isRouteAvailable(
                    selector,
                    MediaRouter.AVAILABILITY_FLAG_IGNORE_DEFAULT_ROUTE,
                )
            }
            val callback = object : MediaRouter.Callback() {
                override fun onRouteAdded(router: MediaRouter, route: MediaRouter.RouteInfo) = refresh()
                override fun onRouteRemoved(router: MediaRouter, route: MediaRouter.RouteInfo) = refresh()
                override fun onRouteChanged(router: MediaRouter, route: MediaRouter.RouteInfo) = refresh()
            }
            router.addCallback(selector, callback, MediaRouter.CALLBACK_FLAG_REQUEST_DISCOVERY)
            refresh()
            onDispose { router.removeCallback(callback) }
        }
    }

    if (castRoutesAvailable) {
        AndroidView(
            modifier = modifier,
            factory = { ctx ->
                val themed = ContextThemeWrapper(ctx, R.style.Theme_MiruroNative_MediaRouter)
                val button = MediaRouteButton(themed)
                button.setDialogFactory(ThemedMediaRouteDialogFactory())
                runCatching { CastButtonFactory.setUpMediaRouteButton(ctx, button) }
                button.setOnLongClickListener {
                    openSystemCastPicker(context)
                    true
                }
                button
            },
        )
    } else {
        androidx.compose.material3.IconButton(
            onClick = { openSystemCastPicker(context) },
            modifier = modifier,
        ) {
            androidx.compose.material3.Icon(
                Icons.Default.Cast,
                contentDescription = "Mirror screen to TV",
                tint = Color.White,
            )
        }
    }
}

/** The phone's native cast/mirror picker; OEMs hang it off different settings actions. */
private fun openSystemCastPicker(context: Context) {
    val candidates = listOf(
        android.content.Intent(android.provider.Settings.ACTION_CAST_SETTINGS),
        android.content.Intent("android.settings.WIFI_DISPLAY_SETTINGS"),
        android.content.Intent(android.provider.Settings.ACTION_WIRELESS_SETTINGS),
    )
    for (intent in candidates) {
        if (runCatching { context.startActivity(intent) }.isSuccess) return
    }
    DiagnosticsLog.event("CastButton no system cast settings activity found")
}

/** Media3 1.8.x exposes Cast tracks but does not implement setTrackSelectionParameters. */
internal fun applyCastTextTrack(context: Context, contentId: String?): Boolean {
    val client = runCatching {
        CastContext.getSharedInstance(context).sessionManager.currentCastSession?.remoteMediaClient
    }.getOrNull() ?: return false
    val mediaTracks = client.mediaInfo?.mediaTracks.orEmpty()
    val selectedTrackId = contentId?.let { selectedContentId ->
        mediaTracks.firstOrNull {
            it.type == com.google.android.gms.cast.MediaTrack.TYPE_TEXT && it.contentId == selectedContentId
        }?.id ?: return false
    }
    val activeIds: LongArray = client.mediaStatus?.activeTrackIds ?: longArrayOf()
    val retainedIds: List<Long> = activeIds.filter { activeId ->
        mediaTracks.firstOrNull { it.id == activeId }?.type != com.google.android.gms.cast.MediaTrack.TYPE_TEXT
    }
    val requestedIds = (retainedIds + listOfNotNull(selectedTrackId)).toLongArray()
    client.setActiveMediaTracks(requestedIds).setResultCallback { result ->
        if (!result.status.isSuccess) {
            DiagnosticsLog.event("PlayerSurface Cast subtitle selection failed status=${result.status.statusCode}")
        }
    }
    return true
}

/**
 * The route chooser/controller dialog fragments create their dialogs from the hosting activity's
 * context; wrap it with the AppCompat-derived theme the same way as the button. Named (non-private)
 * fragment classes so the FragmentManager can re-instantiate them after configuration changes.
 */
internal class ThemedMediaRouteChooserDialogFragment : MediaRouteChooserDialogFragment() {
    override fun onCreateChooserDialog(context: Context, savedInstanceState: Bundle?): MediaRouteChooserDialog =
        super.onCreateChooserDialog(
            ContextThemeWrapper(context, R.style.Theme_MiruroNative_MediaRouter),
            savedInstanceState,
        )
}

internal class ThemedMediaRouteControllerDialogFragment : MediaRouteControllerDialogFragment() {
    override fun onCreateControllerDialog(context: Context, savedInstanceState: Bundle?): MediaRouteControllerDialog =
        super.onCreateControllerDialog(
            ContextThemeWrapper(context, R.style.Theme_MiruroNative_MediaRouter),
            savedInstanceState,
        )
}

private class ThemedMediaRouteDialogFactory : MediaRouteDialogFactory() {
    override fun onCreateChooserDialogFragment(): MediaRouteChooserDialogFragment =
        ThemedMediaRouteChooserDialogFragment()

    override fun onCreateControllerDialogFragment(): MediaRouteControllerDialogFragment =
        ThemedMediaRouteControllerDialogFragment()
}

