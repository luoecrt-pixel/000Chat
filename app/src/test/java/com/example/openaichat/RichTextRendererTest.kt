package com.example.openaichat

import android.text.Spanned
import android.text.style.URLSpan
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RichTextRendererTest {
    @Test fun codeSpansUseTheSelectedThemeWithoutChangingSourceText() {
        for (id in AppThemePalette.themes) {
            val theme = AppThemePalette.byId(id)
            val rendered = RichTextRenderer.render("`literal_text`", theme = theme) as Spanned
            assertEquals("literal_text", rendered.toString())
            assertEquals(theme.codeText, rendered.getSpans(0, rendered.length, android.text.style.ForegroundColorSpan::class.java).single().foregroundColor)
            assertEquals(theme.codeBg, rendered.getSpans(0, rendered.length, android.text.style.BackgroundColorSpan::class.java).single().backgroundColor)
            val document = ScientificRendererDocument.themed(ScientificRendererDocument.mermaid("flowchart TD\nA-->B"), theme)
            assertTrue(document.contains("background:#%06x".format(theme.assistantBubbleBg and 0xFFFFFF)))
            assertTrue(document.contains("color:#%06x".format(theme.textPrimary and 0xFFFFFF)))
        }
    }

    @Test fun identifiersAndBareUrlsDoNotLoseUnderscores() {
        val source = "snake_case_name https://example.org/api/file_name_v2?id=some_id"
        val result = RichTextRenderer.render(source)
        assertEquals(source, result.toString())
        val spans = (result as Spanned).getSpans(0, result.length, URLSpan::class.java)
        assertEquals(1, spans.size)
        assertEquals("https://example.org/api/file_name_v2?id=some_id", spans.single().url)
    }

    @Test fun fencedCodeAndEscapedMarkersKeepLiteralBytes() {
        val source = "Before ```\nname_with_underscores = `raw_value`\n``` After \\_literal\\_"
        val result = RichTextRenderer.render(source).toString()
        assertTrue(result.contains("name_with_underscores = `raw_value`"))
        assertTrue(result.contains("_literal_"))
        assertFalse(result.contains("\\_literal"))
    }

    @Test fun manyBareLinksRetainPunctuationAndDoNotDuplicateExplicitLinkSpans() {
        val source = "[named](https://example.org/named), " +
            (1..80).joinToString(" ") { "https://example.org/file_$it)." }
        val rendered = RichTextRenderer.render(source) as Spanned
        val links = rendered.getSpans(0, rendered.length, URLSpan::class.java)
        assertEquals(81, links.size)
        assertEquals(1, links.count { it.url == "https://example.org/named" })
        for (index in 1..80) {
            val link = links.single { it.url == "https://example.org/file_$index" }
            assertEquals(link.url, rendered.subSequence(rendered.getSpanStart(link), rendered.getSpanEnd(link)).toString())
            assertEquals(").", rendered.subSequence(rendered.getSpanEnd(link), rendered.getSpanEnd(link) + 2).toString())
        }
    }

    @Test fun markdownLinksKeepExactDestination() {
        val result = RichTextRenderer.render("[source](https://example.org/path_with_underscores)")
        val spans = (result as Spanned).getSpans(0, result.length, URLSpan::class.java)
        assertEquals("https://example.org/path_with_underscores", spans.single().url)
    }
}
