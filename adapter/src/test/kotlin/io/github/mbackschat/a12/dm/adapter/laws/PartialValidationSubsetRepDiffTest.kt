package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.adapter.laws.RuntimeLaws.Relevant
import io.github.mbackschat.a12.dm.interpreter.DmInterpreter
import io.github.mbackschat.a12.dm.interpreter.eval.Document
import io.github.mbackschat.a12.dm.interpreter.model.FieldDef
import io.github.mbackschat.a12.dm.interpreter.model.Kind
import io.github.mbackschat.a12.dm.interpreter.model.ModelDef
import io.github.mbackschat.a12.dm.interpreter.model.RelevantEntity
import io.github.mbackschat.a12.dm.interpreter.model.RuleDef
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft.GroupBuilder
import io.github.mbackschat.a12.dm.modelkit.FieldKind
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel
import io.github.mbackschat.a12.dm.rulekit.dsl.Condition
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl
import io.github.mbackschat.a12.dm.rulekit.dsl.DslRenderer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.function.Consumer

/**
 * Differential — **partial validation under a SUBSET of relevant repetitions** (the IG9 boundary): a rule whose
 * condition is a starred **aggregate** over a repeatable group (`Sum` of `Lines* / Qty` below 100), validated with
 * only **one row** relevant. The kernel treats every non-relevant field instance as UNKNOWN (3VL), so an
 * aggregate that references the non-relevant rows must reflect that — the question this test settles is whether
 * the interpreter's aggregate/star resolution honours relevance per-row (gating rows 2,3 to Unknown) or walks
 * the whole document regardless. `PartialValidationDiffTest` only exercises the all-repetitions-wildcarded form;
 * this is the arbitrary-subset case it cannot reach. KERNEL-FINDINGS "Partial validation".
 */
class PartialValidationSubsetRepDiffTest {

    // /Order { CustomerName } with a repeatable /Order/Lines(3) { Qty }.
    private val json = DocumentModelDraft.named("form").locale("en_US")
        .rootGroup("Order", Consumer { g ->
            g.field("CustomerName", FieldKind.STRING)
                .group("Lines", Consumer<GroupBuilder> { it.repeatable(3).field("Qty", FieldKind.NUMBER) })
        })
        .build().toJson()

    // Condition authored with the rulekit typed DSL ([m]) and rendered to the canonical string fed to BOTH
    // engines — no free-text literal (dogfood). `Sum(Lines*/Qty) < 100`.
    private val m = BoundModel.ofJson(json)
    private fun render(c: Condition): String = DslRenderer().render(c)
    // The error field (CustomerName) must appear in the condition (kernel MVK_ERROR_FIELD_NOT_REFERENCED), so
    // the rule reads `FieldFilled(CustomerName) And Sum(Lines* / Qty) < 100`. CustomerName is relevant in the
    // subset case (it IS the error field), so the aggregate is what exercises the subset-repetition relevance.
    private val cond = render(Dsl.fieldFilled(m.field(NAME)).and(Dsl.sum(m.flatten("$LINES*/Qty")).lt(100)))

    private val def = ModelDef(
        listOf(FieldDef(NAME, Kind.STRING), FieldDef(QTY, Kind.NUMBER)),
        listOf(RuleDef("$ORDER/AGG", ORDER, cond, CODE, "../CustomerName")),
        repeatableGroups = setOf(LINES),
        declaredReps = mapOf(LINES to 3),
    )
    private val kModel = RuntimeLaws.withRule(json, ORDER, "AGG", "../CustomerName", CODE, cond)

    // 3 filled rows: Qty 10/20/30 → Sum 60 < 100 → AGG fires in full validation. All 3 declared rows
    // instantiated, so the aggregate is VALUE-typed (no fillable tail).
    private fun kDoc() = RuntimeLaws.doc().group(ORDER, 1).field(NAME, "Acme", 1, 1)
        .group(LINES, 1, 1).field(QTY, "10", 1, 1, 1)
        .group(LINES, 1, 2).field(QTY, "20", 1, 2, 1)
        .group(LINES, 1, 3).field(QTY, "30", 1, 3, 1)

    private fun iDoc() = Document(def.kinds, def.declaredReps).group(ORDER, 1).field(NAME, "Acme", 1, 1)
        .group(LINES, 1, 1).field(QTY, "10", 1, 1, 1)
        .group(LINES, 1, 2).field(QTY, "20", 1, 2, 1)
        .group(LINES, 1, 3).field(QTY, "30", 1, 3, 1)

    private fun kFires(rel: Set<Relevant>) = RuntimeLaws.firedCodesPart(kModel, kDoc(), rel).contains(CODE)
    private fun iFires(rel: Set<RelevantEntity>) = DmInterpreter(def).validatePart(iDoc(), rel).messages.any { it.code == CODE }

    /** Sanity: the aggregate rule fires in FULL validation (Sum 60 < 100), on both engines. */
    @Test fun firesInFull() {
        assertTrue(RuntimeLaws.firedCodes(kModel, kDoc()).contains(CODE), "kernel full should fire AGG")
        assertTrue(DmInterpreter(def).validate(iDoc()).messages.any { it.code == CODE }, "interp full should fire AGG")
    }

    /** The IG9 case: error field + only ROW 1 of Qty relevant; rows 2,3 are NOT relevant. The aggregate
     *  references all three rows, so rows 2,3 resolve to Unknown on the kernel — the interpreter must agree. */
    @Test fun subsetRelevantRowsAgreeWithKernel() {
        val kRel = setOf(Relevant.of(NAME, 0, 0), Relevant.of(QTY, 0, 1, 0))
        val iRel = setOf(RelevantEntity.anyRep(NAME), RelevantEntity(QTY, listOf(0, 1, 0)))
        assertEquals(kFires(kRel), iFires(iRel),
            "subset (row 1 only) — kernel=${kFires(kRel)} interp=${iFires(iRel)}")
    }

    /** The NON-DIRECTIONAL proof (IG9): `Max(Lines* / Qty) > 5` — row 1 (=10) ALONE would force `> 5`, yet the
     *  kernel does NOT fire under a row-1-only relevant set (the aggregate is Unknown; the kernel applies NO
     *  directional 3VL to the non-relevant cells). The interpreter must match (gate the whole aggregate). */
    @Test fun maxSubsetIsNonDirectional() {
        val maxCond = render(Dsl.fieldFilled(m.field(NAME)).and(Dsl.maxValue(m.flatten("$LINES*/Qty")).gt(5)))
        val maxModel = RuntimeLaws.withRule(json, ORDER, "AGG", "../CustomerName", CODE, maxCond)
        val maxDef = def.copy(rules = listOf(RuleDef("$ORDER/AGG", ORDER, maxCond, CODE, "../CustomerName")))
        val kRel = setOf(Relevant.of(NAME, 0, 0), Relevant.of(QTY, 0, 1, 0))
        val iRel = setOf(RelevantEntity.anyRep(NAME), RelevantEntity(QTY, listOf(0, 1, 0)))
        val kFires = RuntimeLaws.firedCodesPart(maxModel, kDoc(), kRel).contains(CODE)
        val iFires = DmInterpreter(maxDef).validatePart(iDoc(), iRel).messages.any { it.code == CODE }
        assertEquals(kFires, iFires, "Max>5 subset (row1=10 alone would force it) — kernel=$kFires interp=$iFires")
    }

    private companion object {
        const val ORDER = "/Order"
        const val LINES = "/Order/Lines"
        const val NAME = "/Order/CustomerName"
        const val QTY = "/Order/Lines/Qty"
        const val CODE = "AGG"
    }
}
