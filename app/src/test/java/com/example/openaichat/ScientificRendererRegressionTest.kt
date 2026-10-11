package com.example.openaichat

import android.net.Uri
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.CancellationException

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ScientificRendererRegressionTest {
    @Test fun mathTokensPreserveSurroundingMarkdownAndCanRestoreEverySourceCharacter() {
        val source = "# Наука\n\n**Сила ${'$'}F=ma${'$'}**; цена ${'$'}5 и ${'$'}10, `\\(code\\)`; \\[x^2+y^2\\]. 😀"
        val formulas = MathDelimiterScanner.scan(source)
        val prepared = MathTextTokens.prepare(source, formulas)
        assertEquals(2, prepared.items.size)
        assertTrue(prepared.markdown.contains("**Сила " + prepared.items.first().first + "**"))
        assertTrue(prepared.markdown.contains("цена ${'$'}5 и ${'$'}10"))
        var restored = prepared.markdown
        prepared.items.forEach { (token, formula) -> restored = restored.replace(token, formula.source) }
        assertEquals(source, restored)
        assertNotEquals(prepared.prefix, MathTextTokens.prepare(source, formulas).prefix)
        try { MathTextTokens.prepare("different", formulas); fail("Stale source offsets must not change another message") }
        catch (_: IllegalArgumentException) { }
    }

    @Test fun inlineDocumentKeepsHtmlAndLatexAsDataAndUsesTheSameIsolatedEngine() {
        val source = "${'$'}x^2${'$'} then </script>"
        val prepared = MathTextTokens.prepare(source, MathDelimiterScanner.scan(source))
        val html = "<p><b>Science</b> " + prepared.items.single().first + " &lt;/script&gt;</p>"
        val document = ScientificRendererDocument.mathInText(html, prepared)
        assertFalse(document.contains(html))
        assertTrue(document.contains("new DOMParser()"))
        assertTrue(document.contains("element.removeAttribute(attribute.name)"))
        assertTrue(document.contains("Formula was not visible Markdown text"))
        assertTrue(document.contains("trust:false"))
        assertTrue(document.contains("await document.fonts.ready"))
        assertFalse(document.contains("addJavascriptInterface"))
    }

    @Test fun allFourDelimitersAreRecognizedWithoutTreatingMoneyOrCodeAsMath() {
        val source = "Цена ${'$'}5 и ${'$'}10; \\${'$'}20. ` ${'$'}code${'$'} `\n" +
            "${'$'}x^2${'$'} и \\(a+b\\), ${'$'}${'$'}x=1\ny=2${'$'}${'$'} и \\[z=3\\]."
        val formulas = MathDelimiterScanner.scan(source)
        assertEquals(listOf("x^2", "a+b", "x=1\ny=2", "z=3"), formulas.map { it.latex })
        assertEquals(listOf(false, false, true, true), formulas.map { it.display })
        formulas.forEach { assertEquals(it.source, source.substring(it.start, it.end)) }
        assertTrue(MathDelimiterScanner.scan("${'$'}5 и ${'$'}10").isEmpty())
        assertTrue(MathDelimiterScanner.scan("`` literal ${'$'}x${'$'} ``").isEmpty())
        assertTrue(MathDelimiterScanner.scan("\\\\(literal)").isEmpty())
        assertEquals("5+3", MathDelimiterScanner.scan("${'$'}5+3${'$'}").single().latex)
        assertEquals("x", MathDelimiterScanner.scan("Цена ${'$'}5 и ${'$'}10; формула \\(x\\).").single().latex)
        assertEquals("y", MathDelimiterScanner.scan("\\[unfinished; valid \\(y\\)").single().latex)
        assertEquals("z", MathDelimiterScanner.scan("\\[".repeat(20_000) + "\\(z\\)").single().latex)
    }

    @Test fun incompleteMathRemainsSourceAndIsFoundOnlyAfterTheClosingChunk() {
        val source = "Text \\[\\frac{a}{b} + c\\] tail"
        for (end in 0..source.length) {
            val prefix = source.substring(0, end)
            MathDelimiterScanner.scan(prefix).forEach { formula ->
                assertEquals(formula.source, prefix.substring(formula.start, formula.end))
                assertTrue(formula.source.endsWith("\\]"))
            }
        }
        assertTrue(MathDelimiterScanner.scan("${'$'}x\nnext").isEmpty())
        assertTrue(MathDelimiterScanner.scan("\\[unfinished".repeat(1000)).isEmpty())
        try {
            MathDelimiterScanner.scan("\\".repeat(100000)) { true }
            fail("Cancelled presentation work must stop")
        } catch (_: CancellationException) { }
    }

    @Test fun rendererAssetsCannotReadOtherOriginsQueriesOrPrivateFiles() {
        val base = ScientificRendererDocument.ORIGIN
        assertEquals("response-renderer/katex/katex.min.js", ScientificRendererDocument.assetPath(Uri.parse(base + "response-renderer/katex/katex.min.js")))
        assertNotNull(ScientificRendererDocument.assetPath(Uri.parse(base + "response-renderer/mermaid/chunks/chunk-1.mjs")))
        listOf(
            "file:///data/data/com.example.openaichat/key", "content://provider/file",
            "https://tracker.invalid/response-renderer/katex/katex.min.js",
            base + "response-renderer/katex/../../secret.js", base + "response-renderer/katex/%2e%2e/secret.js",
            base + "response-renderer/katex/katex.min.js?token=x",
            "https://user@response-renderer.invalid/response-renderer/katex/katex.min.js",
            base + "response-renderer/katex/source.html"
        ).forEach { assertNull(it, ScientificRendererDocument.assetPath(Uri.parse(it))) }
    }

    @Test fun latexInputCannotCloseTheOwnedScriptOrChangeItsSecurityOptions() {
        val payload = "</script><script>window.attack=1</script>\\href{javascript:bad}{x}\u2028"
        val formula = MathDelimiterScanner.Formula(0, payload.length, payload, payload, true)
        val document = ScientificRendererDocument.math(listOf(formula))
        assertFalse(document.contains(payload))
        assertTrue(document.contains("\\u003c/script>"))
        assertTrue(document.contains("trust:false"))
        assertTrue(document.contains("throwOnError:true"))
        assertTrue(document.contains("maxExpand:1000"))
        assertTrue(document.contains("connect-src 'none'"))
        assertFalse(document.contains("addJavascriptInterface"))
    }

    @Test fun mermaidRejectsOverridesAndClickButKeepsAClosedNormalGraphAsData() {
        val graph = "flowchart TD\nA[Science] --> B[Result]"
        assertNull(ScientificRendererDocument.mermaidProblem(graph))
        val document = ScientificRendererDocument.mermaid(graph)
        assertTrue(document.contains("securityLevel:'strict'"))
        assertTrue(document.contains("htmlLabels:false"))
        assertTrue(document.contains("foreignObject"))
        assertFalse(document.contains("bindFunctions"))
        listOf("%%{init:{\"securityLevel\":\"loose\"}}%%\n" + graph,
            "---\nconfig: {}\n---\n" + graph, graph + "\nclick A \"https://track.invalid\"",
            "flowchart TD\nA[<img src=x>]").forEach { assertNotNull(ScientificRendererDocument.mermaidProblem(it)) }
        assertNotNull(ScientificRendererDocument.mermaidProblem("x".repeat(50001)))
    }
}
