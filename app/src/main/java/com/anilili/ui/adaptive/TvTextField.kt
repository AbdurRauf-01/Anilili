package com.anilili.ui.adaptive

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.anilili.diagnostics.DiagnosticsLog

enum class TvTextInputType {
    TEXT,
    EMAIL,
    PASSWORD,
    NUMBER,
}

/**
 * A real Android [EditText] hosted inside Compose for TV.
 *
 * Fire TV's IME can acknowledge a show request without ever attaching to a Compose text input.
 * A native editor provides the platform input connection expected by Fire TV IME, TalkBack, and
 * phone-remote keyboards. D-pad focus alone does not summon the keyboard; Select/Enter/Button A
 * explicitly begins editing.
 */
@Composable
fun TvNativeTextField(
    value: String,
    onValueChange: (String) -> Unit,
    hint: String,
    modifier: Modifier = Modifier,
    inputType: TvTextInputType = TvTextInputType.TEXT,
    imeAction: Int = EditorInfo.IME_ACTION_DONE,
    onImeAction: () -> Unit = {},
    onMoveDown: (() -> Unit)? = null,
    onMoveRight: (() -> Unit)? = null,
    tvFocusTarget: TvFocusTarget? = null,
    focusable: Boolean = true,
) {
    val currentOnValueChange by rememberUpdatedState(onValueChange)
    val currentOnImeAction by rememberUpdatedState(onImeAction)
    val currentOnMoveDown by rememberUpdatedState(onMoveDown)
    val currentOnMoveRight by rememberUpdatedState(onMoveRight)
    val textColor = MaterialTheme.colorScheme.onSurface.toArgb()
    val hintColor = MaterialTheme.colorScheme.onSurfaceVariant.toArgb()
    val surfaceColor = MaterialTheme.colorScheme.surface.toArgb()
    val outlineColor = MaterialTheme.colorScheme.outline.toArgb()
    val focusedOutlineColor = MaterialTheme.colorScheme.primary.toArgb()
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(10.dp)

    Box(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clip(shape)
            .background(androidx.compose.ui.graphics.Color(surfaceColor))
            .border(
                width = if (focused) 2.dp else 1.dp,
                color = androidx.compose.ui.graphics.Color(
                    if (focused) focusedOutlineColor else outlineColor,
                ),
                shape = shape,
            ),
    ) {
        AndroidView(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 56.dp)
                .tvFocusTarget(tvFocusTarget),
            factory = { context ->
                SafeTvEditText(context).apply {
                    tag = NativeTvTextWatcher(this, currentOnValueChange).also(::addTextChangedListener)
                    setText(value)
                    setSelection(value.length)
                    setSingleLine(true)
                    this.hint = hint
                    setTextColor(textColor)
                    setHintTextColor(hintColor)
                    textSize = 18f
                    background = ColorDrawable(Color.TRANSPARENT)
                    val horizontal = (16 * resources.displayMetrics.density).toInt()
                    setPadding(horizontal, 0, horizontal, 0)
                    this.inputType = inputType.androidValue
                    this.imeOptions = imeAction
                    isFocusable = focusable
                    isFocusableInTouchMode = focusable
                    showSoftInputOnFocus = false
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
                    disableSelectionActionMode()

                    setOnFocusChangeListener { _, hasFocus ->
                        focused = hasFocus
                        if (!hasFocus) showSoftInputOnFocus = false
                    }
                    setOnClickListener { beginNativeTvEditing(this) }
                    setOnKeyListener { _, keyCode, event ->
                        when {
                            event.action == KeyEvent.ACTION_DOWN && keyCode in TV_SELECT_KEYS -> {
                                beginNativeTvEditing(this)
                                true
                            }
                            event.action == KeyEvent.ACTION_DOWN &&
                                keyCode == KeyEvent.KEYCODE_DPAD_DOWN &&
                                currentOnMoveDown != null -> {
                                hideNativeTvKeyboard(this)
                                currentOnMoveDown?.invoke()
                                true
                            }
                            event.action == KeyEvent.ACTION_DOWN &&
                                keyCode == KeyEvent.KEYCODE_DPAD_RIGHT &&
                                currentOnMoveRight != null &&
                                selectionStart == text.length &&
                                selectionEnd == text.length -> {
                                // A native EditText consumes Right to move its cursor before
                                // Compose can run spatial focus search. Screens with a control to
                                // the field's right can provide an escape once the caret is at the
                                // end, while retaining normal cursor movement within the query.
                                hideNativeTvKeyboard(this)
                                currentOnMoveRight?.invoke()
                                true
                            }
                            else -> false
                        }
                    }
                    setOnEditorActionListener { _, actionId, event ->
                        val requestedAction = actionId == imeAction ||
                            actionId == EditorInfo.IME_ACTION_DONE ||
                            actionId == EditorInfo.IME_ACTION_SEARCH ||
                            actionId == EditorInfo.IME_ACTION_NEXT
                        val enterKey = event?.action == KeyEvent.ACTION_DOWN &&
                            event.keyCode in TV_SELECT_KEYS
                        if (requestedAction || enterKey) {
                            hideNativeTvKeyboard(this)
                            currentOnImeAction()
                            true
                        } else {
                            false
                        }
                    }
                }
            },
            update = { editor ->
                // Every assignment here is guarded, because this block runs on each recomposition
                // and TextView setters are not free. `setInputType` in particular calls
                // InputMethodManager.restartInput(), which rebuilds the input connection and asks
                // hasEditorInFocusSearchDirection() — that lands in Compose's two-dimensional focus
                // search, which forces the results LazyGrid to lay out beyond its visible bounds.
                // Re-running that per keystroke starved the main thread until Android killed the
                // app for not responding to input: the "typing does nothing, then it closes"
                // report. Reproduced on an Android TV emulator; the phone path never hits it
                // because it uses a Compose text field with no input-connection probe.
                if (editor.hint != hint) editor.hint = hint
                if (editor.currentTextColor != textColor) editor.setTextColor(textColor)
                if (editor.currentHintTextColor != hintColor) editor.setHintTextColor(hintColor)
                if (editor.inputType != inputType.androidValue) editor.inputType = inputType.androidValue
                if (editor.imeOptions != imeAction) editor.imeOptions = imeAction
                if (editor.isFocusable != focusable) editor.isFocusable = focusable
                if (editor.isFocusableInTouchMode != focusable) editor.isFocusableInTouchMode = focusable
                val watcher = editor.tag as? NativeTvTextWatcher
                watcher?.onValueChange = currentOnValueChange
                val echoes = watcher?.pendingEchoes
                val echoIndex = echoes?.indexOf(value) ?: -1
                when {
                    // This value is the field's own keystroke coming back. The editor has already
                    // moved past it, so writing it would delete whatever was typed since.
                    echoIndex >= 0 -> repeat(echoIndex + 1) { echoes?.removeFirst() }
                    // A genuine external change: a picked history term, a reset, a restored query.
                    editor.text.toString() != value -> {
                        echoes?.clear()
                        val selection = editor.selectionStart.coerceAtLeast(0)
                        editor.setText(value)
                        editor.setSelection(selection.coerceAtMost(value.length))
                    }
                }
            },
        )
    }
}

