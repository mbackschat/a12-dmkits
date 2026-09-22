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
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.util.function.Consumer

/**
 * Differential — `SumOfProducts` completeness under partial validation, with **two NESTED repeatable levels**.
 *
 * `PartialValidationAggregateDiffTest` establishes the one-level readings: wildcard fires, both declared rows
 * fire, either row alone is silent. Every one of those cases varies a SINGLE repeatable level, and at one level
 * "each level is fully covered" and "the tuples cover everything" are the same statement. They stop being the
 * same statement the moment two repeatable levels nest, which is exactly the shape this file adds.
 *
 * `/Cart { Outer[2] { Total, Inner[2] { Qty, Price } } }` — the rule sits on `Outer`, so its `Inner*` operands
 * are per-`Outer`-row, and the relevant set can address `(Outer, Inner)` PAIRS. The discriminator is the
 * **diagonal**: `Outer[1]/Inner[1]` plus `Outer[2]/Inner[2]` covers `{1,2}` at both levels while covering only
 * two of the four rows. A completeness test that reads per-level unions calls that complete; the kernel does
 * not. That is not a hypothetical — it shipped, and it is the same over-generalization as the defect the
 * one-level readings were introduced to fix, one nesting level further in.
 *
 * Every case is compared kernel-vs-interpreter rather than against a hardcoded expectation, so the file records
 * what the kernel does even where nobody predicted it — including the **mixed-pattern** case, where coverage is
 * spread across relevant entities of different wildcard shapes and no single shape is complete on its own.
 */
class PartialNestedAggregateDiffTest {

    private val json = DocumentModelDraft.named("cart").locale("en_US")
        .rootGroup("Cart", Consumer { c: GroupBuilder ->
            c.group("Outer", Consumer<GroupBuilder> { o ->
                o.repeatable(2).numberField("Total", Consumer { })
                    .group("Inner", Consumer<GroupBuilder> {
                        it.repeatable(2).numberField("Qty", Consumer { }).numberField("Price", Consumer { })
                    })
            })
        })
        .build().toJson()

    private val m = BoundModel.ofJson(json)
    private val sopOp = RuntimeLaws.render(
        Dsl.fieldFilled(m.field(TOTAL))
            .and(Dsl.sumOfProducts(m.flatten("$INNER*/Qty"), m.flatten("$INNER*/Price")).gt(50)))

    private val kModel = RuntimeLaws.withRule(json, OUTER, "SOP", "../Total", "SOP", sopOp)
    private val def = ModelDef(
        listOf(FieldDef(TOTAL, Kind.NUMBER), FieldDef(QTY, Kind.NUMBER), FieldDef(PRICE, Kind.NUMBER)),
        listOf(RuleDef("$OUTER/SOP", OUTER, sopOp, "SOP", "../Total")),
        repeatableGroups = setOf(OUTER, INNER), declaredReps = mapOf(OUTER to 2, INNER to 2),
    )

    /** Every Inner row of every Outer row carries values whose products clear the threshold. */
    private fun kDoc() = RuntimeLaws.doc().group(CART, 1).also { d ->
        for (o in 1..2) {
            d.group(OUTER, 1, o).field(TOTAL, "1", 1, o, 1)
            for (i in 1..2) d.group(INNER, 1, o, i)
                .field(QTY, "10", 1, o, i, 1).field(PRICE, "10", 1, o, i, 1)
        }
    }

    private fun iDoc() = def.newDocument().group(CART, 1).also { d ->
        for (o in 1..2) {
            d.group(OUTER, 1, o).field(TOTAL, "1", 1, o, 1)
            for (i in 1..2) d.group(INNER, 1, o, i)
                .field(QTY, "10", 1, o, i, 1).field(PRICE, "10", 1, o, i, 1)
        }
    }

    /** Compares the COUNT of firings, not the set of codes: the rule iterates two outer rows, so a set would
     *  collapse "fired for one row" and "fired for both" into the same reading. */
    private fun agree(kRel: Set<Relevant>, iRel: Set<RelevantEntity>, label: String) {
        val k = RuntimeLaws.messagesPart(kModel, kDoc(), kRel).count { it.code == CODE }
        val i = DmInterpreter(def).validatePart(iDoc(), iRel).messages.count { it.code == CODE }
        println("[kernel-reading] SumOfProducts · $label → kernel=$k interp=$i")
        assertEquals(k, i, "$label — kernel fired $k×, interpreter fired $i×")
    }

    /** The error cell, always relevant, so only the aggregate's own coverage varies between cases. */
    private val kTotal = Relevant.of(TOTAL, 0, 0, 0)
    private val iTotal = RelevantEntity.anyRep(TOTAL)

    private fun kInner(outer: Int, inner: Int) = Relevant.of(INNER, 0, outer, inner)
    private fun iInner(outer: Int, inner: Int) = RelevantEntity(INNER, listOf(0, outer, inner))

