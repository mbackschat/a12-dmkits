package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.interpreter.DmInterpreter
import io.github.mbackschat.a12.dm.interpreter.eval.newDocument
import io.github.mbackschat.a12.dm.interpreter.load.ModelLoader
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft.GroupBuilder
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel
import io.github.mbackschat.a12.dm.rulekit.dsl.Condition
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.util.function.Consumer

/**
 * Differential — a STARRED field-fill quantifier (`AtLeastOneFieldFilled(Lines* / Price)`) whose star cells are
 * present but formally INVALID (per-row) — IF84, the IG44 re-drill minimal. The kernel excludes a
 * not-check-relevant (formally invalid) cell from the fill tally — it counts as NEITHER filled nor empty
 * (`isWertAngegeben`, KERNEL-FINDINGS §3) — so `AtLeastOneFieldFilled` over rows whose only values are invalid is
 * FALSE. The interpreter's `Document.resolveValue` applied only the FORMAT/scale check, NOT the declared
 * CONSTRAINTS, so a constraint-violating-but-parseable cell resolved to a real value (not `Val.Unknown`) and the
 * `fillScope` fill tally (and aggregates) miscounted it as filled → the quantifier flipped TRUE, cascading a
 * wrong compute outcome (the CVV surfaced on a downstream `AtLeastOneFieldFilled(...) → warning` compute). Fixed
 * by threading the field constraints into `resolveValue` so a constraint-invalid value resolves to `Val.Unknown`
 * everywhere resolution runs — matching the direct-ref `invalid` gate.
 *
 * Own-domain (e-commerce): `Price` carries `minFractionalDigits = 2`, so a value with fewer fractional digits is
 * `zahlFalscheAnzahlNachkommastellen` — formally invalid though it parses as a number.
 */
class StarQuantifierInvalidCellDiffTest {

    private val PRICE = "/Cart/Lines/Price"
    private val ITEM = "/Cart/Lines/ItemId"

    private val base = DocumentModelDraft.named("cart").locale("en_US")
        .rootGroup("Cart", Consumer { g: GroupBuilder ->
            g.group("Lines", Consumer { l ->
                l.repeatable(99).indexField("ItemId")
                    .stringField("ItemId", Consumer { })
                    .numberField("Price", Consumer { it.fractionDigits(2, 2) })   // min=max=2 fractional digits
            })
        }).build().toJson()
    private val m = BoundModel.ofJson(base)
    private fun render(c: Condition): String = RuntimeLaws.render(c)

    /** Fire a `/Cart`-level rule `cond` over Lines with the given per-row Price values (null = row present, Price
     *  empty; ItemId is always filled so no mandatory intrudes) and assert both engines fire identically. */
    private fun agree(cond: String, prices: List<String?>, msg: String) {
        val kModel = RuntimeLaws.withRule(base, "/Cart", "Q", "../Lines/Price", "Q", cond)
        val kDoc = RuntimeLaws.doc().group("/Cart", 1)
        val def = ModelLoader.load(kModel)
        val iDoc = def.newDocument().group("/Cart", 1)
        prices.forEachIndexed { i, p ->
            val r = i + 1
            kDoc.group("/Cart/Lines", 1, r).field(ITEM, "K$r", 1, r, 1)
            iDoc.group("/Cart/Lines", 1, r).field(ITEM, "K$r", 1, r, 1)
            if (p != null) { kDoc.field(PRICE, p, 1, r, 1); iDoc.field(PRICE, p, 1, r, 1) }
        }
        val kFires = RuntimeLaws.typeOf(kModel, "Q", kDoc).isPresent
        val iFires = DmInterpreter(def).validateFull(iDoc).any { it.code == "Q" }
        assertEquals(kFires, iFires, "$msg  (kernel fires=$kFires interp fires=$iFires)")
    }

    private val atLeastOne = render(Dsl.atLeastOneFieldFilled(m.flatten("/Cart/Lines*/Price")))
    private val noField = render(Dsl.noFieldFilled(m.flatten("/Cart/Lines*/Price")))

    // Both Prices formally INVALID (fewer than min=2 fractional digits): the kernel excludes them → AtLeastOne FALSE.
    @Test fun atLeastOneOverAllInvalidCellsIsFalse() =
        agree(atLeastOne, listOf("5", "3.1"), "both Prices invalid (min-frac) → AtLeastOneFieldFilled FALSE → not fire")
    // One valid, one invalid → the valid one counts → AtLeastOne TRUE.
    @Test fun atLeastOneWithOneValidCellIsTrue() =
        agree(atLeastOne, listOf("5", "3.10"), "one valid Price (3.10) → AtLeastOneFieldFilled TRUE → fire")
    // All valid control.
    @Test fun atLeastOneAllValidIsTrue() =
        agree(atLeastOne, listOf("5.00", "3.10"), "both valid → AtLeastOneFieldFilled TRUE → fire")
    // Empty rows (Price absent, not invalid): AtLeastOne FALSE (control — empty ≠ invalid).
    @Test fun atLeastOneAllEmptyIsFalse() =
        agree(atLeastOne, listOf<String?>(null, null), "both Prices empty → AtLeastOneFieldFilled FALSE → not fire")
    // NoFieldFilled = (empty == all): an invalid cell is NEITHER filled nor empty, so all-invalid is NOT
    // all-empty → NoFieldFilled does NOT fire (the kernel counts invalid in `n` but not `f`).
    @Test fun noFieldFilledOverAllInvalidDoesNotFire() =
        agree(noField, listOf("5", "3.1"), "both Prices invalid → not-check-relevant ≠ empty → NoFieldFilled FALSE → not fire")
    // NoFieldFilled over all-EMPTY (f == n) → fires (control — empty IS counted).
    @Test fun noFieldFilledOverAllEmptyFires() =
        agree(noField, listOf<String?>(null, null), "both Prices empty → NoFieldFilled TRUE → fire")

    // AGGREGATE sibling: a `Sum` over a constraint-invalid element — a not-check-relevant element makes the
    // whole combiner non-evaluable (the kernel's `NumberCombiner`, AggMalformedDiffTest for malformed), so the
    // referencing rule is SUPPRESSED. `resolveValue` now resolves the invalid cell to Unknown, poisoning the Sum.
    private val sumGt = render(Dsl.sum(m.flatten("/Cart/Lines*/Price")).gt(100))
    @Test fun sumWithOneInvalidElementIsSuppressed() =
        agree(sumGt, listOf("741.89", "3.1"), "Sum over a min-frac-invalid element → non-evaluable → rule suppressed")
    @Test fun sumAllValidComputes() =
        agree(sumGt, listOf("741.89", "3.10"), "Sum over all-valid → 745.99 > 100 → fires")
}