private class NativeTvTextWatcher(
    private val editor: EditText,
    var onValueChange: (String) -> Unit,
) : TextWatcher {
    /**
     * Values this field has pushed up that have not yet come back down through recomposition.
     *
     * Compose delivers state a frame or more later, so a second keystroke can land in the EditText
     * before the first one's value arrives in `update`. Writing that stale value back would undo
     * the newer keystroke — typing "Naruto" on a TV landed as "Nuo". Anything still queued here is
     * an echo of the user's own typing and must not be written back.
     */
    val pendingEchoes = ArrayDeque<String>()

    override fun beforeTextChanged(text: CharSequence?, start: Int, count: Int, after: Int) = Unit
    override fun onTextChanged(text: CharSequence?, start: Int, before: Int, count: Int) = Unit
    override fun afterTextChanged(text: Editable?) {
        val value = text?.toString().orEmpty()
        pendingEchoes.addLast(value)
        // A value the caller silently rewrites never comes back to clear its entry, so cap the
        // queue rather than letting a long session grow it without bound.
        while (pendingEchoes.size > MAX_PENDING_ECHOES) pendingEchoes.removeFirst()
        onValueChange(value)
        editor.postInvalidate()
    }

    private companion object {
        const val MAX_PENDING_ECHOES = 32
    }
}

