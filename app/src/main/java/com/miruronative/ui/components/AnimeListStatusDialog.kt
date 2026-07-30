package com.miruronative.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayCircleOutline
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.miruronative.data.AppGraph
import com.miruronative.data.auth.AccountService
import com.miruronative.data.library.LibraryStore
import com.miruronative.diagnostics.DiagnosticsLog
import com.miruronative.ui.adaptive.LocalAppDeviceProfile
import com.miruronative.ui.adaptive.focusHighlight
import kotlinx.coroutines.launch

internal data class AnimeListStatusOption(
    val status: String,
    val label: String,
)

/** The five standard destinations shown by both AniList and MyAnimeList. */
internal val animeListStatusOptions = listOf(
    AnimeListStatusOption("CURRENT", "Watching"),
    AnimeListStatusOption("PLANNING", "Plan to Watch"),
    AnimeListStatusOption("COMPLETED", "Completed"),
    AnimeListStatusOption("PAUSED", "On-Hold"),
    AnimeListStatusOption("DROPPED", "Dropped"),
)

/**
 * Player-facing list picker shared by phone, tablet, and TV.
 *
 * AniList and MAL both use the canonical status keys above inside the app. The repository maps
 * them to the active service's API vocabulary and verifies MAL's returned status before this
 * dialog updates local UI state.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AnimeListStatusDialog(
    anilistId: Int,
    title: String,
    episodeLabel: String?,
    onDismiss: () -> Unit,
    onStatusUpdated: (String) -> Unit = {},
) {
    val device = LocalAppDeviceProfile.current
    val service = AccountService.active
    val remoteStatuses by LibraryStore.remoteStatuses.collectAsState()
    val currentStatus = remoteStatuses[anilistId]
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    var busyStatus by remember(anilistId) { mutableStateOf<String?>(null) }
    var errorMessage by remember(anilistId) { mutableStateOf<String?>(null) }

    fun selectStatus(status: String) {
        if (service == null || busyStatus != null || status == currentStatus) return
        busyStatus = status
        errorMessage = null
        DiagnosticsLog.event(
            "Player list status requested service=${service.label} id=$anilistId status=$status",
        )
        scope.launch {
            runCatching { AppGraph.repository.updateAnimeListStatus(anilistId, status) }
                .onSuccess {
                    LibraryStore.updateRemoteStatus(anilistId, status)
                    if (status != "CURRENT" && status != "REPEATING") {
                        LibraryStore.removeHistory(anilistId, dismissRemoteSeed = true)
                    }
                    LibraryStore.refreshRemoteLibrary(force = true)
                    DiagnosticsLog.event(
                        "Player list status confirmed service=${service.label} id=$anilistId status=$status",
                    )
                    onStatusUpdated(status)
                    onDismiss()
                }
                .onFailure { error ->
                    DiagnosticsLog.throwable(
                        "Player list status failed service=${service.label} id=$anilistId status=$status",
                        error,
                    )
                    errorMessage = error.message ?: "Couldn't update ${service.label}"
                    busyStatus = null
                }
        }
    }

    val content: @Composable (Modifier) -> Unit = { modifier ->
        AnimeListStatusContent(
            title = title,
            episodeLabel = episodeLabel,
            serviceLabel = service?.label,
            currentStatus = currentStatus,
            busyStatus = busyStatus,
            errorMessage = errorMessage,
            onSelect = ::selectStatus,
            onClose = onDismiss,
            modifier = modifier,
        )
    }
    val canDismiss = busyStatus == null

    if (device.isTv) {
        Dialog(onDismissRequest = { if (canDismiss) onDismiss() }) {
            content(
                Modifier
                    .widthIn(max = 620.dp)
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(18.dp))
                    .background(MaterialTheme.colorScheme.surface)
                    .border(
                        1.dp,
                        MaterialTheme.colorScheme.outline.copy(alpha = 0.3f),
                        RoundedCornerShape(18.dp),
                    )
                    .padding(24.dp),
            )
        }
    } else {
        ModalBottomSheet(
            onDismissRequest = { if (canDismiss) onDismiss() },
            sheetState = sheetState,
            containerColor = MaterialTheme.colorScheme.surface,
            contentColor = MaterialTheme.colorScheme.onSurface,
        ) {
            content(
                Modifier
                    .navigationBarsPadding()
                    .padding(start = 20.dp, end = 20.dp, bottom = 12.dp),
            )
        }
    }
}

@Composable
private fun AnimeListStatusContent(
    title: String,
    episodeLabel: String?,
    serviceLabel: String?,
    currentStatus: String?,
    busyStatus: String?,
    errorMessage: String?,
    onSelect: (String) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = "Add to My List",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Black,
            )
            Text(
                text = listOfNotNull(title.takeIf(String::isNotBlank), episodeLabel).joinToString(" · "),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = serviceLabel?.let { "Sync with $it" }
                    ?: "Sign in to AniList or MyAnimeList from Profile to use My List.",
                style = MaterialTheme.typography.labelMedium,
                color = if (serviceLabel == null) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    MaterialTheme.colorScheme.primary
                },
            )
        }

        if (serviceLabel != null) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                animeListStatusOptions.chunked(2).forEach { rowOptions ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        rowOptions.forEach { option ->
                            AnimeListStatusCard(
                                option = option,
                                selected = option.status == currentStatus,
                                busy = option.status == busyStatus,
                                enabled = busyStatus == null && option.status != currentStatus,
                                onClick = { onSelect(option.status) },
                                modifier = Modifier.weight(1f),
                            )
                        }
                        repeat(2 - rowOptions.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
            }
        }

        errorMessage?.let { message ->
            Text(
                text = message,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
            )
        }

        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = onClose, enabled = busyStatus == null) { Text("Close") }
        }
    }
}

@Composable
private fun AnimeListStatusCard(
    option: AnimeListStatusOption,
    selected: Boolean,
    busy: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(28.dp)
    val accent = animeListStatusAccent(option.status)
    Row(
        modifier = modifier
            .height(68.dp)
            .focusHighlight(shape)
            .clip(shape)
            .background(
                if (selected) accent.copy(alpha = 0.18f)
                else MaterialTheme.colorScheme.surfaceVariant,
            )
            .border(
                width = if (selected) 2.dp else 1.dp,
                color = if (selected) accent else MaterialTheme.colorScheme.outline.copy(alpha = 0.55f),
                shape = shape,
            )
            .semantics {
                this.selected = selected
                if (selected) stateDescription = "Current status"
            }
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (busy) {
            CircularProgressIndicator(
                modifier = Modifier.size(22.dp),
                strokeWidth = 2.dp,
                color = accent,
            )
        } else {
            Icon(
                imageVector = animeListStatusIcon(option.status),
                contentDescription = null,
                tint = accent,
                modifier = Modifier.size(22.dp),
            )
        }
        Text(
            text = option.label,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = if (selected || busy) FontWeight.Bold else FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
    }
}

private fun animeListStatusIcon(status: String): ImageVector = when (status) {
    "CURRENT" -> Icons.Default.PlayCircleOutline
    "PLANNING" -> Icons.Default.CalendarMonth
    "COMPLETED" -> Icons.Default.Check
    "PAUSED" -> Icons.Default.Pause
    else -> Icons.Default.Close
}

private fun animeListStatusAccent(status: String): Color = when (status) {
    "CURRENT", "COMPLETED" -> Color(0xFF20B865)
    "PLANNING" -> Color(0xFF4D8EE8)
    "PAUSED" -> Color(0xFFF2A92F)
    else -> Color(0xFFEF4D62)
}
