package io.github.mangi.eta.ui.markdown

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle

/** 语法着色只区分聊天里最需要辨识的四类符号，颜色随主题切换。 */
@Immutable
internal data class CodeSyntaxPalette(
    val keyword: Color,
    val string: Color,
    val comment: Color,
    val number: Color,
) {
    fun colorFor(kind: CodeTokenKind): Color = when (kind) {
        CodeTokenKind.Keyword -> keyword
        CodeTokenKind.String -> string
        CodeTokenKind.Comment -> comment
        CodeTokenKind.Number -> number
    }

    companion object {
        val Light = CodeSyntaxPalette(
            keyword = Color(0xFFAF00DB),
            string = Color(0xFFA31515),
            comment = Color(0xFF008000),
            number = Color(0xFF098658),
        )
        val Dark = CodeSyntaxPalette(
            keyword = Color(0xFFC586C0),
            string = Color(0xFFCE9178),
            comment = Color(0xFF6A9955),
            number = Color(0xFFB5CEA8),
        )
    }
}

internal enum class CodeTokenKind { Keyword, String, Comment, Number }

internal data class CodeToken(val start: Int, val end: Int, val kind: CodeTokenKind) {
    fun text(source: String): String = source.substring(start, end)
}

/**
 * 原生词法着色：只对已知语言做一次线性扫描，未知语言返回 null 由调用方保持纯文本。
 *
 * 聊天里的代码块可能非常多，若每个块都挂 WebView 跑 highlight.js，长会话的排版与
 * 内存开销都不可接受；这里用关键字/字符串/注释/数字四类 token 覆盖常见语言，
 * 关键字表对齐 highlight.js 的 common 语言包。
 */
internal object CodeHighlighter {
    fun highlight(code: String, language: String?, palette: CodeSyntaxPalette): AnnotatedString? {
        val tokens = tokenizeCode(code, language) ?: return null
        if (tokens.isEmpty()) return null
        val builder = AnnotatedString.Builder(code)
        tokens.forEach { token ->
            builder.addStyle(SpanStyle(color = palette.colorFor(token.kind)), token.start, token.end)
        }
        return builder.toAnnotatedString()
    }
}

internal fun tokenizeCode(code: String, language: String?): List<CodeToken>? {
    val normalized = normalizeCodeLanguage(language) ?: return null
    return when (normalized) {
        "markup" -> MarkupLexer(code).run()
        "css" -> CssLexer(code).run()
        else -> languageSpec(normalized)?.let { CodeLexer(code, it).run() }
    }
}

internal fun normalizeCodeLanguage(language: String?): String? {
    val tag = language?.trim()?.lowercase()?.substringBefore(' ') ?: return null
    if (tag.isEmpty()) return null
    return when (tag) {
        "kt", "kts", "kotlin" -> "kotlin"
        "py", "py3", "python" -> "python"
        "js", "jsx", "javascript", "node" -> "javascript"
        "ts", "tsx", "typescript" -> "typescript"
        "java" -> "java"
        "c", "h" -> "c"
        "cc", "cpp", "c++", "hpp" -> "cpp"
        "cs", "c#", "csharp" -> "csharp"
        "go", "golang" -> "go"
        "rs", "rust" -> "rust"
        "swift" -> "swift"
        "dart" -> "dart"
        "php" -> "php"
        "rb", "ruby" -> "ruby"
        "json", "jsonc" -> "json"
        "xml", "html", "xhtml", "svg", "vue" -> "markup"
        "css", "scss", "less" -> "css"
        "sh", "bash", "shell", "zsh", "console" -> "bash"
        "sql" -> "sql"
        "yml", "yaml" -> "yaml"
        "toml", "ini" -> "toml"
        "lua" -> "lua"
        else -> null
    }
}

private data class LanguageSpec(
    val keywords: Set<String>,
    val lineComments: List<String>,
    val blockComment: Pair<String, String>?,
    val strings: List<String>,
    val multiLineStrings: List<String> = emptyList(),
    val caseInsensitive: Boolean = false,
    val annotations: Boolean = false,
)

