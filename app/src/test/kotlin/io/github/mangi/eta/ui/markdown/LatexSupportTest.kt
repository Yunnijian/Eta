package io.github.mangi.eta.ui.markdown

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LatexSupportTest {
    @Test
    fun casesEnvironmentsAreRenderable() {
        val source = """
            \begin{cases}
            3x + 2y = 12 \\
            x - y = 1
            \end{cases}
        """.trimIndent()

        assertTrue(LatexSupport.isRenderable(source))
    }

    @Test
    fun piecewiseCasesWithConditionsAreRenderable() {
        val source = """
            f(x) = \begin{cases}
            x^2 & x \ge 0 \\
            -x & x < 0
            \end{cases}
        """.trimIndent()

        assertTrue(LatexSupport.isRenderable(source))
    }

    @Test
    fun gatherEnvironmentIsRenderableWithoutTags() {
        assertTrue(LatexSupport.isRenderable("\\begin{gather}a = b + c \\\\ d = e + f\\end{gather}"))
    }

    @Test
    fun currencyStyleInlineMathIsNotTreatedAsFormula() {
        assertFalse(LatexSupport.isRenderable("5和"))
        assertTrue(LatexSupport.isRenderable("E = mc^2"))
    }
}
