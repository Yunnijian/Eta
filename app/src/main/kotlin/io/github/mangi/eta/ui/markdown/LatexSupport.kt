package io.github.mangi.eta.ui.markdown

import com.hrm.latex.parser.LatexParser

/** 行内公式在 AnnotatedString 里的标记；item 同时用作 InlineTextContent 的 id。 */
internal const val LATEX_INLINE_MATH_TAG = "eta-latex-inline"

/**
 * LaTeX 渲染前的可用性判定。聊天解析在后台线程完成，因此这里也会解析一遍：
 * 只有无错误的公式才交给 Compose 渲染，其余按原文显示，避免整条消息因单个坏公式崩溃。
 */
internal object LatexSupport {
    private val parser = LatexParser()

    fun isRenderable(latex: String): Boolean {
        val source = latex.trim()
        if (source.isEmpty()) return false
        // 金额与中文说明会被 GFM 当作行内公式，这里按普通文字还原。
        if (containsCjkOrFullWidth(source)) return false
        return runCatching {
            synchronized(parser) { parser.parseWithDiagnostics(source).errors.isEmpty() }
        }.getOrDefault(false)
    }

    private fun containsCjkOrFullWidth(source: String): Boolean = source.any { char ->
        val code = char.code
        Character.UnicodeScript.of(code) == Character.UnicodeScript.HAN ||
            code in 0x3000..0x303F ||
            code in 0xFF00..0xFFEF ||
            code == 0x2018 || code == 0x2019 || code == 0x201C || code == 0x201D
    }
}