private fun languageSpec(language: String): LanguageSpec? = when (language) {
    "kotlin" -> LanguageSpec(
        keywords = KOTLIN_KEYWORDS,
        lineComments = listOf("//"),
        blockComment = "/*" to "*/",
        strings = listOf("\"\"\"", "\"", "'"),
        multiLineStrings = listOf("\"\"\""),
        annotations = true,
    )
    "java" -> LanguageSpec(
        keywords = JAVA_KEYWORDS,
        lineComments = listOf("//"),
        blockComment = "/*" to "*/",
        strings = listOf("\"\"\"", "\"", "'"),
        multiLineStrings = listOf("\"\"\""),
        annotations = true,
    )
    "javascript", "typescript" -> LanguageSpec(
        keywords = JS_KEYWORDS + if (language == "typescript") TS_KEYWORDS else emptySet(),
        lineComments = listOf("//"),
        blockComment = "/*" to "*/",
        strings = listOf("`", "\"", "'"),
        multiLineStrings = listOf("`"),
    )
    "c", "cpp", "csharp", "go", "rust", "swift", "dart" -> LanguageSpec(
        keywords = when (language) {
            "c", "cpp" -> C_KEYWORDS
            "csharp" -> CSHARP_KEYWORDS
            "go" -> GO_KEYWORDS
            "rust" -> RUST_KEYWORDS
            "swift" -> SWIFT_KEYWORDS
            else -> DART_KEYWORDS
        },
        lineComments = listOf("//"),
        blockComment = "/*" to "*/",
        strings = listOf("\"\"\"", "\"", "'"),
        multiLineStrings = listOf("\"\"\""),
    )
    "python" -> LanguageSpec(
        keywords = PYTHON_KEYWORDS,
        lineComments = listOf("#"),
        blockComment = null,
        strings = listOf("\"\"\"", "'''", "\"", "'"),
        multiLineStrings = listOf("\"\"\"", "'''"),
        annotations = true,
    )
    "ruby" -> LanguageSpec(
        keywords = RUBY_KEYWORDS,
        lineComments = listOf("#"),
        blockComment = null,
        strings = listOf("\"", "'"),
    )
    "php" -> LanguageSpec(
        keywords = PHP_KEYWORDS,
        lineComments = listOf("//", "#"),
        blockComment = "/*" to "*/",
        strings = listOf("\"", "'"),
    )
    "bash" -> LanguageSpec(
        keywords = BASH_KEYWORDS,
        lineComments = listOf("#"),
        blockComment = null,
        strings = listOf("\"", "'", "`"),
    )
    "json" -> LanguageSpec(
        keywords = setOf("true", "false", "null"),
        lineComments = emptyList(),
        blockComment = null,
        strings = listOf("\""),
    )
    "sql" -> LanguageSpec(
        keywords = SQL_KEYWORDS,
        lineComments = listOf("--"),
        blockComment = "/*" to "*/",
        strings = listOf("\"", "'"),
        caseInsensitive = true,
    )
    "yaml", "toml" -> LanguageSpec(
        keywords = setOf("true", "false", "null", "yes", "no"),
        lineComments = listOf("#"),
        blockComment = null,
        strings = listOf("\"\"\"", "\"", "'"),
        multiLineStrings = listOf("\"\"\""),
    )
    "lua" -> LanguageSpec(
        keywords = LUA_KEYWORDS,
        lineComments = listOf("--"),
        blockComment = null,
        strings = listOf("\"", "'"),
    )
    else -> null
}

private class CodeLexer(private val code: String, private val spec: LanguageSpec) {
    private val tokens = ArrayList<CodeToken>()
    private val delimiters = spec.strings.sortedByDescending(String::length)

