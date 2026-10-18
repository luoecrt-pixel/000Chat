package com.example.openaichat

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CancellationException

class ResponseIncrementalPresentationTest {
    @Test fun everyStreamingPrefixMatchesACompleteParseIncludingProvisionalClosures() {
        val source = "Intro\n\n````text\n``` literal\n````\n\n" +
            "<file name=\"a.txt\">\nfile\n</file>\n" +
            "| a | b |\n| -- | -- |\n| 1 | 2 |\n\n" +
            "<details>\n<summary>More</summary>\nbody\n</details>\nEnd"
        val session = ResponseBlockParser.Session()
        for (end in 0..source.length) {
            val prefix = source.substring(0, end)
            assertEquals("prefix $end", ResponseBlockParser.parse(prefix), session.update(prefix))
        }
    }

    @Test fun closedBlocksAreReusedButSyntheticJsonAndUnfinishedFenceAreNotFrozen() {
        val session = ResponseBlockParser.Session()
        val first = session.update("```txt\nhello\n```\n")
        val second = session.update("```txt\nhello\n```\nnext")
        assertSame(first.single(), second.first())
        session.reset()
        session.update("```txt\nhello\n```")
        val longer = "```txt\nhello\n```extra"
        assertEquals(ResponseBlockParser.parse(longer), session.update(longer))
        session.reset()
        session.update("[1,2]\n")
        assertEquals(ResponseBlockParser.parse("[1,2]\nprose"), session.update("[1,2]\nprose"))
        assertEquals(ResponseBlockParser.parse("replacement"), session.update("replacement"))
    }

    @Test fun cancelledParsingDoesNotCommitAnIncompleteSession() {
        val session = ResponseBlockParser.Session()
        val original = "```text\nready\n```\n"
        session.update(original)
        var checks = 0
        try {
            session.update(original + "line\n".repeat(10000)) { ++checks > 20 }
            fail("Cancelled snapshots must stop")
        } catch (_: CancellationException) { }
        assertEquals(ResponseBlockParser.parse(original + "done"), session.update(original + "done"))
        try {
            ResponseBlockParser.cells("x|".repeat(10000)) { true }
            fail("A long table row must be cancellable")
        } catch (_: CancellationException) { }
    }

    @Test fun detailsDoNotCloseInsideCodeOrFileAndUnsafeAttributesRemainProse() {
        val source = "<details open>\n<summary>Science</summary>\n" +
            "```text\n</details>\n```\n" +
            "<file name=\"example.txt\">\n</details>\n</file>\n" +
            "<details>\n<summary>Nested</summary>\nx\n</details>\n</details>\n"
        val block = ResponseBlockParser.parse(source).single() as ResponseBlockParser.Block.Details
        assertTrue(block.closed)
        assertTrue(block.expanded)
        assertTrue(block.content.contains("<file name="))
        val partial = ResponseBlockParser.parse(source.substringBeforeLast("</details>"))
        assertFalse((partial.single() as ResponseBlockParser.Block.Details).closed)
        val unsafe = "<details onclick=\"bad()\">\n<summary>x</summary>\nbody\n</details>"
        assertTrue(ResponseBlockParser.parse(unsafe).all { it is ResponseBlockParser.Block.Prose })
    }

    @Test fun pagesPreserveAllUnicodeAndNulWithoutSplittingSurrogatePairs() {
        val source = "a".repeat(ResponseTextPages.CHARACTERS - 1) + "😀" +
            "b".repeat(ResponseTextPages.CHARACTERS - 2) + "😀\u0000tail"
        val count = ResponseTextPages.page(source, 0).count
        val pages = (0 until count).map { ResponseTextPages.page(source, it).text }
        assertEquals(source, pages.joinToString(""))
        pages.forEach { assertFalse(it.firstOrNull()?.let(Character::isLowSurrogate) ?: false)
            assertFalse(it.lastOrNull()?.let(Character::isHighSurrogate) ?: false) }
        assertEquals(count - 1, ResponseTextPages.page(source, Int.MAX_VALUE).index)
        assertEquals("", ResponseTextPages.page("", 10).text)
    }

