package io.github.mbackschat.a12.dm.interpreter

import io.github.mbackschat.a12.dm.interpreter.parse.parseCondition
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * The kernel's lexer accepts three spellings for the `And`/`Or`/`In` keyword families and NOTHING else —
 * `LexerTerminals_en` enumerates them per-variant (`And`/`and`/`AND` = UND1/2/3, `Or`/`or`/`OR` = ODER1/2/3,
 * `In`/`in`/`IN` = IN1/2/3), and `inOp` in the grammar is exactly `'In' | 'IN' | 'in'`. Real stored conditions
 * use all three casings (the A12 Tools workspaces mix `and` and `AND` freely). This locks that the interpreter
 * parser accepts EXACTLY the enumerated variants — and rejects any other casing (`aNd`, `oR`, `In` misspelled),
 * so it stays a fixed table, not blanket case-folding.
 *
 * WI-P (SEMANTICS-FORMALIZATION-PROPOSAL, from Phase-0 S1; INTERPRETER-FINDINGS IF55). Presence predicates isolate
 * keyword spelling here; numeric literals are also safe in whole-AST equality because `Dec` has value semantics since IF41.
 */
class KeywordCaseVariantParseTest {

    @Test
    fun andVariantsAllParseToTheCanonicalAnd() {
        val canonical = parseCondition("FieldFilled(A) And FieldFilled(B)", "/G")
        assertEquals(canonical, parseCondition("FieldFilled(A) and FieldFilled(B)", "/G"))
        assertEquals(canonical, parseCondition("FieldFilled(A) AND FieldFilled(B)", "/G"))
    }

    @Test
    fun orVariantsAllParseToTheCanonicalOr() {
        val canonical = parseCondition("FieldFilled(A) Or FieldFilled(B)", "/G")
        assertEquals(canonical, parseCondition("FieldFilled(A) or FieldFilled(B)", "/G"))
        assertEquals(canonical, parseCondition("FieldFilled(A) OR FieldFilled(B)", "/G"))
    }

    @Test
    fun inVariantsAllParseInTheValueListQuantifier() {
        val canonical = parseCondition("""AtLeastOneFieldValueIncludedInValueList(A, B In "x", "y")""", "/G")
        assertEquals(canonical, parseCondition("""AtLeastOneFieldValueIncludedInValueList(A, B in "x", "y")""", "/G"))
        assertEquals(canonical, parseCondition("""AtLeastOneFieldValueIncludedInValueList(A, B IN "x", "y")""", "/G"))
    }

    @Test
    fun caseVariantsComposeAcrossTheWholeCondition() {
        // A real-corpus mix: lowercase `and` joining an uppercase-`OR` group.
        val canonical =
            parseCondition("FieldFilled(A) And (FieldFilled(B) Or FieldFilled(C))", "/G")
        assertEquals(canonical, parseCondition("FieldFilled(A) and (FieldFilled(B) OR FieldFilled(C))", "/G"))
    }

    @Test
    fun misspelledCasingIsRejected() {
        // Only the three enumerated spellings are keywords — any other casing is NOT `And`, so the second
        // predicate is trailing input the parser refuses (no blanket case-folding).
        assertFailsWith<IllegalArgumentException> {
            parseCondition("FieldFilled(A) aNd FieldFilled(B)", "/G")
        }
        assertFailsWith<IllegalArgumentException> {
            parseCondition("FieldFilled(A) oR FieldFilled(B)", "/G")
        }
    }
}