    fun run(): List<CodeToken> {
        var index = 0
        while (index < code.length) {
            val char = code[index]
            if (char.isWhitespace()) {
                index += 1
                continue
            }
            val commentEnd = lineCommentEnd(index)
            if (commentEnd != null) {
                tokens += CodeToken(index, commentEnd, CodeTokenKind.Comment)
                index = commentEnd
                continue
            }
            val block = spec.blockComment
            if (block != null && code.startsWith(block.first, index)) {
                val end = code.indexOf(block.second, index + block.first.length)
                    .let { if (it < 0) code.length else it + block.second.length }
                tokens += CodeToken(index, end, CodeTokenKind.Comment)
                index = end
                continue
            }
            val delimiter = delimiters.firstOrNull { code.startsWith(it, index) }
            if (delimiter != null) {
                val end = scanString(index, delimiter)
                tokens += CodeToken(index, end, CodeTokenKind.String)
                index = end
                continue
            }
            if (char.isDigit()) {
                val end = scanNumber(index)
                tokens += CodeToken(index, end, CodeTokenKind.Number)
                index = end
                continue
            }
            if (char.isLetter() || char == '_') {
                val end = scanIdentifier(index)
                val word = code.substring(index, end)
                val keyword = if (spec.caseInsensitive) {
                    spec.keywords.any { it.equals(word, ignoreCase = true) }
                } else {
                    word in spec.keywords
                }
                if (keyword) tokens += CodeToken(index, end, CodeTokenKind.Keyword)
                index = end
                continue
            }
            if (spec.annotations && char == '@') {
                val end = if (index + 1 < code.length && code[index + 1].isLetter()) {
                    scanIdentifier(index + 1)
                } else {
                    index + 1
                }
                tokens += CodeToken(index, end, CodeTokenKind.Keyword)
                index = end
                continue
            }
            index += 1
        }
        return tokens
    }

    private fun lineCommentEnd(start: Int): Int? {
        val marker = spec.lineComments.firstOrNull { code.startsWith(it, start) } ?: return null
        var end = code.indexOf('\n', start + marker.length)
        if (end < 0) end = code.length
        return end
    }

    private fun scanString(start: Int, delimiter: String): Int {
        val multiLine = delimiter in spec.multiLineStrings
        var index = start + delimiter.length
        while (index < code.length) {
            val char = code[index]
            if (char == '\\' && !multiLine) {
                index += 2
                continue
            }
            if (code.startsWith(delimiter, index)) return index + delimiter.length
            if (!multiLine && (char == '\n' || char == '\r')) return index
            index += 1
        }
        return code.length
    }

    private fun scanIdentifier(start: Int): Int {
        var index = start
        while (index < code.length) {
            val char = code[index]
            if (char.isLetterOrDigit() || char == '_') index += 1 else break
        }
        return index
    }

    private fun scanNumber(start: Int): Int {
        var index = start
        if (code[index] == '0' && index + 1 < code.length) {
            when (code[index + 1]) {
                'x', 'X', 'b', 'B', 'o', 'O' -> {
                    index += 2
                    while (index < code.length && (code[index].isLetterOrDigit() || code[index] == '_')) {
                        index += 1
                    }
                    return index
                }
            }
        }
        while (index < code.length && (code[index].isDigit() || code[index] == '_')) index += 1
        if (index + 1 < code.length && code[index] == '.' && code[index + 1].isDigit()) {
            index += 1
            while (index < code.length && (code[index].isDigit() || code[index] == '_')) index += 1
        }
        if (index < code.length && (code[index] == 'e' || code[index] == 'E')) {
            var cursor = index + 1
            if (cursor < code.length && (code[cursor] == '+' || code[cursor] == '-')) cursor += 1
            if (cursor < code.length && code[cursor].isDigit()) {
                index = cursor
                while (index < code.length && code[index].isDigit()) index += 1
            }
        }
        while (index < code.length && code[index] in "fFdDlLuU") index += 1
        return index
    }
}

/**
 * HTML/XML 只着色标签名、属性值与注释；`<style>` 与 `<script>` 的正文交给对应词法器。
 *
 * 子语言切换按标签名（大小写不敏感）判断：遇到开标签后找完整的闭合标签，对区间内容
 * 单独分词再平移偏移。注释先整段跳过、属性值在标签扫描阶段已被引号包住，因此
 * 字符串或注释里出现的 `<style>` 字样不会触发切换。
 */
private class MarkupLexer(private val code: String) {
    private val tokens = ArrayList<CodeToken>()