    @Test fun closedFormulaCrossing32KBoundaryMovesIntactToNextPageWithoutAlteringSource() {
        val formula = "\\[\\int_0^\\infty e^{-x^2}\\,dx = \\frac{\\sqrt{\\pi}}{2}\\]"
        val prefix = "Научный текст ".repeat(2285).take(ResponseTextPages.CHARACTERS - 12)
        val suffix = "\nПродолжение вывода после формулы 😀".repeat(120)
        val source = prefix + formula + suffix
        val first = ResponseTextPages.page(source, 0)
        val second = ResponseTextPages.page(source, 1)
        assertEquals(prefix, first.text)
        assertNull(first.fallbackNotice)
        assertTrue(second.text.startsWith(formula))
        assertNull(second.fallbackNotice)
        assertEquals(formula, MathDelimiterScanner.scan(second.text).first().source)
        val allPages = (0 until first.count).map { ResponseTextPages.page(source, it).text }
        assertEquals(source, allPages.joinToString(""))
    }

    @Test fun exact512KBudgetBoundaryAndOversizedSourceRemainLossless() {
        val formula = "${'$'}x^2 + y^2 = z^2${'$'}"
        val exact = "A".repeat(ResponseTextPages.SAFE_UI_SOURCE_BUDGET - formula.length) + formula
        assertEquals(512_000, exact.length)
        val first = ResponseTextPages.page(exact, 0)
        val boundedLayout = cachedPageLayout()!!
        assertEquals(exact, cachedPageSource(boundedLayout))
        for (index in 0 until first.count) assertNull(ResponseTextPages.page(exact, index).fallbackNotice)
        assertEquals(exact, (0 until first.count).joinToString("") { ResponseTextPages.page(exact, it).text })
        assertSame("Bounded answers must reuse their computed layout", boundedLayout, cachedPageLayout())

        val over = exact + "B"
        assertEquals(512_001, over.length)
        val oversized = ResponseTextPages.page(over, 0)
        for (index in 0 until oversized.count) assertNotNull(ResponseTextPages.page(over, index).fallbackNotice)
        assertEquals(over, (0 until oversized.count).joinToString("") { ResponseTextPages.page(over, it).text })
        assertSame("Oversized answers must not replace the bounded cache", boundedLayout, cachedPageLayout())
        assertEquals(exact, cachedPageSource(boundedLayout))
    }

    @Test fun oversizedPagesStayBoundedLosslessAndDoNotRetainTheirSource() {
        val bounded = "cached bounded answer"
        ResponseTextPages.page(bounded, 0)
        val boundedLayout = cachedPageLayout()!!
        val stride = ResponseTextPages.CHARACTERS - 1
        val characters = CharArray(stride * 20 + 1) { 'x' }
        for (boundary in 1..20 step 2) {
            characters[boundary * stride - 1] = '\uD83D'
            characters[boundary * stride] = '\uDE00'
        }
        val source = String(characters) + "\u0000tail"
        assertTrue(source.length > ResponseTextPages.SAFE_UI_SOURCE_BUDGET)
        val first = ResponseTextPages.page(source, Int.MIN_VALUE)
        val pages = (0 until first.count).map { ResponseTextPages.page(source, it) }
        assertEquals(0, first.index)
        assertEquals(pages.last(), ResponseTextPages.page(source, Int.MAX_VALUE))
        assertEquals(source, pages.joinToString("") { it.text })
        pages.forEach {
            assertNotNull(it.fallbackNotice)
            assertTrue(it.text.isNotEmpty())
            assertTrue(it.text.length <= ResponseTextPages.CHARACTERS)
            assertFalse(it.text.first().isLowSurrogate())
            assertFalse(it.text.last().isHighSurrogate())
        }
        assertTrue(pages.any { it.text.length == ResponseTextPages.CHARACTERS })
        assertSame(boundedLayout, cachedPageLayout())
        assertSame(bounded, cachedPageSource(boundedLayout))
    }

