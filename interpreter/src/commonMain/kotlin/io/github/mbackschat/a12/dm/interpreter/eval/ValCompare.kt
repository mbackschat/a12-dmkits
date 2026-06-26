package io.github.mbackschat.a12.dm.interpreter.eval

import io.github.mbackschat.a12.dm.interpreter.ast.Val

/**
 * Pure runtime VALUE-comparison semantics over resolved [Val]s — AST-walk-agnostic, so a future code GENERATOR
 * could call them exactly as the tree-walking [Interpreter] does (the generator-readiness layering, IF10/IG5;
 * the runtime-semantics layer, alongside [DateMath]/[Kleene] and the operator registries). The kernel has TWO
 * comparison notions and so do we:
 *
 *  - [language] — the condition comparison (`BedingungsOperatorHelper.vergleiche`/`vergleicheSTRING`): a NUMBER
 *    pair is rounded to **scale 19** `HALF_UP` first (`VkBigDecimal.DEFAULT_SCALE`) — observable only beyond 19
 *    fractional digits (e.g. a scale-10 × scale-10 product); text/dates compare lexicographically (ISO dates →
 *    chronological). Drives `<`/`<=`/`>`/`>=` and the directional `!=` polarity.
 *  - [ORDER] — `min`/`max` **selection**, which the kernel does at FULL precision (no scale-19 round).
 *
 * Both throw on incomparable kinds; the [Interpreter] resolves/guards operands so only number↔number and
 * text↔text reach here.
 */
object ValCompare {

    /** The kernel's language comparison (scale-19 rounding for numbers). */
    fun language(l: Val, r: Val): Int = when {
        l is Val.Num && r is Val.Num -> l.v.compareScaled19(r.v)
        l is Val.Txt && r is Val.Txt -> l.v.compareTo(r.v)
        else -> error("incomparable values $l vs $r")
    }

    /** Orders comparable values for `min`/`max` selection: numbers numerically (full precision), text lexicographically. */
    val ORDER: Comparator<Val> = Comparator { a, b ->
        when {
            a is Val.Num && b is Val.Num -> a.v.compareTo(b.v)
            a is Val.Txt && b is Val.Txt -> a.v.compareTo(b.v)
            else -> error("incomparable values $a vs $b")
        }
    }
}
