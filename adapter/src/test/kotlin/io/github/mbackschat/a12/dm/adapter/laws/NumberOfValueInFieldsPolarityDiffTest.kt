package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.interpreter.DmInterpreter
import io.github.mbackschat.a12.dm.interpreter.eval.newDocument
import io.github.mbackschat.a12.dm.interpreter.load.ModelLoader
import io.github.mbackschat.a12.dm.interpreter.model.MsgType
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel
import io.github.mbackschat.a12.dm.rulekit.dsl.ConditionLanguage
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl
import io.github.mbackschat.a12.dm.rulekit.dsl.DslRenderer
import io.github.mbackschat.a12.dm.rulekit.dsl.NumberExpr
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Differential + tri-check (groovy-dynamic == java-static == interpreter) — the POLARITY of a fired
 * comparison against a `Having`-FILTERED `NumberOfValueInFields` count (the SPEC-2026-07-23-12 separators).
 *
 * The kernel's value count (`RuntimeController.anzahlWertInFeldlisteIntern`) mirrors the entity-count
 * `containsFilteredValue` rule (IF114) but with a value-match gate: a filtered operand makes the count
 * **growable** unconditionally (a filter is present), yet **shrinkable** only when a currently counted cell
 * that MATCHED the constant came through that filter. So the two separators, both stored BELOW the count:
 *  - a filtered current MATCH → `(grow, shrink)` → OMISSION (the count can shrink to the stored value);
 *  - a filtered selected NON-match → grow-only zero → VALUE (the count cannot shrink; only the value helps).
 * Stored ABOVE, both are OMISSION (the count can grow). An empty/omitted-tail cell keeps it growable; the
 * first reached formal unavailability suppresses the whole count.
 *
 * Own domain (e-commerce order): `Count` vs a filtered `NumberOfValueInFields(1 In Items* / Qty Having …)`.
 */
class NumberOfValueInFieldsPolarityDiffTest {

    private companion object {
        const val COUNT = "/Order/Count"
        const val QTY = "/Order/Items/Qty"
        const val FLAG = "/Order/Items/Flag"
        const val ITEMS = "/Order/Items"
    }

    private val model = DocumentModelDraft.named("nvcount").locale("en_US")
        .rootGroup("Order") { o ->
            o.numberField("Count") { it.fractionDigits(0, 0) }
            o.group("Items") { i ->
                i.repeatable(10).numberField("Qty") { it.fractionDigits(0, 0) }.stringField("Flag") {}
            }
        }.build().toJson()

    /** Tri-check `code|type` over the fired messages of `[Count] != <cond>`; returns the agreed signature list. */
    private fun sigs(cond: String, count: String, rows: List<Pair<String?, String?>>): List<String> {
        val kModel = RuntimeLaws.withRule(model, "/Order", "NvRule", "../Count", "NV", "[Count] != $cond")
        val def = ModelLoader.load(kModel)
        fun build(k: RuntimeLaws.Doc, i: io.github.mbackschat.a12.dm.interpreter.eval.Document) {
            k.group("/Order", 1).field(COUNT, count, 1, 1)
            i.group("/Order", 1).field(COUNT, count, 1, 1)
            rows.forEachIndexed { idx, (qty, flag) ->
                val r = idx + 1
                k.group(ITEMS, 1, r); i.group(ITEMS, 1, r)
                qty?.let { k.field(QTY, it, 1, r, 1); i.field(QTY, it, 1, r, 1) }
                flag?.let { k.field(FLAG, it, 1, r, 1); i.field(FLAG, it, 1, r, 1) }
            }
        }
        val kDoc = RuntimeLaws.doc(); val iDoc = def.newDocument(); build(kDoc, iDoc)
        val groovy = RuntimeLaws.messages(kModel, kDoc).map { "${it.code()}|${it.type().name}" }.sorted()
        val engine = RuntimeLaws.kernelJavaEngine(kModel)
        val kDoc2 = RuntimeLaws.doc(); val iDoc2 = def.newDocument(); build(kDoc2, iDoc2)
        val javaStatic = engine.validationSignaturesSorted(engine.instance(kDoc2))
            .map { it.split("|").let { p -> "${p[0]}|${p[1]}" } }.sorted()
        val interp = DmInterpreter(def).validateFull(iDoc)
            .map { "${it.code}|${if (it.type == MsgType.OMISSION) "OMISSION_ERROR" else "VALUE_ERROR"}" }.sorted()
        assertEquals(groovy, javaStatic, "cond=$cond count=$count rows=$rows — groovy vs java-static (tri-check)")
        assertEquals(groovy, interp, "cond=$cond count=$count rows=$rows — kernel vs interpreter")
        return groovy
    }

