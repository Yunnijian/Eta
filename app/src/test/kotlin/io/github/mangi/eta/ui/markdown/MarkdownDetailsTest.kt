package io.github.mangi.eta.ui.markdown

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkdownDetailsTest {
    @Test
    fun detailsWithSummaryAndMarkdownBodyBecomesOneCollapsibleBlock() {
        val document = parse(
            """
            <details>
            <summary>更多信息</summary>

            正文 **加粗**

            </details>
            """.trimIndent(),
        )

        val details = document.blocks.single() as MarkdownDetails
        assertEquals("更多信息", details.summary.text)
        val paragraph = details.blocks.single() as MarkdownParagraph
        assertEquals("正文 加粗", paragraph.text.text)
    }

    @Test
    fun singleLineDetailsIsParsedWithoutBlankLines() {
        val document = parse("<details><summary>标题</summary>内容</details>")

        val details = document.blocks.single() as MarkdownDetails
        assertEquals("标题", details.summary.text)
        assertEquals("内容", (details.blocks.single() as MarkdownParagraph).text.text)
    }

    @Test
    fun scriptInsideDetailsStaysLiteralText() {
        val document = parse(
            """
            <details>
            <summary>安全</summary>
            <script>alert(1)</script>
            </details>
            """.trimIndent(),
        )

        val details = document.blocks.single() as MarkdownDetails
        val text = details.blocks.joinToString("\n") { (it as? MarkdownTextBlock)?.text?.text.orEmpty() }
        assertTrue(text.contains("<script>alert(1)</script>"))
    }

    @Test
    fun otherHtmlBlocksStillDisplayAsRawText() {
        val document = parse("<div>hello</div>")

        val paragraph = document.blocks.single() as MarkdownParagraph
        assertEquals("<div>hello</div>", paragraph.text.text)
    }

    @Test
    fun danglingDetailsFallsBackToRawText() {
        val document = parse("<details>\n<summary>没有闭合</summary>")

        val paragraph = document.blocks.single() as MarkdownParagraph
        assertTrue(paragraph.text.text.contains("<details>"))
    }

    @Test
    fun mermaidFenceKeepsItsLanguageAndIsNotTreatedAsMath() {
        val document = parse("```mermaid\ngraph TD\n  A --> B\n```")

        val code = document.blocks.single() as MarkdownCode
        assertTrue(code.isMermaid())
        assertFalse(code.isMath)
    }

    private fun parse(source: String): MarkdownDocument =
        StreamingGfmParserSession().parse(source, isComplete = true).document
}
