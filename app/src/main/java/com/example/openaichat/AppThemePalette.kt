package com.example.openaichat

import android.graphics.Color

data class ThemeColors(
    val id: String,
    val label: String,
    val isLight: Boolean,
    val rootBg: Int,
    val headerBg: Int,
    val drawerBg: Int,
    val drawerSelectedBg: Int,
    val drawerNewChatBg: Int,
    val userBubbleBg: Int,
    val assistantBubbleBg: Int,
    val cardBg: Int,
    val inputBg: Int,
    val buttonBg: Int,
    val textPrimary: Int,
    val textSecondary: Int,
    val hintText: Int,
    val accent: Int,
    val onAccent: Int,
    val divider: Int,
    val tableHeaderBg: Int,
    val tableRowBg: Int
)

object AppThemePalette {
    val themes = listOf("dark", "light", "gray")
    val labels = arrayOf("Тёмная", "Светлая (белая)", "Серая (Integral)")

    fun byId(id: String?): ThemeColors = when (id?.trim()?.lowercase()) {
        "light" -> LIGHT
        "gray" -> GRAY
        else -> DARK
    }

    val DARK = ThemeColors(
        id = "dark",
        label = "Тёмная",
        isLight = false,
        rootBg = Color.rgb(17, 18, 21),
        headerBg = Color.rgb(22, 23, 27),
        drawerBg = Color.rgb(24, 25, 30),
        drawerSelectedBg = Color.rgb(43, 48, 60),
        drawerNewChatBg = Color.rgb(36, 44, 58),
        userBubbleBg = Color.rgb(31, 40, 54),
        assistantBubbleBg = Color.rgb(28, 29, 34),
        cardBg = Color.rgb(36, 38, 44),
        inputBg = Color.rgb(30, 31, 36),
        buttonBg = Color.rgb(34, 36, 42),
        textPrimary = Color.WHITE,
        textSecondary = Color.rgb(165, 170, 180),
        hintText = Color.rgb(120, 125, 135),
        accent = Color.rgb(138, 180, 248),
        onAccent = Color.rgb(18, 20, 24),
        divider = Color.rgb(42, 44, 52),
        tableHeaderBg = Color.rgb(40, 44, 52),
        tableRowBg = Color.rgb(32, 35, 42)
    )

    val LIGHT = ThemeColors(
        id = "light",
        label = "Светлая (белая)",
        isLight = true,
        rootBg = Color.rgb(247, 248, 250),
        headerBg = Color.rgb(255, 255, 255),
        drawerBg = Color.rgb(242, 244, 248),
        drawerSelectedBg = Color.rgb(216, 228, 248),
        drawerNewChatBg = Color.rgb(225, 235, 252),
        userBubbleBg = Color.rgb(220, 234, 255),
        assistantBubbleBg = Color.rgb(255, 255, 255),
        cardBg = Color.rgb(238, 241, 246),
        inputBg = Color.rgb(255, 255, 255),
        buttonBg = Color.rgb(232, 236, 242),
        textPrimary = Color.rgb(22, 24, 29),
        textSecondary = Color.rgb(85, 93, 107),
        hintText = Color.rgb(130, 138, 150),
        accent = Color.rgb(26, 102, 210),
        onAccent = Color.WHITE,
        divider = Color.rgb(216, 222, 232),
        tableHeaderBg = Color.rgb(228, 233, 242),
        tableRowBg = Color.rgb(244, 246, 250)
    )

    // Reference 1000269206.jpg: main background #CED8DA, header #8199A5
    val GRAY = ThemeColors(
        id = "gray",
        label = "Серая (Integral)",
        isLight = true,
        rootBg = Color.rgb(0xCE, 0xD8, 0xDA),
        headerBg = Color.rgb(0x81, 0x99, 0xA5),
        drawerBg = Color.rgb(0xC2, 0xCE, 0xD2),
        drawerSelectedBg = Color.rgb(0x9F, 0xB3, 0xBD),
        drawerNewChatBg = Color.rgb(0xAE, 0xC0, 0xC8),
        userBubbleBg = Color.rgb(0xB4, 0xC6, 0xCE),
        assistantBubbleBg = Color.rgb(0xDF, 0xE6, 0xE8),
        cardBg = Color.rgb(0xD5, 0xDF, 0xE2),
        inputBg = Color.rgb(0xE3, 0xE9, 0xEB),
        buttonBg = Color.rgb(0xB8, 0xC7, 0xCD),
        textPrimary = Color.rgb(0x16, 0x20, 0x24),
        textSecondary = Color.rgb(0x3E, 0x4E, 0x56),
        hintText = Color.rgb(0x5A, 0x6B, 0x73),
        accent = Color.rgb(0x1B, 0x56, 0x73),
        onAccent = Color.WHITE,
        divider = Color.rgb(0xA6, 0xB7, 0xBE),
        tableHeaderBg = Color.rgb(0xBD, 0xCB, 0xD0),
        tableRowBg = Color.rgb(0xD4, 0xDE, 0xE1)
    )
}
