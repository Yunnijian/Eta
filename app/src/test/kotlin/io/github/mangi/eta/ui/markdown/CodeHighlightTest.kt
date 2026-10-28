package io.github.mangi.eta.ui.markdown

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CodeHighlightTest {
    @Test
    fun pythonKeywordsStringsCommentsAndNumbersAreTokenized() {
        val code = """
            def greet(name):
                # 说明
                message = "hello"
                return 42
        """.trimIndent()

        val tokens = tokenizeCode(code, "python")
        assertNotNull(tokens)
        assertToken(tokens.orEmpty(), code, "def", CodeTokenKind.Keyword)
        assertToken(tokens.orEmpty(), code, "return", CodeTokenKind.Keyword)
        assertToken(tokens.orEmpty(), code, "\"hello\"", CodeTokenKind.String)
        assertToken(tokens.orEmpty(), code, "# 说明", CodeTokenKind.Comment)
        assertToken(tokens.orEmpty(), code, "42", CodeTokenKind.Number)
    }

    @Test
    fun kotlinTokensCoverKeywordsStringsCommentsAndNumbers() {
        val code = """
            fun main() {
                val text = "你好"
                // 注释
                println(1.5f)
            }
        """.trimIndent()

        val tokens = tokenizeCode(code, "kotlin").orEmpty()
        assertToken(tokens, code, "fun", CodeTokenKind.Keyword)
        assertToken(tokens, code, "val", CodeTokenKind.Keyword)
        assertToken(tokens, code, "\"你好\"", CodeTokenKind.String)
        assertToken(tokens, code, "// 注释", CodeTokenKind.Comment)
        assertToken(tokens, code, "1.5f", CodeTokenKind.Number)
    }

    @Test
    fun javascriptAliasAlsoTokenizes() {
        val code = """
            const answer = 42;
            // 注释
            console.log("hi");
        """.trimIndent()

        val tokens = tokenizeCode(code, "js").orEmpty()
        assertToken(tokens, code, "const", CodeTokenKind.Keyword)
        assertToken(tokens, code, "42", CodeTokenKind.Number)
        assertToken(tokens, code, "\"hi\"", CodeTokenKind.String)
        assertToken(tokens, code, "// 注释", CodeTokenKind.Comment)
    }

    @Test
    fun unknownOrMissingLanguageFallsBackToPlainText() {
        assertNull(tokenizeCode("plain text", null))
        assertNull(tokenizeCode("plain text", ""))
        assertNull(tokenizeCode("plain text", "brainfuck"))
    }

    @Test
    fun highlightBuildsOneSpanPerToken() {
        val code = "val answer = 42 // 注释"
        val highlighted = CodeHighlighter.highlight(code, "kt", CodeSyntaxPalette.Light)

        assertNotNull(highlighted)
        assertEquals(code, highlighted?.text)
        assertEquals(
            tokenizeCode(code, "kt")?.size,
            highlighted?.spanStyles?.size,
        )
    }

    @Test
    fun unterminatedStringStopsAtLineEnd() {
        val code = "val name = \"unfinished\nval next = 1"
        val tokens = tokenizeCode(code, "kotlin").orEmpty()

        assertToken(tokens, code, "\"unfinished", CodeTokenKind.String)
        assertTrue(tokens.any { it.kind == CodeTokenKind.Keyword && it.text(code) == "val" })
    }

    @Test
    fun markupColorsTagNamesAndAttributeValuesWithoutExecutingThem() {
        val code = """<a href="https://example.com">x</a>"""
        val tokens = tokenizeCode(code, "html").orEmpty()

        assertToken(tokens, code, "a", CodeTokenKind.Keyword)
        assertToken(tokens, code, "\"https://example.com\"", CodeTokenKind.String)
    }

    @Test
    fun htmlStyleBlockSwitchesToCssAndColorsSelectorsPropertiesAndColors() {
        val code = """
            <style>
            /* 卡片 */
            body { background: #fff; padding: 12px; }
            .card {
              background: linear-gradient(90deg, #667eea, #3a5ce5);
              padding: 480px;
              border: 1px solid #3a5ce5;
            }
            @media (max-width: 480px) { .card { padding: 20px; } }
            </style>
        """.trimIndent()

        val tokens = tokenizeCode(code, "html").orEmpty()
        assertToken(tokens, code, "/* 卡片 */", CodeTokenKind.Comment)
        assertToken(tokens, code, "body", CodeTokenKind.Keyword)
        assertToken(tokens, code, ".card", CodeTokenKind.Keyword)
        assertToken(tokens, code, "background", CodeTokenKind.Keyword)
        assertToken(tokens, code, "padding", CodeTokenKind.Keyword)
        assertToken(tokens, code, "linear-gradient", CodeTokenKind.Keyword)
        assertToken(tokens, code, "solid", CodeTokenKind.Keyword)
        assertToken(tokens, code, "#fff", CodeTokenKind.Number)
        assertToken(tokens, code, "#667eea", CodeTokenKind.Number)
        assertToken(tokens, code, "#3a5ce5", CodeTokenKind.Number)
        assertToken(tokens, code, "12px", CodeTokenKind.Number)
        assertToken(tokens, code, "480px", CodeTokenKind.Number)
        assertToken(tokens, code, "20px", CodeTokenKind.Number)
    }

    @Test
    fun standaloneCssBlockHighlightsRulesCommentsAndNestedBlocks() {
        val code = """
            /* 主题 */
            :root { --gap: 12px; }
            .card:hover {
              background: rgba(0, 0, 0, .08);
              color: #fff;
            }
            @media screen and (min-width: 480px) {
              .card { padding: 24px; border-radius: 50%; }
            }
        """.trimIndent()

        val tokens = tokenizeCode(code, "css").orEmpty()
        assertToken(tokens, code, "/* 主题 */", CodeTokenKind.Comment)
        assertToken(tokens, code, ":root", CodeTokenKind.Keyword)
        assertToken(tokens, code, "--gap", CodeTokenKind.Keyword)
        assertToken(tokens, code, ".card", CodeTokenKind.Keyword)
        assertToken(tokens, code, ":hover", CodeTokenKind.Keyword)
        assertToken(tokens, code, "background", CodeTokenKind.Keyword)
        assertToken(tokens, code, "rgba", CodeTokenKind.Keyword)
        assertToken(tokens, code, "#fff", CodeTokenKind.Number)
        assertToken(tokens, code, ".08", CodeTokenKind.Number)
        assertToken(tokens, code, "12px", CodeTokenKind.Number)
        assertToken(tokens, code, "480px", CodeTokenKind.Number)
        assertToken(tokens, code, "24px", CodeTokenKind.Number)
        assertToken(tokens, code, "50%", CodeTokenKind.Number)
    }

    @Test
    fun htmlScriptBlockSwitchesToJavaScript() {
        val code = """
            <script type="text/javascript">
            // 初始化
            const name = "eta";
            if (name) { console.log(name); }
            </script>
        """.trimIndent()

        val tokens = tokenizeCode(code, "html").orEmpty()
        assertToken(tokens, code, "// 初始化", CodeTokenKind.Comment)
        assertToken(tokens, code, "const", CodeTokenKind.Keyword)
        assertToken(tokens, code, "if", CodeTokenKind.Keyword)
        assertToken(tokens, code, "\"eta\"", CodeTokenKind.String)
        assertToken(tokens, code, "\"text/javascript\"", CodeTokenKind.String)
    }

    @Test
    fun styleTextInsideStringsAndCommentsDoesNotSwitchSubLanguage() {
        val code = """
            <!-- <style> body { color: red } </style> -->
            <script>
            const snippet = "<style> .x { color: red } </style>";
            // 注释里的 <style> 也不切换
            </script>
            <div data-sample="<style>"></div>
        """.trimIndent()

        val tokens = tokenizeCode(code, "html").orEmpty()
        assertToken(tokens, code, "\"<style> .x { color: red } </style>\"", CodeTokenKind.String)
        assertToken(tokens, code, "\"<style>\"", CodeTokenKind.String)
        assertFalse(
            "注释与字符串里的 <style> 不应触发 CSS 分词",
            tokens.any { it.text(code) == "color" || it.text(code) == ".x" },
        )
    }

    private fun assertToken(
        tokens: List<CodeToken>,
        code: String,
        expected: String,
        kind: CodeTokenKind,
    ) {
        assertTrue(
            "缺少 $kind token: $expected，实际=${tokens.map { it.text(code) to it.kind }}",
            tokens.any { it.kind == kind && it.text(code) == expected },
        )
    }
}