private fun beginNativeTvEditing(editor: EditText) {
    editor.showSoftInputOnFocus = true
    editor.requestFocus()
    editor.setSelection(editor.text.length)
    val manager = editor.context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
    if (manager == null) {
        DiagnosticsLog.event("TvNativeTextField no InputMethodManager")
        return
    }
    editor.post {
        val accepted = manager.showSoftInput(editor, InputMethodManager.SHOW_IMPLICIT)
        DiagnosticsLog.event(
            "TvNativeTextField show accepted=$accepted ime=${manager.enabledInputMethodList.joinToString { it.id }}",
        )
        editor.postDelayed({
            if (!editor.isAttachedToWindow || editor.isImeVisible()) return@postDelayed
            @Suppress("DEPRECATION")
            val forced = manager.showSoftInput(editor, InputMethodManager.SHOW_FORCED)
            DiagnosticsLog.event("TvNativeTextField forced show accepted=$forced")
        }, 250)
    }
}

/**
 * Turns off the floating text-selection toolbar and its smart-selection classifier.
 *
 * A TV has no cursor to drag a selection with, so the toolbar is unreachable anyway — but the
 * platform still kicks off `SelectionActionModeHelper`'s async TextClassification task, and when
 * its result lands after the view has been re-laid-out the framework dereferences a null
 * `Layout`: `NullPointerException … Layout.getSelection` in
 * `convertSelectionToRectangles`, a fatal crash inside android.widget with no app frame to fix.
 * Seen on an Android TV 14 device. Removing the feature removes the task that crashes.
 */
private fun EditText.disableSelectionActionMode() {
    // Deliberately NOT setTextIsSelectable(false): on an EditText that also clears the movement
    // method, which breaks the caret and typing outright.
    isLongClickable = false
    val refuse = object : android.view.ActionMode.Callback {
        override fun onCreateActionMode(mode: android.view.ActionMode?, menu: android.view.Menu?) = false
        override fun onPrepareActionMode(mode: android.view.ActionMode?, menu: android.view.Menu?) = false
        override fun onActionItemClicked(mode: android.view.ActionMode?, item: android.view.MenuItem?) = false
        override fun onDestroyActionMode(mode: android.view.ActionMode?) = Unit
    }
    customSelectionActionModeCallback = refuse
    customInsertionActionModeCallback = refuse
    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
        setTextClassifier(android.view.textclassifier.TextClassifier.NO_OP)
    }
}

private fun hideNativeTvKeyboard(editor: EditText) {
    val manager = editor.context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
    manager?.hideSoftInputFromWindow(editor.windowToken, 0)
    editor.showSoftInputOnFocus = false
}

private fun View.isImeVisible(): Boolean =
    ViewCompat.getRootWindowInsets(this)?.isVisible(WindowInsetsCompat.Type.ime()) == true

private val TvTextInputType.androidValue: Int
    get() = when (this) {
        TvTextInputType.TEXT -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        TvTextInputType.EMAIL -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
        TvTextInputType.PASSWORD ->
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        TvTextInputType.NUMBER -> InputType.TYPE_CLASS_NUMBER
    }

private val TV_SELECT_KEYS = setOf(
    KeyEvent.KEYCODE_DPAD_CENTER,
    KeyEvent.KEYCODE_ENTER,
    KeyEvent.KEYCODE_NUMPAD_ENTER,
    KeyEvent.KEYCODE_BUTTON_A,
    KeyEvent.KEYCODE_BUTTON_SELECT,
)

/**
 * An [EditText] that guards against Compose focus search race conditions on Fire TV / Android 9.
 *
 * When an on-screen keyboard (IME) opens or requests input connection, Android's native
 * [TextView.onCreateInputConnection] calls [focusSearch]. If Compose focus state is currently
 * in transition, [super.focusSearch] can throw `IllegalStateException: ActiveParent must have a focusedChild`.
 */
private class SafeTvEditText(context: Context) : EditText(context) {
    /**
     * True only while the IME is building an input connection.
     *
     * `TextView.onCreateInputConnection` calls `hasEditorInFocusSearchDirection`, which runs a
     * focus search purely to decide whether the keyboard should offer a "next field" action. Here
     * that search crosses into Compose and forces the surrounding lazy grid through a
     * beyond-bounds layout pass — a whole screen of work to answer a cosmetic question. The
     * screens using this field set `imeOptions` explicitly, so the answer is not wanted anyway.
     */
    private var buildingInputConnection = false

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection? {
        buildingInputConnection = true
        return try {
            super.onCreateInputConnection(outAttrs)
        } finally {
            buildingInputConnection = false
        }
    }

    override fun focusSearch(direction: Int): View? {
        if (buildingInputConnection) return null
        return try {
            super.focusSearch(direction)
        } catch (e: IllegalStateException) {
            // Compose focus state can be mid-transition: "ActiveParent must have a focusedChild".
            this
        } catch (e: Exception) {
            this
        }
    }
}
