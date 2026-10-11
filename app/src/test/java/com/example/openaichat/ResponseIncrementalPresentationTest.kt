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

    @Test fun nativeHtmlWhitelistNeverAcceptsAttributesOrExecutableMarkup() {
        listOf("<sup>", "</sub>", "<br>", "<BR />").forEach { assertTrue(ResponseRenderSafety.nativeInlineTag(it)) }
        listOf("<sup onclick=\"x\">", "<style>", "<iframe>", "<script>", "<img src=\"x\">").forEach {
            assertFalse(ResponseRenderSafety.nativeInlineTag(it))
            assertFalse(ResponseRenderSafety.nativeHtmlBlock(it + "body"))
        }
        assertTrue(ResponseRenderSafety.nativeHtmlBlock("x<sup>2</sup><br/>y<sub>0</sub>"))
        assertFalse(ResponseRenderSafety.nativeHtmlBlock("<sup>x</sup><unfinished"))
    }
}