    @Test
    fun wildcardedInnerAgrees() =
        agree(setOf(kTotal, Relevant.of(INNER, 0, 0, 0)), setOf(iTotal, RelevantEntity.anyRep(INNER)), "wildcard")

    @Test
    fun theFullCrossProductAgrees() = agree(
        setOf(kTotal) + (1..2).flatMap { o -> (1..2).map { i -> kInner(o, i) } },
        setOf(iTotal) + (1..2).flatMap { o -> (1..2).map { i -> iInner(o, i) } },
        "all four (Outer, Inner) pairs enumerated",
    )

    /**
     * **The discriminator.** Both levels see `{1, 2}`, so every level is individually complete, yet only half
     * the rows are relevant. Per-level union reasoning fires here; the kernel decides for itself.
     */
    @Test
    fun theDiagonalAgrees() = agree(
        setOf(kTotal, kInner(1, 1), kInner(2, 2)),
        setOf(iTotal, iInner(1, 1), iInner(2, 2)),
        "diagonal only (Outer1/Inner1 + Outer2/Inner2) — each level complete, the cross-product is not",
    )

    /** The other half of the diagonal, so the reading cannot depend on which pairs were picked. */
    @Test
    fun theAntiDiagonalAgrees() = agree(
        setOf(kTotal, kInner(1, 2), kInner(2, 1)),
        setOf(iTotal, iInner(1, 2), iInner(2, 1)),
        "anti-diagonal only (Outer1/Inner2 + Outer2/Inner1)",
    )

    /** One Outer row fully enumerated, the other not covered at all. */
    @Test
    fun oneOuterRowFullyEnumeratedAgrees() = agree(
        setOf(kTotal, kInner(1, 1), kInner(1, 2)),
        setOf(iTotal, iInner(1, 1), iInner(1, 2)),
        "Outer row 1 complete, Outer row 2 absent",
    )

    /**
     * `Outer[*]/Inner[1]` plus `Outer[*]/Inner[2]` covers every pair. Both entities WILDCARD the same level and
     * differ only in a concrete coordinate, so they share one pattern and the cross-product check sees them
     * together — this case does NOT exercise coverage spread across different shapes, despite reading like it.
     * It is kept because it pins the one-wildcard-level reading; [mixedWildcardShapesAgree] is the real one.
     */
    @Test
    fun oneWildcardShapeWithSeparateInnerRowsAgrees() = agree(
        setOf(kTotal, Relevant.of(INNER, 0, 0, 1), Relevant.of(INNER, 0, 0, 2)),
        setOf(iTotal, RelevantEntity(INNER, listOf(0, 0, 1)), RelevantEntity(INNER, listOf(0, 0, 2))),
        "Outer wildcarded, Inner rows enumerated separately (ONE shared wildcard pattern)",
    )

    /**
     * **Genuinely mixed wildcard shapes.** `Outer[*]/Inner[1]` wildcards the outer level; `Outer[1]/Inner[2]` and
     * `Outer[2]/Inner[2]` pin it. The union is all four pairs, but the two patterns — `[*,*,1]` and `[*,c,2]` —
     * are individually incomplete, and a completeness test that decides **per pattern** reports incomplete and
     * suppresses. That direction was a documented guess; this case measures it.
     */
    @Test
    fun mixedWildcardShapesAgree() = agree(
        setOf(kTotal, Relevant.of(INNER, 0, 0, 1), kInner(1, 2), kInner(2, 2)),
        setOf(iTotal, RelevantEntity(INNER, listOf(0, 0, 1)), iInner(1, 2), iInner(2, 2)),
        "Inner[1] for every Outer + Inner[2] pinned per Outer (union is complete, neither pattern is)",
    )

    /**
     * The same fact with the mixing at the ROOT coordinate instead of the outer one — `[*,*,1]` against
     * `[1,*,2]` — so the reading cannot depend on which level carries the shape difference.
     */
    @Test
    fun mixedWildcardShapesAtTheRootCoordinateAgree() = agree(
        setOf(kTotal, Relevant.of(INNER, 0, 0, 1), Relevant.of(INNER, 1, 0, 2)),
        setOf(iTotal, RelevantEntity(INNER, listOf(0, 0, 1)), RelevantEntity(INNER, listOf(1, 0, 2))),
        "root wildcarded vs root pinned (union covers every pair, neither pattern does)",
    )

    private companion object {
        const val CODE = "SOP"
        const val CART = "/Cart"
        const val OUTER = "/Cart/Outer"
        const val TOTAL = "/Cart/Outer/Total"
        const val INNER = "/Cart/Outer/Inner"
        const val QTY = "/Cart/Outer/Inner/Qty"
        const val PRICE = "/Cart/Outer/Inner/Price"
    }
}
