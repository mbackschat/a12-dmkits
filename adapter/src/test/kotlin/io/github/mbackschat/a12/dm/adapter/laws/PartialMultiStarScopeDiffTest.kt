package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.adapter.laws.RuntimeLaws.Relevant
import io.github.mbackschat.a12.dm.interpreter.DmInterpreter
import io.github.mbackschat.a12.dm.interpreter.eval.newDocument
import io.github.mbackschat.a12.dm.interpreter.model.FieldDef
import io.github.mbackschat.a12.dm.interpreter.model.Kind
import io.github.mbackschat.a12.dm.interpreter.model.ModelDef
import io.github.mbackschat.a12.dm.interpreter.model.RelevantEntity
import io.github.mbackschat.a12.dm.interpreter.model.RuleDef
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft.GroupBuilder
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl
import io.github.mbackschat.a12.dm.rulekit.dsl.DslRenderer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.util.function.Consumer

/**
 * Differential — the starred-aggregate relevance gates under a **MULTI-star** operand.
 *
 * `PartialNestedWildcardScopeDiffTest` and `PartialNestedAggregateDiffTest` both vary two nested repeatable
 * levels with a **single** star, where the star's own level and the deepest repeatable level of its path are the
 * same level. This file separates them: `Outer* then Inner* then Qty` stars BOTH levels, and
 * [IF94](../../../../../../../../docs/INTERPRETER-FINDINGS.md) pins the kernel's binding rule as *the levels
 * strictly above the FIRST starred group* — so from `Outer[2]`'s row this operand still spans EVERY outer row.
 *
 * That is exactly the assumption the row-scoping helper got wrong: it derived the row's bound prefix from the
 * **deepest** repeatable level, which for a multi-star operand binds a level the star itself iterates. The
 * consequence is not a missed suppression but a wrong FIRING — pinning `Outer` to one row and wildcarding `Inner`
 * reads as "fully wildcarded" to a gate that never sees the outer level, while the operand's real extent reaches
 * outer rows the caller never made relevant.
 *
 * [theMultiStarOperandSpansEveryOuterRow] is the positive control and runs FIRST in spirit: the threshold is set
 * BETWEEN one outer row's subtotal (20) and the cross-row total (40), so the rule fires only if the kernel really
 * spans both outer rows. Without it, a silent kernel in the partial cases below could just as well mean the shape
 * is unsupported, and every "agreement" underneath would be vacuous.
 */
class PartialMultiStarScopeDiffTest {

    private val json = DocumentModelDraft.named("cart").locale("en_US")
        .rootGroup("Cart", Consumer { c: GroupBuilder ->
            c.group("Outer", Consumer<GroupBuilder> { o ->
                o.repeatable(2).numberField("Total", Consumer { })
                    .group("Inner", Consumer<GroupBuilder> { it.repeatable(2).numberField("Qty", Consumer { }) })
            })
        })
        .build().toJson()

    private val m = BoundModel.ofJson(json)

    /** `Sum` over BOTH starred levels. 25 sits between one outer row's 20 and the cross-row 40. */
    private val sumOp = DslRenderer().render(
        Dsl.fieldFilled(m.field(TOTAL)).and(Dsl.sum(m.flatten("$OUTER*/Inner*/Qty")).gt(25)))

    private val kModel = RuntimeLaws.withRule(json, OUTER, "MSTAR", "../Total", CODE, sumOp)
    private val def = ModelDef(
        listOf(FieldDef(TOTAL, Kind.NUMBER), FieldDef(QTY, Kind.NUMBER)),
        listOf(RuleDef("$OUTER/MSTAR", OUTER, sumOp, CODE, "../Total")),
        repeatableGroups = setOf(OUTER, INNER), declaredReps = mapOf(OUTER to 2, INNER to 2),
    )

    private fun kDoc() = RuntimeLaws.doc().group(CART, 1).also { d ->
        for (o in 1..2) {
            d.group(OUTER, 1, o).field(TOTAL, "1", 1, o, 1)
            for (i in 1..2) d.group(INNER, 1, o, i).field(QTY, "10", 1, o, i, 1)
        }
    }

