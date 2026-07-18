package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.interpreter.DmInterpreter
import io.github.mbackschat.a12.dm.interpreter.eval.Document
import io.github.mbackschat.a12.dm.interpreter.eval.DocumentConfig
import io.github.mbackschat.a12.dm.interpreter.model.FieldDef
import io.github.mbackschat.a12.dm.interpreter.model.Kind
import io.github.mbackschat.a12.dm.interpreter.model.ModelDef
import io.github.mbackschat.a12.dm.interpreter.model.RelevantEntity
import io.github.mbackschat.a12.dm.adapter.laws.RuntimeLaws.Relevant
import io.github.mbackschat.a12.dm.edit.EditSession
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft.GroupBuilder
import io.github.mbackschat.a12.dm.rulekit.dsl.Condition
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl
import io.github.mbackschat.a12.dm.rulekit.dsl.FieldRef
import io.github.mbackschat.a12.dm.rulekit.dsl.Rule
import io.github.mbackschat.a12.dm.rulekit.dsl.Severity
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.util.function.Consumer

/**
 * Differential — an INSTANTIATED repeatable-group row is *content*, so the kernel EVALUATES a rule on it even
 * when every field in the row is empty (KERNEL-FINDINGS §4/§9: creating a repeatable row is itself content).
 * This is the complement of [EmptyRowGateDiffTest], which covers a truly-empty NON-repeatable scope (not
 * evaluated). Two shapes over `/Cart { Lines[3] { Qty } }`:
 *
 *  - **per-row** `[Qty] < 5` (scope = the repeatable `Lines`): fires OMISSION (empty Qty = 0 < 5) on EVERY
 *    instantiated row — whether the row carries a present-but-empty `Qty` placement (what the fuzz emits) or is
 *    a bare group instance with no field placement.
 *  - **empty-identity aggregate** `MinValue` over `Lines* then Qty`, `< 5` (scope = the non-repeatable `/Cart`): the
 *    instantiated `Lines` rows make `/Cart` content-bearing, so the aggregate evaluates (0 < 5 → OMISSION @ first row).
 *  - **zero rows**: the group is empty → `/Cart` has no content → neither shape fires (both engines agree).
 *
 * Each case compares kernel-vs-interpreter (no hardcoded expectation). Surfaced by the aggregate-fuzz extension
 * (the deferred `MinValue < n` rule); the durable net is `perf.InterpreterKernelFuzzTest` (MN re-added).
 */
class RepeatableRowContentDiffTest {

    private val json = DocumentModelDraft.named("cart").locale("en_US")
        .rootGroup("Cart", Consumer { g: GroupBuilder ->
            g.numberField("Note", Consumer { })
                .group("Lines", Consumer<GroupBuilder> { it.repeatable(3).numberField("Qty", Consumer { }) })
        })
        .build().toJson()

    private val edit = EditSession.read(json)
    private val cart = edit.group(CART)
    private val qty = edit.group(LINES).num("Qty")
    private val note = cart.num("Note")

    private fun rowRule(code: String, errorField: FieldRef, cond: Condition): Rule = edit.group(LINES).rule {
        it.name("R_$code").code(code).errorField(errorField).`when`(cond).severity(Severity.ERROR).message(Dsl.en("x")).build()
    }
    private fun cartRule(code: String, errorField: FieldRef, cond: Condition): Rule = cart.rule {
        it.name("R_$code").code(code).errorField(errorField).`when`(cond).severity(Severity.ERROR).message(Dsl.en("x")).build()
    }

    private fun kDoc(rows: Int, emptyPlacements: Boolean) = RuntimeLaws.doc().group(CART, 1).let { d ->
        for (r in 1..rows) { d.group(LINES, 1, r); if (emptyPlacements) d.emptyField(QTY, 1, r, 1) }
        d
    }
    private fun iDoc(rows: Int, emptyPlacements: Boolean) = Document(DocumentConfig(KINDS, mapOf(LINES to 3)))
        .group(CART, 1).let { d ->
            for (r in 1..rows) { d.group(LINES, 1, r); if (emptyPlacements) d.emptyField(QTY, 1, r, 1) }
            d
        }

    private fun defOf(r: Rule) = ModelDef(listOf(FieldDef(NOTE, Kind.NUMBER), FieldDef(QTY, Kind.NUMBER)),
        listOf(TypedRules.def(r)), repeatableGroups = setOf(LINES), declaredReps = mapOf(LINES to 3))