    fun run(): List<CodeToken> {
        var index = 0
        while (index < code.length) {
            if (code.startsWith("<!--", index)) {
                val end = code.indexOf("-->", index + 4).let { if (it < 0) code.length else it + 3 }
                tokens += CodeToken(index, end, CodeTokenKind.Comment)
                index = end
                continue
            }
            if (code[index] == '<') {
                val tagEnd = findTagEnd(index)
                var cursor = index + 1
                val closing = cursor < code.length && code[cursor] == '/'
                while (cursor < tagEnd && (code[cursor] == '/' || code[cursor] == '!' || code[cursor] == '?')) {
                    cursor += 1
                }
                val nameStart = cursor
                while (cursor < tagEnd && (code[cursor].isLetterOrDigit() || code[cursor] == '-' || code[cursor] == ':')) {
                    cursor += 1
                }
                val tagName = if (cursor > nameStart) code.substring(nameStart, cursor).lowercase() else ""
                if (cursor > nameStart) tokens += CodeToken(nameStart, cursor, CodeTokenKind.Keyword)
                while (cursor < tagEnd) {
                    val char = code[cursor]
                    if (char == '"' || char == '\'') {
                        val end = scanQuoted(cursor, char, tagEnd)
                        tokens += CodeToken(cursor, end, CodeTokenKind.String)
                        cursor = end
                    } else {
                        cursor += 1
                    }
                }
                val selfClosing = tagEnd > index && code[tagEnd - 1] == '/'
                index = tagEnd + 1
                if (!closing && !selfClosing && (tagName == "style" || tagName == "script")) {
                    val closeStart = findClosingTag(tagName, index)
                    val contentEnd = if (closeStart >= 0) closeStart else code.length
                    tokens += lexEmbedded(index, contentEnd, if (tagName == "style") "css" else "javascript")
                    index = contentEnd
                }
                continue
            }
            index += 1
        }
        return tokens
    }

    private fun lexEmbedded(start: Int, end: Int, language: String): List<CodeToken> {
        if (end <= start) return emptyList()
        val fragment = code.substring(start, end)
        val nested = when (language) {
            "css" -> CssLexer(fragment).run()
            else -> languageSpec("javascript")?.let { CodeLexer(fragment, it).run() }.orEmpty()
        }
        return nested.map { CodeToken(it.start + start, it.end + start, it.kind) }
    }

    /** 闭合标签名大小写不敏感，允许 `</style >`、`</style data-x="1">` 这类变体。 */
    private fun findClosingTag(name: String, from: Int): Int {
        var index = from
        while (index < code.length) {
            val start = code.indexOf("</", index)
            if (start < 0) return -1
            val nameStart = start + 2
            val nameEnd = nameStart + name.length
            if (nameEnd <= code.length && code.regionMatches(nameStart, name, 0, name.length, ignoreCase = true)) {
                val next = code.getOrNull(nameEnd)
                if (next == null || next.isWhitespace() || next == '>' || next == '/') {
                    if (closingTagEnd(nameEnd) >= 0) return start
                }
            }
            index = start + 2
        }
        return -1
    }

    private fun closingTagEnd(from: Int): Int {
        var index = from
        var quote: Char? = null
        while (index < code.length) {
            val char = code[index]
            when {
                quote != null && char == quote -> quote = null
                quote == null && (char == '"' || char == '\'') -> quote = char
                quote == null && char == '>' -> return index
            }
            index += 1
        }
        return -1
    }

    private fun findTagEnd(start: Int): Int {
        var index = start + 1
        var quote: Char? = null
        while (index < code.length) {
            val char = code[index]
            when {
                quote != null && char == quote -> quote = null
                quote == null && (char == '"' || char == '\'') -> quote = char
                quote == null && char == '>' -> return index
            }
            index += 1
        }
        return code.length - 1
    }

    private fun scanQuoted(start: Int, quote: Char, limit: Int): Int {
        var index = start + 1
        while (index < limit) {
            if (code[index] == quote) return index + 1
            index += 1
        }
        return limit
    }
}

/**
 * CSS：选择器、属性名、值关键字与函数名统一使用关键字色，十六进制颜色与数字单位
 * 使用数字色，字符串与注释各用本来的颜色。标点（大括号、分号、冒号）保持默认色，
 * 与 highlight.js 的 css 语法一致。
 *
 * 用花括号层级维护「选择器 / 声明」两种上下文：@media、@supports、@keyframes 等
 * 嵌套块内部仍是选择器，其余块内部是声明；声明块里紧跟冒号的标识符即属性名。
 */
