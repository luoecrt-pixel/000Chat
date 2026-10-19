package com.example.openaichat

import android.graphics.Color
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class AppThemePaletteTest {
    @Test fun darkIsTheDefaultIncludingUnknownIds() {
        for (id in listOf(null, "", "unknown", " DARK ")) assertSame(AppThemePalette.DARK, AppThemePalette.byId(id))
        assertFalse(AppThemePalette.DARK.isLight)
        assertEquals(Color.rgb(17, 18, 21), AppThemePalette.DARK.rootBg)
        assertEquals(Color.WHITE, AppThemePalette.DARK.textPrimary)
    }

    @Test fun lightHasWhiteSurfacesAndDarkReadableText() {
        val theme = AppThemePalette.byId(" LIGHT ")
        assertSame(AppThemePalette.LIGHT, theme)
        assertTrue(theme.isLight)
        assertEquals(Color.WHITE, theme.headerBg)
        assertEquals(Color.WHITE, theme.inputBg)
        assertEquals(Color.rgb(22, 24, 29), theme.textPrimary)
        assertNotEquals(theme.textPrimary, theme.assistantBubbleBg)
    }

    @Test fun grayMatchesIntegralReferenceAndPickerLabelsMatchIds() {
        val theme = AppThemePalette.byId(" GRAY ")
        assertTrue(theme.isLight)
        assertEquals(Color.parseColor("#CED8DA"), theme.rootBg)
        assertEquals(Color.parseColor("#8199A5"), theme.headerBg)
        assertEquals(listOf("dark", "light", "gray"), AppThemePalette.themes)
        assertEquals(AppThemePalette.themes.map { AppThemePalette.byId(it).label }, AppThemePalette.labels.toList())
    }

    @Test fun semanticContentSurfacesAreDistinctAndReadableInEveryTheme() {
        fun luminance(color: Int): Double {
            fun linear(channel: Int): Double {
                val value = channel / 255.0
                return if (value <= 0.04045) value / 12.92 else Math.pow((value + 0.055) / 1.055, 2.4)
            }
            return 0.2126 * linear(Color.red(color)) + 0.7152 * linear(Color.green(color)) + 0.0722 * linear(Color.blue(color))
        }
        for (id in AppThemePalette.themes) {
            val theme = AppThemePalette.byId(id)
            val pairs = listOf(theme.codeBg to theme.codeText, theme.mathBg to theme.mathText,
                theme.diagramBg to theme.diagramText, theme.toolBg to theme.toolText,
                theme.fileBg to theme.fileText, theme.logBg to theme.logText, theme.errorBg to theme.errorText)
            assertEquals("Each content family needs its own surface in $id", pairs.size, pairs.map { it.first }.toSet().size)
            for ((background, foreground) in pairs) {
                assertNotEquals("Card must be visible on the answer surface in $id", theme.assistantBubbleBg, background)
                val ratio = (maxOf(luminance(background), luminance(foreground)) + 0.05) /
                    (minOf(luminance(background), luminance(foreground)) + 0.05)
                assertTrue("Content contrast $ratio in $id", ratio >= 4.5)
                assertEquals(255, Color.alpha(background))
                assertEquals(255, Color.alpha(foreground))
            }
        }
    }
}
