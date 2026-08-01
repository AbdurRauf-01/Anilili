package com.anilili.data.settings

import com.anilili.ui.watch.applyCaptionStyleJs
import com.anilili.ui.watch.asJsStringLiteral
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CaptionStyleTest {

    @Test
    fun `default background is translucent, not media3's opaque black`() {
        val alpha = (CaptionStyle().backgroundArgb ushr 24) and 0xFF
        assertTrue("default background must let the video through", alpha in 1..254)
    }

    @Test
    fun `background opacity maps onto the alpha channel`() {
        val style = CaptionStyle(backgroundColor = CaptionBackgroundColor.BLACK)
        assertEquals(0x00000000, style.copy(backgroundOpacityPercent = 0).backgroundArgb)
        assertEquals(0xFF000000.toInt(), style.copy(backgroundOpacityPercent = 100).backgroundArgb)
        // 50% of 255 truncates to 127, not 128.
        assertEquals(0x7F000000, style.copy(backgroundOpacityPercent = 50).backgroundArgb)
        assertEquals(0x99000000.toInt(), style.copy(backgroundOpacityPercent = 60).backgroundArgb)
    }

    @Test
    fun `background opacity preserves the chosen colour's rgb`() {
        val style = CaptionStyle(
            backgroundColor = CaptionBackgroundColor.NAVY,
            backgroundOpacityPercent = 40,
        )
        assertEquals(CaptionBackgroundColor.NAVY.rgb, style.backgroundArgb and 0xFFFFFF)
    }

    @Test
    fun `text colour is always fully opaque`() {
        CaptionTextColor.entries.forEach { color ->
            val argb = CaptionStyle(textColor = color).textArgb
            assertEquals("alpha for $color", 0xFF, (argb ushr 24) and 0xFF)
            assertEquals("rgb for $color", color.rgb, argb and 0xFFFFFF)
        }
    }

    @Test
    fun `css rgba uses a dot decimal regardless of locale`() {
        val previous = java.util.Locale.getDefault()
        try {
            // A comma-decimal locale would otherwise emit rgba(..., 0,60) and void the rule.
            java.util.Locale.setDefault(java.util.Locale.GERMANY)
            val css = CaptionStyle(
                backgroundColor = CaptionBackgroundColor.BLACK,
                backgroundOpacityPercent = 60,
            ).backgroundCssRgba()
            assertEquals("rgba(0, 0, 0, 0.60)", css)
        } finally {
            java.util.Locale.setDefault(previous)
        }
    }

    @Test
    fun `css hex is six digits`() {
        assertEquals("#ffffff", CaptionStyle(textColor = CaptionTextColor.WHITE).textCssHex())
        assertEquals("#4dd0e1", CaptionStyle(textColor = CaptionTextColor.CYAN).textCssHex())
    }

    @Test
    fun `stored values round-trip and unknown values fall back to the default`() {
        CaptionTextColor.entries.forEach {
            assertEquals(it, CaptionTextColor.fromStored(it.storedValue))
        }
        CaptionBackgroundColor.entries.forEach {
            assertEquals(it, CaptionBackgroundColor.fromStored(it.storedValue))
        }
        CaptionEdgeStyle.entries.forEach {
            assertEquals(it, CaptionEdgeStyle.fromStored(it.storedValue))
        }
        assertEquals(CaptionTextColor.WHITE, CaptionTextColor.fromStored("chartreuse"))
        assertEquals(CaptionBackgroundColor.BLACK, CaptionBackgroundColor.fromStored(null))
        assertEquals(CaptionEdgeStyle.NONE, CaptionEdgeStyle.fromStored(""))
    }

    @Test
    fun `defaults are offered as selectable steps`() {
        assertTrue(
            CaptionStyle.DEFAULT_BACKGROUND_OPACITY_PERCENT in CaptionStyle.BACKGROUND_OPACITY_STEPS,
        )
        assertTrue(CaptionStyle.DEFAULT_TEXT_SCALE_PERCENT in CaptionStyle.TEXT_SCALE_STEPS)
        assertTrue(CaptionStyle.DEFAULT_BOTTOM_MARGIN_PERCENT in CaptionStyle.BOTTOM_MARGIN_STEPS)
    }

    @Test
    fun `captions default to bold and sit above media3's usual bottom padding`() {
        val style = CaptionStyle()

        assertTrue(style.boldText)
        assertEquals(0.12f, style.bottomPaddingFraction)
        assertTrue(style.bottomPaddingFraction > 0.08f)
    }

    @Test
    fun `bottom margin percent becomes a fraction`() {
        assertEquals(0.25f, CaptionStyle(bottomMarginPercent = 25).bottomPaddingFraction, 0.001f)
    }

    @Test
    fun `injected css carries the margin and lifts further when controls are up`() {
        val style = CaptionStyle(bottomMarginPercent = 25)
        assertTrue(applyCaptionStyleJs(style, controlsVisible = false).contains("bottom: 25% !important"))
        assertTrue(applyCaptionStyleJs(style, controlsVisible = true).contains("bottom: 35% !important"))
    }

    @Test
    fun `injected css offsets each surface once`() {
        // ::cue only honours transform, the player containers only honour bottom. Emitting both on
        // either surface moved captions twice as far as the user asked for.
        val js = applyCaptionStyleJs(CaptionStyle(bottomMarginPercent = 20))
        val cueRule = js.substringAfter("::cue").substringBefore("}")
        assertTrue("::cue needs the transform", cueRule.contains("translateY(-20vh)"))
        assertFalse("::cue must not also set bottom", cueRule.contains("bottom:"))
        val containerRule = js.substringAfter(".vjs-text-track-display").substringBefore("}")
        assertTrue(containerRule.contains("bottom: 20% !important"))
        assertFalse("containers must not also set margin-bottom", containerRule.contains("margin-bottom"))
    }

    @Test
    fun `the css is embedded as a valid javascript string literal`() {
        // The stylesheet's attribute selectors contain double quotes. Interpolating them raw made
        // the whole injected script a SyntaxError, and evaluateJavascript reports that nowhere —
        // captions silently kept the page's own styling on every embed player.
        val quote = '"'
        val backslash = '\\'
        val js = applyCaptionStyleJs(CaptionStyle())
        // The CSS is flattened onto one line and is full of semicolons, so take the whole
        // assignment line rather than cutting at the first one.
        val literal = js.lineSequence().first { it.contains("style.textContent") }
            .trim().removePrefix("style.textContent = ").removeSuffix(";")
        assertEquals("literal must open with a quote", quote, literal.first())
        assertEquals("literal must close with a quote", quote, literal.last())
        // Every quote inside the literal has to be escaped, or it ends the string early.
        val body = literal.drop(1).dropLast(1)
        body.forEachIndexed { index, ch ->
            if (ch == quote) {
                assertTrue("unescaped quote at $index", index > 0 && body[index - 1] == backslash)
            }
        }
        // ...and the selector still has to be there once escaped.
        assertTrue("the attribute selector must survive", body.contains("[class*=" + backslash + quote))
    }

    @Test
    fun `js string literal escapes everything that could end it early`() {
        val quote = '"'
        val backslash = '\\'

        // A bare quote comes back as backslash + quote, wrapped in quotes.
        val quoted = quote.toString().asJsStringLiteral()
        assertEquals(listOf(quote, backslash, quote, quote), quoted.toList())

        // A backslash doubles, so it cannot escape the closing quote.
        val slashed = backslash.toString().asJsStringLiteral()
        assertEquals(listOf(quote, backslash, backslash, quote), slashed.toList())

        // Nothing that terminates a JS string literal may survive raw — including the line and
        // paragraph separators, which are not newlines but end a literal all the same.
        listOf('\n', '\r', ' ', ' ').forEach { ch ->
            assertFalse("$ch must not appear raw", ch in ch.toString().asJsStringLiteral())
        }
        // '<' is escaped so the payload stays inert inside an inline script block.
        assertFalse("<".asJsStringLiteral().contains('<'))

        // Ordinary characters, spaces included, pass through untouched.
        assertEquals(quote + "a b" + quote, "a b".asJsStringLiteral())
    }
}