private class CssLexer(private val code: String) {
    private val tokens = ArrayList<CodeToken>()
    private val modes = ArrayDeque<Mode>()

    private enum class Mode { Selectors, Declarations }

    fun run(): List<CodeToken> {
        modes.addLast(Mode.Selectors)
        var parenDepth = 0
        var pendingAtRule: String? = null
        var index = 0
        while (index < code.length) {
            val char = code[index]
            when {
                char.isWhitespace() -> index += 1
                code.startsWith("/*", index) -> {
                    val end = code.indexOf("*/", index + 2).let { if (it < 0) code.length else it + 2 }
                    tokens += CodeToken(index, end, CodeTokenKind.Comment)
                    index = end
                }
                char == '"' || char == '\'' -> {
                    val end = scanString(index, char)
                    tokens += CodeToken(index, end, CodeTokenKind.String)
                    index = end
                }
                char == '{' -> {
                    val nested = pendingAtRule != null && pendingAtRule in NESTED_AT_RULES
                    modes.addLast(if (nested) Mode.Selectors else Mode.Declarations)
                    pendingAtRule = null
                    index += 1
                }
                char == '}' -> {
                    if (modes.size > 1) modes.removeLast()
                    pendingAtRule = null
                    index += 1
                }
                char == '(' -> {
                    parenDepth += 1
                    index += 1
                }
                char == ')' -> {
                    if (parenDepth > 0) parenDepth -= 1
                    index += 1
                }
                char == ';' || char == ',' -> {
                    pendingAtRule = null
                    index += 1
                }
                char == '@' -> {
                    val end = scanIdentifier(index + 1)
                    if (end > index + 1) {
                        tokens += CodeToken(index, end, CodeTokenKind.Keyword)
                        pendingAtRule = code.substring(index + 1, end).lowercase()
                        index = end
                    } else {
                        index += 1
                    }
                }
                char == '#' -> {
                    val hexEnd = if (modes.last() == Mode.Declarations) hexColorEnd(index) else null
                    if (hexEnd != null) {
                        tokens += CodeToken(index, hexEnd, CodeTokenKind.Number)
                        index = hexEnd
                    } else {
                        val end = scanIdentifier(index + 1)
                        if (end > index + 1) {
                            tokens += CodeToken(index, end, CodeTokenKind.Keyword)
                            index = end
                        } else {
                            index += 1
                        }
                    }
                }
                isNumberStart(index) -> {
                    val end = scanNumber(index)
                    tokens += CodeToken(index, end, CodeTokenKind.Number)
                    index = end
                }
                char == '!' -> {
                    var cursor = index + 1
                    while (cursor < code.length && code[cursor].isWhitespace()) cursor += 1
                    val end = scanIdentifier(cursor)
                    if (end > cursor) {
                        tokens += CodeToken(index, end, CodeTokenKind.Keyword)
                        index = end
                    } else {
                        index += 1
                    }
                }
                char == '.' || char == ':' -> {
                    if (modes.last() == Mode.Selectors) {
                        var cursor = index + 1
                        if (char == ':' && cursor < code.length && code[cursor] == ':') cursor += 1
                        val end = scanIdentifier(cursor)
                        if (end > cursor) {
                            tokens += CodeToken(index, end, CodeTokenKind.Keyword)
                            index = end
                        } else {
                            index += 1
                        }
                    } else {
                        index += 1
                    }
                }
                isIdentStart(index) -> {
                    val end = scanIdentifier(index)
                    val word = code.substring(index, end).lowercase()
                    val keyword = when {
                        modes.last() == Mode.Selectors -> true
                        parenDepth == 0 && nextSignificant(end) == ':' -> true
                        nextSignificant(end) == '(' -> true
                        word in CSS_VALUE_KEYWORDS -> true
                        else -> false
                    }
                    if (keyword) tokens += CodeToken(index, end, CodeTokenKind.Keyword)
                    index = end
                }
                else -> index += 1
            }
        }
        return tokens
    }