    @Test fun unclosedFormulaAcrossBoundaryRemainsLossless() {
        val unclosed = "Текст ".repeat(5330) + "\\[x_1 + x_2 + " + "y".repeat(500)
        assertTrue(unclosed.length > ResponseTextPages.CHARACTERS)
        val first = ResponseTextPages.page(unclosed, 0)
        assertEquals(unclosed, (0 until first.count).joinToString("") { ResponseTextPages.page(unclosed, it).text })
    }

    @Test fun exact16KMathBodyWithDelimitersDoesNotReceiveOversizedNotice() {
        val formula = "\\[" + "x".repeat(ScientificRendererDocument.MAX_MATH_CHARACTERS) + "\\]"
        assertEquals(16_004, formula.length)
        val prefix = "P".repeat(ResponseTextPages.CHARACTERS - 500)
        val source = prefix + formula + "Tail"
        val first = ResponseTextPages.page(source, 0)
        val second = ResponseTextPages.page(source, 1)
        assertEquals(prefix, first.text)
        assertNull(first.fallbackNotice)
        assertTrue(second.text.startsWith(formula))
        assertNull(second.fallbackNotice)
        assertEquals(source, (0 until first.count).joinToString("") { ResponseTextPages.page(source, it).text })
    }

    @Test fun oversizedMathBodyMovesIntactAndNoticesOnlyThePageContainingIt() {
        val formula = "\\[" + "x".repeat(ScientificRendererDocument.MAX_MATH_CHARACTERS + 1) + "\\]"
        val prefix = "P".repeat(ResponseTextPages.CHARACTERS - 500)
        val source = prefix + formula + "Tail"
        val first = ResponseTextPages.page(source, 0)
        val second = ResponseTextPages.page(source, 1)
        assertEquals(prefix, first.text)
        assertNull("Moving a formula must not flag the preceding page", first.fallbackNotice)
        assertTrue(second.text.startsWith(formula))
        assertNotNull(second.fallbackNotice)
        assertEquals(source, (0 until first.count).joinToString("") { ResponseTextPages.page(source, it).text })
    }

    @Test fun formulaLargerThanPageFallsBackWithoutSplittingSurrogatePairsOrLosingText() {
        val formula = "\\[" + "x".repeat(ResponseTextPages.CHARACTERS - 3) + "😀" + "y".repeat(500) + "\\]"
        val source = formula + "Tail"
        val first = ResponseTextPages.page(source, 0)
        val pages = (0 until first.count).map { ResponseTextPages.page(source, it) }
        assertTrue(pages.size > 1)
        assertEquals(source, pages.joinToString("") { it.text })
        pages.forEach {
            assertNotNull(it.fallbackNotice)
            assertTrue(it.text.length <= ResponseTextPages.CHARACTERS)
            assertFalse(it.text.first().isLowSurrogate())
            assertFalse(it.text.last().isHighSurrogate())
        }
    }

    @Test fun nativeHtmlWhitelistNeverAcceptsAttributesOrExecutableMarkup() {
        listOf("<sup>", "</sub>", "<br>", "<BR />").forEach { assertTrue(ResponseRenderSafety.nativeInlineTag(it)) }
        listOf("<sup onclick=\"x\">", "<style>", "<iframe>", "<script>", "<img src=\"x\">").forEach {
            assertFalse(ResponseRenderSafety.nativeInlineTag(it))
            assertFalse(ResponseRenderSafety.nativeHtmlBlock(it + "body"))
        }
        assertTrue(ResponseRenderSafety.nativeHtmlBlock("x<sup>2</sup><br/>y<sub>0</sub>"))
        assertFalse(ResponseRenderSafety.nativeHtmlBlock("<sup>x</sup><unfinished"))
    }

    private fun cachedPageLayout(): Any? = ResponseTextPages::class.java
        .getDeclaredField("cachedLayout").apply { isAccessible = true }.get(null)

    private fun cachedPageSource(layout: Any): String = layout.javaClass
        .getDeclaredField("source").apply { isAccessible = true }.get(layout) as String
}