    private fun agree(label: String, r: Rule, rows: Int, emptyPlacements: Boolean) {
        val kModel = TypedRules.inject(json, r)
        val k = RuntimeLaws.messages(kModel, kDoc(rows, emptyPlacements)).filter { it.code() == r.code() }
            .map { "${it.type()}@${it.field()}" }.toSortedSet()
        val i = DmInterpreter(defOf(r)).validate(iDoc(rows, emptyPlacements)).messages.filter { it.code == r.code() }
            .map { "${it.type}_ERROR@${it.errorPath}" }.toSortedSet()
        assertEquals(k, i, "$label — kernel=$k interp=$i")
    }

    private fun agreePart(label: String, r: Rule, rows: Int, kRel: Set<Relevant>, iRel: Set<RelevantEntity>) {
        val kModel = TypedRules.inject(json, r)
        val k = RuntimeLaws.messagesPart(kModel, kDoc(rows, false), kRel).filter { it.code() == r.code() }
            .map { "${it.type()}@${it.field()}" }.toSortedSet()
        val i = DmInterpreter(defOf(r)).validatePart(iDoc(rows, false), iRel).messages.filter { it.code == r.code() }
            .map { "${it.type}_ERROR@${it.errorPath}" }.toSortedSet()
        assertEquals(k, i, "$label — kernel=$k interp=$i")
    }

    @Test fun perRowComparisonFiresOnInstantiatedEmptyRows() {
        agree("per-row Qty<5, 3 rows + empty placements", rowRule("PRP", qty.field(), qty.lt(5)), 3, true)
        agree("per-row Qty<5, 3 bare group rows", rowRule("PRB", qty.field(), qty.lt(5)), 3, false)
    }

    @Test fun emptyIdentityAggregateFiresWhenGroupHasInstantiatedRows() {
        agree("MinValue(Lines*/Qty)<5, 3 rows + empty placements",
            cartRule("MNP", qty.field(), Dsl.minValue(cart.flatten("Lines*/Qty")).lt(5)), 3, true)
        agree("MinValue(Lines*/Qty)<5, 3 bare group rows",
            cartRule("MNB", qty.field(), Dsl.minValue(cart.flatten("Lines*/Qty")).lt(5)), 3, false)
    }

    @Test fun zeroRowsFiresNothing() {
        agree("per-row Qty<5, 0 rows", rowRule("PR0", qty.field(), qty.lt(5)), 0, false)
        agree("MinValue(Lines*/Qty)<5, 0 rows",
            cartRule("MN0", qty.field(), Dsl.minValue(cart.flatten("Lines*/Qty")).lt(5)), 0, false)
    }

    /**
     * PARTIAL validation — an explicitly-RELEVANT error-field instance is check-relevant, so the kernel evaluates
     * its rule (empty-as-0 / empty-identity FIRE) even on a non-content-bearing (empty / phantom) row, overriding
     * the content gate (IF54) — while the IF42 star-relevance gate still bounds an aggregate under a *concrete*
     * relevant row. Note the asymmetry with [zeroRowsFiresNothing]: the empty-identity aggregate does NOT fire in
     * FULL validation over 0 rows, but DOES in partial when the ancestor `/Cart` is relevant (a phantom `Lines[1]`).
     */
    @Test fun partialRelevantEmptyInstanceIsEvaluated() {
        val mn = cartRule("MNpc", qty.field(), Dsl.minValue(cart.flatten("Lines*/Qty")).lt(5))
        agreePart("MinValue empty-identity, /Cart relevant, 0 rows (phantom fire)", mn, 0,
            setOf(Relevant.of(CART, 1)), setOf(RelevantEntity(CART, listOf(1))))
        agreePart("MinValue, concrete Line1 relevant, 3 empty rows (IF42 suppresses)", mn, 3,
            setOf(Relevant.of(LINES, 1, 1)), setOf(RelevantEntity(LINES, listOf(1, 1))))
        val pln = cartRule("PLNpc", note.field(), note.lt(5))
        agreePart("plain Note<5, Note relevant, empty Note (empty-as-0 fires)", pln, 0,
            setOf(Relevant.of(NOTE, 1, 1)), setOf(RelevantEntity(NOTE, listOf(1, 1))))
    }

    private companion object {
        const val CART = "/Cart"; const val LINES = "/Cart/Lines"
        const val QTY = "/Cart/Lines/Qty"; const val NOTE = "/Cart/Note"
        val KINDS = mapOf(NOTE to Kind.NUMBER, QTY to Kind.NUMBER)
    }
}