    private fun scanString(start: Int, quote: Char): Int {
        var index = start + 1
        while (index < code.length) {
            when (code[index]) {
                '\\' -> index += 2
                quote -> return index + 1
                '\n' -> return index
                else -> index += 1
            }
        }
        return code.length
    }

    private fun scanIdentifier(start: Int): Int {
        var index = start
        while (index < code.length) {
            val char = code[index]
            if (char.isLetterOrDigit() || char == '_' || char == '-' || char.code >= 0x80) index += 1 else break
        }
        return index
    }

    private fun isIdentStart(index: Int): Boolean {
        val char = code[index]
        if (char.isLetter() || char == '_' || char.code >= 0x80) return true
        if (char != '-') return false
        val next = code.getOrNull(index + 1) ?: return false
        return next.isLetter() || next == '_' || next == '-' || next.code >= 0x80
    }

    private fun isNumberStart(index: Int): Boolean {
        val char = code[index]
        if (char.isDigit()) return true
        val next = code.getOrNull(index + 1)
        if (char == '.') return next?.isDigit() == true
        if (char == '-') {
            return next?.isDigit() == true ||
                (next == '.' && code.getOrNull(index + 2)?.isDigit() == true)
        }
        return false
    }

    private fun scanNumber(start: Int): Int {
        var index = start
        if (code[index] == '-' || code[index] == '+') index += 1
        while (index < code.length && code[index].isDigit()) index += 1
        if (index < code.length && code[index] == '.' && code.getOrNull(index + 1)?.isDigit() == true) {
            index += 1
            while (index < code.length && code[index].isDigit()) index += 1
        }
        if (index < code.length && code[index] == '%') return index + 1
        while (index < code.length && code[index].isLetter()) index += 1
        return index
    }

    /** `#fff`、`#667eea`、`#3a5ce5` 整串着色；长度不合法时按 ID 选择器处理。 */
    private fun hexColorEnd(start: Int): Int? {
        var index = start + 1
        val begin = index
        while (index < code.length && code[index].isHexDigit()) index += 1
        val length = index - begin
        if (length != 3 && length != 4 && length != 6 && length != 8) return null
        val next = code.getOrNull(index)
        if (next != null && (next.isLetterOrDigit() || next == '_' || next == '-')) return null
        return index
    }

    private fun nextSignificant(from: Int): Char? {
        var index = from
        while (index < code.length) {
            if (!code[index].isWhitespace()) return code[index]
            index += 1
        }
        return null
    }

    private fun Char.isHexDigit(): Boolean =
        this in '0'..'9' || this in 'a'..'f' || this in 'A'..'F'

    private companion object {
        val NESTED_AT_RULES = setOf("media", "supports", "layer", "container", "keyframes", "scope")
    }
}

private val KOTLIN_KEYWORDS = setOf(
    "as", "break", "class", "continue", "do", "else", "false", "for", "fun", "if", "in",
    "interface", "is", "null", "object", "package", "return", "super", "this", "throw",
    "true", "try", "typealias", "typeof", "val", "var", "when", "while", "by", "catch",
    "constructor", "delegate", "dynamic", "field", "file", "finally", "get", "import",
    "init", "param", "property", "receiver", "set", "setparam", "where", "actual",
    "abstract", "annotation", "companion", "const", "crossinline", "data", "enum",
    "expect", "external", "final", "infix", "inline", "inner", "internal", "lateinit",
    "noinline", "open", "operator", "out", "override", "private", "protected", "public",
    "reified", "sealed", "suspend", "tailrec", "vararg", "it",
)

private val JAVA_KEYWORDS = setOf(
    "abstract", "assert", "boolean", "break", "byte", "case", "catch", "char", "class",
    "const", "continue", "default", "do", "double", "else", "enum", "extends", "final",
    "finally", "float", "for", "goto", "if", "implements", "import", "instanceof", "int",
    "interface", "long", "native", "new", "package", "private", "protected", "public",
    "return", "short", "static", "strictfp", "super", "switch", "synchronized", "this",
    "throw", "throws", "transient", "try", "void", "volatile", "while", "true", "false",
    "null", "var", "record", "sealed", "permits", "yield",
)

