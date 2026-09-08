package io.github.mbackschat.a12.dm.buildlogic

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class KotlinSourceTest {

    private fun count(source: String) = KotlinSource.countInCode(source, "havingInterp(")

    @Test
    fun `a real call counts`() {
        assertEquals(1, count("if (!havingInterp(row).fires(having)) continue"))
    }

    @Test
    fun `a mention in a line comment does not count`() {
        // The measured false RED: prose naming the symbol failed a correct tree.
        assertEquals(1, count("""
            // route every consumer through havingInterp( rather than a direct loop
            if (!havingInterp(row).fires(having)) continue
        """.trimIndent()))
    }

    @Test
    fun `a commented-out call does not count`() {
        // The measured false GREEN, and the sharper direction: the only real call site was commented out and
        // the guard still reported exactly one owner.
        assertEquals(0, count("// if (!havingInterp(row).fires(having)) continue"))
    }

    @Test
    fun `a mention inside a block comment does not count, and Kotlin nests them`() {
        assertEquals(0, count("/* havingInterp( /* nested havingInterp( */ still comment */"))
        assertEquals(1, count("/* a /* b */ c */ havingInterp(x)"))
    }

    @Test
    fun `a mention inside a string does not count, in either string form`() {
        assertEquals(0, count("""val s = "havingInterp(" """))
        assertEquals(0, count("val s = \"\"\"havingInterp(\"\"\""))
    }

    @Test
    fun `a raw string ends at the LAST quote of its closing run`() {
        // `"""x""""` is the four-quote case: taking the first three leaves a stray quote that opens a
        // phantom string and swallows the rest of the file.
        assertEquals(1, count("val s = \"\"\"x\"\"\"\nhavingInterp(y)"))
        assertEquals(1, count("val s = \"\"\"x\"\"\"\"\nhavingInterp(y)"))
    }

    @Test
    fun `an escaped quote does not end the string`() {
        assertEquals(1, count("""val s = "a\"havingInterp( b"; havingInterp(x)"""))
    }

    @Test
    fun `a quote character literal does not open a string`() {
        // The estate has `'"'` in real sources; reading it as a string opener desynchronises the whole file.
        assertEquals(1, count("""val q = '"'; havingInterp(x)"""))
    }

    @Test
    fun `blanking preserves offsets so a line number is still recoverable`() {
        val source = "line one\n// a comment\nhavingInterp(x)\n"
        val blanked = KotlinSource.code(source)
        assertEquals(source.length, blanked.length)
        assertEquals(source.count { it == '\n' }, blanked.count { it == '\n' })
    }
}
