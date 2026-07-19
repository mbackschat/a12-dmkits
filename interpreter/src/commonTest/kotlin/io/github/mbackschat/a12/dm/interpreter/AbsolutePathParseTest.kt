package io.github.mbackschat.a12.dm.interpreter

import io.github.mbackschat.a12.dm.interpreter.parse.parseCondition
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The kernel — and the rulekit DSL renderer ([io.github.mbackschat.a12.dm.rulekit.dsl.DslRenderer], the
 * canonical authoring path) — write a condition's field paths **absolutely**: `FieldFilled(/Pol/Cap0)`,
 * `AllFieldsFilled(/A/x, /B/y)`, a starred `Sum` over an absolute path. The interpreter's condition parser must accept those exactly as it
 * accepts the relative form: an absolute path resolves to itself, a relative one against the rule's `base`
 * group, and the two must yield the **same AST**.
 *
 * The parser already accepted an absolute path inside a `[...]` value reference (so cross-level walk-ups parsed);
 * this locks the previously-missing case — a bare absolute path as a **predicate / aggregate argument** — which
 * the perf benchmark surfaced the moment it moved from hand-typed (relative) strings to DSL-authored (absolute)
 * conditions. INTERPRETER-FINDINGS IF24.
 */
class AbsolutePathParseTest {

    // These cases use field-vs-field comparisons and presence predicates to isolate path parsing. Numeric literals
    // are also safe in whole-AST equality: `Dec` has scale-insensitive value equality since IF41.

    @Test
    fun absoluteEqualsRelativeInPredicateArgAndValueRef() =
        assertEquals(
            parseCondition("FieldFilled(Cap0) And [Cap0] == [Cap1]", "/Pol"),
            parseCondition("FieldFilled(/Pol/Cap0) And [/Pol/Cap0] == [/Pol/Cap1]", "/Pol"),
        )

    @Test
    fun absoluteFieldListPredicate() =
        assertEquals(
            parseCondition("AllFieldsFilled(A, B) And NoFieldFilled(A, B)", "/Pol/Con0"),
            parseCondition("AllFieldsFilled(/Pol/Con0/A, /Pol/Con0/B) And NoFieldFilled(/Pol/Con0/A, /Pol/Con0/B)", "/Pol/Con0"),
        )

    @Test
    fun absoluteStarAggregate() =
        assertEquals(
            parseCondition("Sum(Lines*/Amount) == Sum(Lines*/Cost)", "/Order"),
            parseCondition("Sum(/Order/Lines*/Amount) == Sum(/Order/Lines*/Cost)", "/Order"),
        )

    @Test
    fun absoluteNestedStarAggregate() =
        assertEquals(
            parseCondition("Sum(Lines*/Subs*/Cost) == Sum(Lines*/Subs*/Fee)", "/Order"),
            parseCondition("Sum(/Order/Lines*/Subs*/Cost) == Sum(/Order/Lines*/Subs*/Fee)", "/Order"),
        )
}
