package com.miruronative.ui.watch

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper

/**
 * The [Activity] behind a Compose [Context], unwrapping whatever `ContextWrapper` layers the theme
 * and window decor put in the way. Player screens need it for orientation and window-insets work,
 * which the composition context alone cannot reach.
 */
internal tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