    private fun iDoc() = def.newDocument().group(CART, 1).also { d ->
        for (o in 1..2) {
            d.group(OUTER, 1, o).field(TOTAL, "1", 1, o, 1)
            for (i in 1..2) d.group(INNER, 1, o, i).field(QTY, "10", 1, o, i, 1)
        }
    }

    /**
     * **The positive control.** `Sum(Outer* then Inner* then Qty) > 25` can only fire if the operand spans both
     * outer rows: one row alone totals 20. A kernel that scoped the star to the iterating row would be silent
     * here, and every partial case below would be comparing two kinds of nothing.
     */
    @Test
    fun theMultiStarOperandSpansEveryOuterRow() {
        val k = RuntimeLaws.messages(kModel, kDoc()).count { it.code() == CODE }
        val i = DmInterpreter(def).validateFull(iDoc()).count { it.code == CODE }
        println("[kernel-reading] multi-star Sum · FULL validation → kernel=$k interp=$i")
        assertEquals(2, k, "the kernel must span both outer rows (40 > 25) and fire for each of the 2 outer rows")
        assertEquals(k, i, "full validation must agree before any partial reading means anything")
    }

    private fun agree(kRel: Set<Relevant>, iRel: Set<RelevantEntity>, label: String) {
        val k = RuntimeLaws.messagesPart(kModel, kDoc(), kRel).count { it.code() == CODE }
        val i = DmInterpreter(def).validatePart(iDoc(), iRel).messages.count { it.code == CODE }
        println("[kernel-reading] multi-star Sum · $label → kernel=$k interp=$i")
        assertEquals(k, i, "$label — kernel fired $k×, interpreter fired $i×")
    }

    private val kTotal = Relevant.of(TOTAL, 0, 0, 0)
    private val iTotal = RelevantEntity.anyRep(TOTAL)

    @Test
    fun everyLevelWildcardedAgrees() = agree(
        setOf(kTotal, Relevant.of(INNER, 0, 0, 0)),
        setOf(iTotal, RelevantEntity.anyRep(INNER)),
        "both starred levels wildcarded",
    )

    /**
     * **The discriminator.** `Outer` is pinned to row 1 and `Inner` is wildcarded under it. A gate that binds the
     * row at the DEEPEST repeatable level never sees the outer coordinate at all, so it reads this as "every
     * unbound level is wildcarded" and evaluates — even though the operand's extent reaches `Outer[2]`'s inner
     * rows, which are not relevant under any entity here.
     */
    @Test
    fun outerPinnedToOneRowWithInnerWildcardedAgrees() = agree(
        setOf(kTotal, Relevant.of(INNER, 0, 1, 0)),
        setOf(iTotal, RelevantEntity(INNER, listOf(0, 1, RelevantEntity.ALL))),
        "outer pinned to row 1, inner wildcarded under it (the operand still spans outer row 2)",
    )

    /** The same pin one row over, so the reading cannot depend on which outer row was chosen. */
    @Test
    fun outerPinnedToTheOtherRowAgrees() = agree(
        setOf(kTotal, Relevant.of(INNER, 0, 2, 0)),
        setOf(iTotal, RelevantEntity(INNER, listOf(0, 2, RelevantEntity.ALL))),
        "outer pinned to row 2, inner wildcarded under it",
    )

    /** Both outer rows pinned separately, each with its inner level wildcarded — the union IS every row. */
    @Test
    fun bothOuterRowsPinnedWithInnerWildcardedAgrees() = agree(
        setOf(kTotal, Relevant.of(INNER, 0, 1, 0), Relevant.of(INNER, 0, 2, 0)),
        setOf(
            iTotal,
            RelevantEntity(INNER, listOf(0, 1, RelevantEntity.ALL)),
            RelevantEntity(INNER, listOf(0, 2, RelevantEntity.ALL)),
        ),
        "both outer rows pinned, inner wildcarded under each",
    )

    private companion object {
        const val CODE = "MSTAR"
        const val CART = "/Cart"
        const val OUTER = "/Cart/Outer"
        const val TOTAL = "/Cart/Outer/Total"
        const val INNER = "/Cart/Outer/Inner"
        const val QTY = "/Cart/Outer/Inner/Qty"
    }
}