    // The NV operands are authored through the typed single-list overload (DG21 → API-FINDINGS 97) and
    // rendered GROUP-RELATIVE — the dogfood that retired the raw condition strings this differential once
    // needed. `typedRenderMatchesTheKernelForm` locks each render to its exact previous kernel string.
    private val m = BoundModel.ofJson(model)
    private fun rel(nv: NumberExpr) = DslRenderer(ConditionLanguage.EN, "/Order").render(nv.expression())
    private val nv = rel(Dsl.numberOfValueInFields(1L,
        m.flatten("/Order/Items*/Qty").having(m.str(FLAG).eq("Y"))))
    private val nvBare = rel(Dsl.numberOfValueInFields(1L, m.flatten("/Order/Items*/Qty")))  // unfiltered sibling
    private val nvStr = rel(Dsl.numberOfValueInFields("A", m.flatten("/Order/Items*/Flag")))  // String-family identity

    @Test fun typedRenderMatchesTheKernelForm() {
        assertEquals("NumberOfValueInFields(1 In Items*/Qty Having [Items/Flag] == \"Y\")", nv)
        assertEquals("NumberOfValueInFields(1 In Items*/Qty)", nvBare)
        assertEquals("NumberOfValueInFields(\"A\" In Items*/Flag)", nvStr)
    }

    // ── separator 1: a filtered current MATCH is shrinkable — stored BELOW the count → OMISSION ─────────────
    @Test fun filteredMatchStoredBelowIsOmission() =
        assertEquals(listOf("NV|OMISSION_ERROR"), sigs(nv, "0", listOf("1" to "Y", "1" to "Y")),
            "count 2 came through the filter as a match → can shrink to 0")

    // ── separator 2 (the key): a filtered selected NON-match is grow-only zero — stored BELOW → VALUE ───────
    @Test fun filteredNonMatchStoredBelowIsValue() =
        assertEquals(listOf("NV|VALUE_ERROR"), sigs(nv, "-5", listOf("2" to "Y", "3" to "Y")),
            "the filter kept two filled cells but neither matched 1 → count 0 cannot shrink to -5")

    // ── stored ABOVE: both are OMISSION (the count can grow) ───────────────────────────────────────────────
    @Test fun filteredNonMatchStoredAboveIsOmission() =
        assertEquals(listOf("NV|OMISSION_ERROR"), sigs(nv, "5", listOf("2" to "Y", "3" to "Y")))

    @Test fun filteredMatchStoredAboveIsOmission() =
        assertEquals(listOf("NV|OMISSION_ERROR"), sigs(nv, "5", listOf("1" to "Y", "1" to "Y")))

    // ── the filter drops non-matching-flag rows before the value gate (count value correctness) ────────────
    @Test fun filterDropsRowsThenMatchesValue() =
        assertEquals(listOf("NV|OMISSION_ERROR"), sigs(nv, "0", listOf("1" to "N", "1" to "Y")),
            "only row 2 passes the flag filter and matches 1 → count 1, shrinkable to 0")

    // ── no rows at all: the un-instantiated declared tail keeps a filtered count growable → OMISSION ───────
    @Test fun noRowsStoredAboveIsOmission() =
        assertEquals(listOf("NV|OMISSION_ERROR"), sigs(nv, "5", emptyList()))

    // ── first reached formal unavailability suppresses the whole count → the NV comparison does NOT fire ───
    //    a kept row's malformed Qty (leading zero `07` on a scale-0 field) makes the count non-evaluable; the
    //    only message is that cell's own formal error (NV absent = suppressed — would fire OMISSION otherwise) ─
    @Test fun firstFormalCauseSuppressesTheCount() =
        assertEquals(listOf("zahlHatFuehrendeNullen|VALUE_ERROR"), sigs(nv, "5", listOf("07" to "Y", "1" to "Y")),
            "the kept malformed cell makes the count non-evaluable → the NV comparison is suppressed")

    // ── the SIBLING site — an UNFILTERED star: growable via the omitted declared tail, never shrinkable ────
    @Test fun bareStarOmittedTailStoredAboveIsOmission() =
        assertEquals(listOf("NV|OMISSION_ERROR"), sigs(nvBare, "5", listOf("1" to "Y")),
            "count 1 with 9 declared rows omitted → can grow to 5 (no filter, so grow-only)")

    @Test fun bareStarStoredBelowIsValue() =
        assertEquals(listOf("NV|VALUE_ERROR"), sigs(nvBare, "-5", listOf("1" to "Y")),
            "an unfiltered count is never shrinkable → cannot reach -5, only the value helps")

    // ── exact token identity for a textual family: case-sensitive stored/evaluated projection, no coercion ─
    @Test fun exactTokenIdentityCountsOnlyTheExactToken() =
        assertEquals(emptyList<String>(), sigs(nvStr, "1", listOf(null to "A", null to "a")),
            "only \"A\" matches (not \"a\") → count 1, stored 1 is silent")

    // ── a matching stored count is silent ──────────────────────────────────────────────────────────────────
    @Test fun matchingCountIsSilent() =
        assertEquals(emptyList<String>(), sigs(nv, "2", listOf("1" to "Y", "1" to "Y")))
}
