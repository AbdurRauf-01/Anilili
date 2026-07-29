package com.miruronative.ui.adaptive

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.platform.InspectorInfo

/**
 * A [FocusRequester] that also reports whether anything is currently holding it.
 *
 * The D-pad screens redirect focus across composables that do not share a parent — the top nav
 * hands Down to the home rows, Right to the search box. Those redirects are expressed as
 * `focusProperties { down = requester }`, and Compose resolves them by calling
 * [FocusRequester.requestFocus] from inside `FocusOwnerImpl` while the key event is being
 * dispatched. When no node holds the requester that call throws `IllegalStateException:
 * FocusRequester is not initialized`, and because the throw happens inside the focus owner rather
 * than at our redirect site there is nothing to wrap in `runCatching` — it kills the process.
 *
 * The target screens are only composed on some routes and only after their data loads, so the
 * redirect has to disappear whenever its destination is absent. [isAttached] is what the redirect
 * sites read to decide that; it is plain snapshot state, so reading it during composition rebuilds
 * the modifier chain when a destination appears or goes away.
 */
@Stable
class TvFocusTarget {
    val requester = FocusRequester()

    // A count rather than a flag: during a navigation transition the outgoing and incoming copies
    // of a screen are both composed for a frame, and a flag would land on whichever of the two
    // callbacks ran last instead of on whether anything still holds the requester.
    private var holders by mutableStateOf(0)

    /** True while at least one composable is applying [tvFocusTarget] for this target. */
    val isAttached: Boolean get() = holders > 0

    internal fun onAttach() {
        holders++
    }

    internal fun onDetach() {
        holders--
    }
}

@Composable
fun rememberTvFocusTarget(): TvFocusTarget = remember { TvFocusTarget() }

/**
 * Attaches [target]'s requester here and tracks that attachment for the redirect sites.
 *
 * Null is accepted so phone layouts can pass nothing through the same parameter.
 */
fun Modifier.tvFocusTarget(target: TvFocusTarget?): Modifier =
    if (target == null) this else this
        .focusRequester(target.requester)
        .then(TvFocusTargetElement(target))

/**
 * Runs [redirect] only when [target] can actually receive focus.
 *
 * Sugar for the guard every redirect site needs, so that a new call site cannot forget it.
 */
inline fun tvFocusRedirect(target: TvFocusTarget?, redirect: (FocusRequester) -> Unit) {
    if (target != null && target.isAttached) redirect(target.requester)
}

private data class TvFocusTargetElement(
    val target: TvFocusTarget,
) : ModifierNodeElement<TvFocusTargetNode>() {
    override fun create() = TvFocusTargetNode(target)

    override fun update(node: TvFocusTargetNode) {
        node.setTarget(target)
    }

    override fun InspectorInfo.inspectableProperties() {
        name = "tvFocusTarget"
        properties["target"] = target
    }
}

private class TvFocusTargetNode(private var target: TvFocusTarget) : Modifier.Node() {
    override fun onAttach() {
        target.onAttach()
    }

    override fun onDetach() {
        target.onDetach()
    }

    fun setTarget(next: TvFocusTarget) {
        if (next === target) return
        // The chain swapped destinations underneath us; the old one is no longer held here.
        if (isAttached) {
            target.onDetach()
            next.onAttach()
        }
        target = next
    }
}
