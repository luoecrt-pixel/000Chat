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
) {
    // Content has its own quiet colour families, rather than borrowing a button
    // accent or flattening every scientific/card surface into the chat bubble.
    private fun semantic(dark: Long, light: Long, gray: Long): Int = when (id) {
        "light" -> light.toInt()
        "gray" -> gray.toInt()
        else -> dark.toInt()
    }
    val codeBg: Int get() = semantic(0xFF292536, 0xFFF1EDF8, 0xFFDCD8E5)
    val codeText: Int get() = semantic(0xFFE0D4F7, 0xFF503B78, 0xFF49385F)
    val codeBorder: Int get() = semantic(0xFF655578, 0xFFC3B5D8, 0xFFADA0BF)
    val mathBg: Int get() = semantic(0xFF302A21, 0xFFFFF5E2, 0xFFE9E1D0)
    val mathText: Int get() = semantic(0xFFF2D8AA, 0xFF755018, 0xFF694B24)
    val mathBorder: Int get() = semantic(0xFF7A6542, 0xFFD7BD8C, 0xFFB9A780)
    val diagramBg: Int get() = semantic(0xFF203136, 0xFFE9F5F5, 0xFFD1E1DF)
    val diagramText: Int get() = semantic(0xFFB8E0DF, 0xFF285D63, 0xFF244D53)
    val diagramBorder: Int get() = semantic(0xFF416A71, 0xFF9FC5C7, 0xFF8EAEB0)
    val toolBg: Int get() = semantic(0xFF242D3C, 0xFFEDF2FA, 0xFFD4DEE8)
    val toolText: Int get() = semantic(0xFFC3D6F5, 0xFF36567F, 0xFF314B6C)
    val fileBg: Int get() = semantic(0xFF302A35, 0xFFF7EEF5, 0xFFE4D9E3)
    val fileText: Int get() = semantic(0xFFE3C9E0, 0xFF744668, 0xFF65405C)
    val logBg: Int get() = semantic(0xFF282D37, 0xFFF0F3F8, 0xFFD5DEE3)
    val logText: Int get() = semantic(0xFFCCD5E6, 0xFF425572, 0xFF364D5D)
    val surfaceBorder: Int get() = semantic(0xFF505969, 0xFFC2CDDC, 0xFF9DAFB9)
    val diffAddedBg: Int get() = semantic(0xFF19382B, 0xFFE6F4EA, 0xFFCDDDCF)
    val diffAddedText: Int get() = semantic(0xFFBDECCB, 0xFF205C35, 0xFF244D32)
    val errorBg: Int get() = semantic(0xFF3A2529, 0xFFFFEEEB, 0xFFECD8D4)
    val errorText: Int get() = semantic(0xFFFFC1B9, 0xFF963F37, 0xFF873D36)
}

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