private val JS_KEYWORDS = setOf(
    "as", "async", "await", "break", "case", "catch", "class", "const", "continue",
    "debugger", "default", "delete", "do", "else", "export", "extends", "finally", "for",
    "from", "function", "if", "import", "in", "instanceof", "let", "new", "of", "return",
    "static", "super", "switch", "this", "throw", "try", "typeof", "var", "void", "while",
    "with", "yield", "true", "false", "null", "undefined", "get", "set",
)

private val TS_KEYWORDS = setOf(
    "abstract", "any", "boolean", "declare", "enum", "implements", "interface", "keyof",
    "namespace", "never", "number", "object", "private", "protected", "public", "readonly",
    "string", "type", "unknown", "is", "asserts", "infer", "satisfies", "override",
)

private val C_KEYWORDS = setOf(
    "auto", "break", "case", "char", "const", "continue", "default", "do", "double",
    "else", "enum", "extern", "float", "for", "goto", "if", "inline", "int", "long",
    "register", "restrict", "return", "short", "signed", "sizeof", "static", "struct",
    "switch", "typedef", "union", "unsigned", "void", "volatile", "while", "class",
    "namespace", "new", "delete", "public", "private", "protected", "template",
    "typename", "using", "virtual", "override", "constexpr", "nullptr", "true", "false",
    "bool", "throw", "try", "catch", "this",
)

private val CSHARP_KEYWORDS = setOf(
    "abstract", "as", "base", "bool", "break", "byte", "case", "catch", "char", "class",
    "const", "continue", "decimal", "default", "delegate", "do", "double", "else", "enum",
    "event", "explicit", "extern", "false", "finally", "fixed", "float", "for", "foreach",
    "get", "goto", "if", "implicit", "in", "int", "interface", "internal", "is", "lock",
    "long", "namespace", "new", "null", "object", "operator", "out", "override", "params",
    "private", "protected", "public", "readonly", "ref", "return", "sbyte", "sealed",
    "set", "short", "sizeof", "stackalloc", "static", "string", "struct", "switch", "this",
    "throw", "true", "try", "typeof", "uint", "ulong", "unchecked", "unsafe", "ushort",
    "using", "var", "virtual", "void", "volatile", "while", "async", "await", "record",
)

private val GO_KEYWORDS = setOf(
    "break", "case", "chan", "const", "continue", "default", "defer", "else", "fallthrough",
    "for", "func", "go", "goto", "if", "import", "interface", "map", "package", "range",
    "return", "select", "struct", "switch", "type", "var", "true", "false", "nil", "iota",
)

private val RUST_KEYWORDS = setOf(
    "as", "async", "await", "break", "const", "continue", "crate", "dyn", "else", "enum",
    "extern", "false", "fn", "for", "if", "impl", "in", "let", "loop", "match", "mod",
    "move", "mut", "pub", "ref", "return", "self", "Self", "static", "struct", "super",
    "trait", "true", "type", "unsafe", "use", "where", "while", "union",
)

private val SWIFT_KEYWORDS = setOf(
    "associatedtype", "class", "deinit", "enum", "extension", "fileprivate", "func",
    "import", "init", "inout", "internal", "let", "open", "operator", "private",
    "protocol", "public", "static", "struct", "subscript", "typealias", "var", "break",
    "case", "continue", "default", "defer", "do", "else", "fallthrough", "for", "guard",
    "if", "in", "repeat", "return", "switch", "where", "while", "as", "catch", "false",
    "is", "nil", "rethrows", "super", "self", "throw", "throws", "true", "try", "async",
    "await", "some", "any",
)

private val DART_KEYWORDS = setOf(
    "abstract", "as", "assert", "async", "await", "break", "case", "catch", "class",
    "const", "continue", "covariant", "default", "deferred", "do", "dynamic", "else",
    "enum", "export", "extends", "extension", "external", "factory", "false", "final",
    "finally", "for", "Function", "get", "hide", "if", "implements", "import", "in",
    "interface", "is", "late", "library", "mixin", "new", "null", "on", "operator", "part",
    "required", "rethrow", "return", "set", "show", "static", "super", "switch", "sync",
    "this", "throw", "true", "try", "typedef", "var", "void", "while", "with", "yield",
)

