package io.github.mbackschat.a12.dm.interpreter

import io.github.mbackschat.a12.dm.interpreter.parse.parseCondition
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The kernel's canonical condition render (its `FormatterVisitor`, which the rulekit DSL renderer reproduces)
 * writes paths **relative to the rule's group**, so a cross-level UP reference — a `/Order/Lines/Subs`-scoped
 * rule reading the parent Line's `Qty` — renders **`../Qty`** (each `..` = one group level up). The interpreter
 * parser already handled the leading-`/` absolute form (IF24) and the bare same-group form; this locks the
 * missing **`../` parent-relative** case: `../Qty` from base `/Order/Lines/Subs` resolves to the same AST as the
 * absolute `/Order/Lines/Qty`, in BOTH a `[...]` value reference and a bare predicate / aggregate argument.
 *
 * INTERPRETER-GAPS-LEDGER IG1 — surfaced when the perf catalog moved to DSL-authored (group-relative) conditions
 * and `[../Qty]` choked the parser; a real kernel model storing the canonical relative form would hit the same.
 */
class ParentRelativePathParseTest {

    // Field-vs-field comparisons and presence predicates only — no NUMBER literal (its `Dec` compares by
    // identity, IG4, which would spuriously fail whole-AST `assertEquals`), exactly as AbsolutePathParseTest.

    @Test
    fun parentRelativeEqualsAbsoluteInPredicateAndValueRef() =
        assertEquals(
            parseCondition("FieldFilled(/Perf/Lines/Qty) And [/Perf/Lines/Subs/Cost] == [/Perf/Lines/Qty]", "/Perf/Lines/Subs"),
            parseCondition("FieldFilled(../Qty) And [Cost] == [../Qty]", "/Perf/Lines/Subs"),
        )

    @Test
    fun multipleParentLevels() =
        assertEquals(
            parseCondition("[/Perf/Total] == [/Perf/Lines/Subs/Cost]", "/Perf/Lines/Subs"),
            parseCondition("[../../Total] == [Cost]", "/Perf/Lines/Subs"),
        )

    @Test
    fun parentRelativeIntoSiblingSubtree() =
        assertEquals(
            parseCondition("[/Order/Billing/City] == [/Order/Lines/City]", "/Order/Lines"),
            parseCondition("[../Billing/City] == [City]", "/Order/Lines"),
        )

    @Test
    fun parentRelativeStarAggregate() =
        assertEquals(
            parseCondition("Sum(/Order/Discounts*/Amount) == Sum(/Order/Lines*/Amount)", "/Order/Lines"),
            parseCondition("Sum(../Discounts*/Amount) == Sum(../Lines*/Amount)", "/Order/Lines"),
        )
}
