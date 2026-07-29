package com.miruronative.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.material.ExperimentalMaterialApi
import androidx.compose.material.pullrefresh.PullRefreshIndicator
import androidx.compose.material.pullrefresh.pullRefresh
import androidx.compose.material.pullrefresh.rememberPullRefreshState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.miruronative.ui.adaptive.LocalAppDeviceProfile

/**
 * Touch pull-to-refresh with an automatic Material indicator; TV keeps normal D-pad scrolling.
 *
 * This deliberately uses Compose Material's nested-scroll implementation instead of Material 3's
 * `PullToRefreshBox`. Material 3 1.3.1 can finish an animation after its modifier node has detached
 * and then crash while reading `LocalDensity`. The Material implementation is on the
 * same Compose BOM as the rest of the app and does not read a CompositionLocal from a modifier node.
 */
@OptIn(ExperimentalMaterialApi::class)
@Composable
fun PullRefreshContainer(
    isRefreshing: Boolean,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    if (LocalAppDeviceProfile.current.isTv) {
        Box(modifier, content = content)
    } else {
        val state = rememberPullRefreshState(isRefreshing, onRefresh)
        Box(modifier.pullRefresh(state)) {
            content()
            PullRefreshIndicator(
                refreshing = isRefreshing,
                state = state,
                modifier = Modifier.align(Alignment.TopCenter),
                backgroundColor = MaterialTheme.colorScheme.surface,
                contentColor = MaterialTheme.colorScheme.primary,
            )
        }
    }
}