private val PYTHON_KEYWORDS = setOf(
    "False", "None", "True", "and", "as", "assert", "async", "await", "break", "class",
    "continue", "def", "del", "elif", "else", "except", "finally", "for", "from", "global",
    "if", "import", "in", "is", "lambda", "nonlocal", "not", "or", "pass", "raise",
    "return", "try", "while", "with", "yield", "match", "case", "self", "cls",
)

private val RUBY_KEYWORDS = setOf(
    "alias", "and", "begin", "break", "case", "class", "def", "defined?", "do", "else",
    "elsif", "end", "ensure", "false", "for", "if", "in", "module", "next", "nil", "not",
    "or", "redo", "rescue", "retry", "return", "self", "super", "then", "true", "undef",
    "unless", "until", "when", "while", "yield",
)

private val PHP_KEYWORDS = setOf(
    "abstract", "and", "array", "as", "break", "callable", "case", "catch", "class",
    "clone", "const", "continue", "declare", "default", "do", "echo", "else", "elseif",
    "empty", "enddeclare", "endfor", "endforeach", "endif", "endswitch", "endwhile",
    "eval", "exit", "extends", "final", "finally", "fn", "for", "foreach", "function",
    "global", "goto", "if", "implements", "include", "instanceof", "insteadof",
    "interface", "isset", "list", "match", "namespace", "new", "or", "print", "private",
    "protected", "public", "readonly", "require", "return", "static", "switch", "throw",
    "trait", "try", "unset", "use", "var", "while", "xor", "yield", "true", "false", "null",
)

private val BASH_KEYWORDS = setOf(
    "if", "then", "else", "elif", "fi", "for", "while", "until", "do", "done", "case",
    "esac", "function", "in", "select", "time", "coproc", "return", "exit", "local",
    "export", "readonly", "declare", "typeset", "unset", "shift", "set", "source",
    "alias", "unalias", "echo", "printf", "read", "cd", "pwd", "true", "false",
)

/** 值关键字，对齐 highlight.js 的 css 语法；大小写不敏感匹配。 */
private val CSS_VALUE_KEYWORDS = setOf(
    "absolute", "all", "auto", "backwards", "baseline", "block", "bold", "bolder", "border-box",
    "both", "bottom", "capitalize", "center", "collapse", "column", "contain", "content-box",
    "cover", "currentcolor", "dashed", "default", "dotted", "double", "ease", "ease-in",
    "ease-in-out", "ease-out", "end", "fixed", "flex", "flex-end", "flex-start", "forwards",
    "grid", "groove", "hidden", "inherit", "initial", "inline", "inline-block", "inline-flex",
    "inline-grid", "inset", "italic", "justify", "large", "larger", "left", "lighter",
    "line-through", "linear", "local", "lowercase", "medium", "middle", "monospace", "none",
    "no-repeat", "normal", "nowrap", "oblique", "outset", "overline", "paused", "pointer",
    "relative", "repeat", "revert", "revert-layer", "ridge", "right", "row", "running",
    "sans-serif", "scroll", "serif", "small", "smaller", "solid", "space-around",
    "space-between", "space-evenly", "start", "static", "step-end", "step-start", "sticky",
    "stretch", "thick", "thin", "to", "top", "transparent", "underline", "unset", "uppercase",
    "visible", "wrap", "x-large", "x-small", "xx-large", "xx-small",
)

private val SQL_KEYWORDS = setOf(
    "select", "from", "where", "and", "or", "not", "insert", "into", "values", "update",
    "set", "delete", "create", "table", "alter", "drop", "index", "view", "join", "left",
    "right", "inner", "outer", "on", "as", "group", "by", "order", "having", "limit",
    "offset", "distinct", "union", "all", "case", "when", "then", "else", "end", "null",
    "is", "in", "exists", "between", "like", "primary", "key", "foreign", "references",
    "default", "unique", "check", "constraint", "int", "integer", "varchar", "text",
    "date", "timestamp", "boolean", "true", "false", "begin", "commit", "rollback",
)

private val LUA_KEYWORDS = setOf(
    "and", "break", "do", "else", "elseif", "end", "false", "for", "function", "goto",
    "if", "in", "local", "nil", "not", "or", "repeat", "return", "then", "true", "until",
    "while",
)
