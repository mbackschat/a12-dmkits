package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.adapter.laws.RuntimeLaws.Relevant
import io.github.mbackschat.a12.dm.interpreter.DmInterpreter
import io.github.mbackschat.a12.dm.interpreter.eval.Document
import io.github.mbackschat.a12.dm.interpreter.model.FieldDef
import io.github.mbackschat.a12.dm.interpreter.model.Kind
import io.github.mbackschat.a12.dm.interpreter.model.ModelDef
import io.github.mbackschat.a12.dm.interpreter.model.RelevantEntity
import io.github.mbackschat.a12.dm.interpreter.model.RuleDef
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl
import io.github.mbackschat.a12.dm.rulekit.dsl.DslRenderer
import io.github.mbackschat.a12.dm.rulekit.dsl.NumberExpr
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft.GroupBuilder
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.util.function.Consumer

/**
 * Differential — starred aggregates over a repeatable group under partial validation are PER-OPERATOR (IF42).
 * `/Cart { Total, Lines[2] { Qty, Price } }`; the always-relevant `Total` is each rule's error field, so only the
 * aggregate's cell relevance varies. Row 1 `{Qty 182, Price 600}`, row 2 `{Qty empty, Price 600}`.
 *
 *  - **all-rows aggregates** (`Sum`/`Max` of `Lines` `Price`): the kernel evaluates them ONLY when the relevant
 *    set WILDCARDS the `Lines` level — concrete per-row enumeration, even of BOTH rows, leaves them UNKNOWN.
 *  - **`FirstFilled`** (of `Lines` `Qty`): order-aware — it fires whenever the FIRST FILLED cell (row 1) is
 *    relevant (wildcard, both-rows, OR row-1-only), and is UNKNOWN only when row 1 is not relevant (row-2-only).
 *
 * Each relevant set is compared kernel-vs-interpreter (not a hardcoded expectation). The broad randomized net is
 * `perf.PartialValidationFuzzDiffTest`; this pins the canonical cases. KERNEL-FINDINGS "Partial validation".
 */
class PartialValidationAggregateDiffTest {

    private val json = DocumentModelDraft.named("cart").locale("en_US")
        .rootGroup("Cart", Consumer { g: GroupBuilder ->
            g.numberField("Total", Consumer { })
                .group("Lines", Consumer<GroupBuilder> { it.repeatable(2).numberField("Qty", Consumer { }).numberField("Price", Consumer { }) })
        })
        .build().toJson()

    private val m = BoundModel.ofJson(json)
    private fun cond(agg: NumberExpr) = DslRenderer().render(Dsl.fieldFilled(m.field(TOTAL)).and(agg.gt(50)))
    private val sumOp = cond(Dsl.sum(m.flatten("$LINES*/Price")))
    private val ffOp = cond(Dsl.firstFilledNumber(m.flatten("$LINES*/Qty")))
    private val kModel = RuntimeLaws.withRule(RuntimeLaws.withRule(
        json, CART, "SUM", "../Total", "SUM", sumOp), CART, "FF", "../Total", "FF", ffOp)
    private val def = ModelDef(
        listOf(FieldDef(TOTAL, Kind.NUMBER), FieldDef(QTY, Kind.NUMBER), FieldDef(PRICE, Kind.NUMBER)),
        listOf(RuleDef("$CART/SUM", CART, sumOp, "SUM", "../Total"), RuleDef("$CART/FF", CART, ffOp, "FF", "../Total")),
        repeatableGroups = setOf(LINES), declaredReps = mapOf(LINES to 2),
    )

    private fun kDoc() = RuntimeLaws.doc().group(CART, 1).field(TOTAL, "1", 1, 1)
        .group(LINES, 1, 1).field(QTY, "182", 1, 1, 1).field(PRICE, "600", 1, 1, 1)
        .group(LINES, 1, 2).field(PRICE, "600", 1, 2, 1)
    private fun iDoc() = Document(def.kinds, def.declaredReps).group(CART, 1).field(TOTAL, "1", 1, 1)
        .group(LINES, 1, 1).field(QTY, "182", 1, 1, 1).field(PRICE, "600", 1, 1, 1)
        .group(LINES, 1, 2).field(PRICE, "600", 1, 2, 1)

    private fun agree(kRel: Set<Relevant>, iRel: Set<RelevantEntity>, label: String) {
        val k = RuntimeLaws.messagesPart(kModel, kDoc(), kRel).map { it.code }.filter { it in CODES }.toSortedSet()
        val i = DmInterpreter(def).validatePart(iDoc(), iRel).messages.map { it.code }.filter { it in CODES }.toSortedSet()
        assertEquals(k, i, "$label — kernel=$k interp=$i")
    }

    @Test fun wildcardLinesFiresBoth() =
        agree(setOf(Relevant.of(TOTAL, 0, 0), Relevant.of(LINES, 0, 0)),
            setOf(RelevantEntity.anyRep(TOTAL), RelevantEntity.anyRep(LINES)), "wildcard Lines")

    @Test fun concreteBothRowsOnlyFirstFilled() =
        agree(setOf(Relevant.of(TOTAL, 0, 0), Relevant.of(LINES, 0, 1), Relevant.of(LINES, 0, 2)),
            setOf(RelevantEntity.anyRep(TOTAL), RelevantEntity(LINES, listOf(0, 1)), RelevantEntity(LINES, listOf(0, 2))),
            "concrete both rows (Sum/Max UNKNOWN, FirstFilled fires)")

    @Test fun row1OnlyFirstFilledFires() =
        agree(setOf(Relevant.of(TOTAL, 0, 0), Relevant.of(LINES, 0, 1)),
            setOf(RelevantEntity.anyRep(TOTAL), RelevantEntity(LINES, listOf(0, 1))), "row 1 only")

    @Test fun row2OnlySuppressesAll() =
        agree(setOf(Relevant.of(TOTAL, 0, 0), Relevant.of(LINES, 0, 2)),
            setOf(RelevantEntity.anyRep(TOTAL), RelevantEntity(LINES, listOf(0, 2))), "row 2 only (FirstFilled's first cell non-relevant)")

    private companion object {
        const val CART = "/Cart"; const val TOTAL = "/Cart/Total"
        const val LINES = "/Cart/Lines"; const val QTY = "/Cart/Lines/Qty"; const val PRICE = "/Cart/Lines/Price"
        val CODES = setOf("SUM", "FF")
    }
}
