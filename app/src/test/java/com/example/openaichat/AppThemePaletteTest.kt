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
}
