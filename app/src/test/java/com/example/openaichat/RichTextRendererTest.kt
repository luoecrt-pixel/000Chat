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

    @Test fun markdownLinksKeepExactDestination() {
        val result = RichTextRenderer.render("[source](https://example.org/path_with_underscores)")
        val spans = (result as Spanned).getSpans(0, result.length, URLSpan::class.java)
        assertEquals("https://example.org/path_with_underscores", spans.single().url)
    }
}
