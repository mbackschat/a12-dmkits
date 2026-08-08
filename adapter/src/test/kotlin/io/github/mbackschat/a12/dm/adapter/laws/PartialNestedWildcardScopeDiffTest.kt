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
 * Differential — the WILDCARD-ONLY gate ([IF42](../../../../../../../../docs/INTERPRETER-FINDINGS.md)) under two
 * nested repeatable levels, for the four combiner aggregates rather than `SumOfProducts`.
 *
 * `SumOfProducts`' completeness gate was found to be decided globally when the kernel decides **per iteration
 * row** (IF255). `starRelevant` — the wildcard-only gate `Sum`/`MaxValue`/`MinValue`/`NumberOfDifferentValues`
 * share — is keyed on the star path alone in exactly the same way, so it is the sibling site of that class and
 * the root-cause rule says to sweep it rather than assume it is fine.
 *
 * The discriminator is a relevant set that wildcards the inner level for **one** outer row while enumerating the
 * other outer row's inner rows **concretely**: `Outer[1]/Inner[*]` + `Outer[2]/Inner[1]` + `Outer[2]/Inner[2]`.
 * A path-keyed gate answers "some entity wildcards this star" once and admits BOTH rows; the per-row truth is
 * that only `Outer[1]` is wildcarded, and a concrete enumeration is not enough for these four operators however
 * complete it is. Cell relevance alone cannot cover the difference, because every cell of `Outer[2]` IS relevant
 * here — only its wildcard-ness differs.
 *
 * Every case is read from the kernel rather than asserted, so this records the boundary even where nobody
 * predicted it.
 */
class PartialNestedWildcardScopeDiffTest {

    private val json = DocumentModelDraft.named("cart").locale("en_US")
        .rootGroup("Cart", Consumer { c: GroupBuilder ->
            c.group("Outer", Consumer<GroupBuilder> { o ->
                o.repeatable(2).numberField("Total", Consumer { })
                    .group("Inner", Consumer<GroupBuilder> { it.repeatable(2).numberField("Qty", Consumer { }) })
            })
        })
        .build().toJson()

    private val m = BoundModel.ofJson(json)
    private val sumOp = DslRenderer().render(
        Dsl.fieldFilled(m.field(TOTAL)).and(Dsl.sum(m.flatten("$INNER*/Qty")).gt(5)))

    private val kModel = RuntimeLaws.withRule(json, OUTER, "SUM", "../Total", CODE, sumOp)
    private val def = ModelDef(
        listOf(FieldDef(TOTAL, Kind.NUMBER), FieldDef(QTY, Kind.NUMBER)),
        listOf(RuleDef("$OUTER/SUM", OUTER, sumOp, CODE, "../Total")),
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

    /** Compares the MULTISET of firings, so one-row-vs-two-rows is visible rather than collapsed to a set. */
    private fun agree(kRel: Set<Relevant>, iRel: Set<RelevantEntity>, label: String) {
        val k = RuntimeLaws.messagesPart(kModel, kDoc(), kRel).count { it.code == CODE }
        val i = DmInterpreter(def).validatePart(iDoc(), iRel).messages.count { it.code == CODE }
        println("[kernel-reading] Sum · $label → kernel=$k interp=$i")
        assertEquals(k, i, "$label — kernel fired $k×, interpreter fired $i×")
    }

    private val kTotal = Relevant.of(TOTAL, 0, 0, 0)
    private val iTotal = RelevantEntity.anyRep(TOTAL)

    @Test
    fun bothOuterRowsWildcardedAgree() = agree(
        setOf(kTotal, Relevant.of(INNER, 0, 0, 0)),
        setOf(iTotal, RelevantEntity.anyRep(INNER)),
        "inner wildcarded for every outer row",
    )

    @Test
    fun bothOuterRowsConcretelyEnumeratedAgree() = agree(
        setOf(kTotal) + (1..2).flatMap { o -> (1..2).map { i -> Relevant.of(INNER, 0, o, i) } },
        setOf(iTotal) + (1..2).flatMap { o -> (1..2).map { i -> RelevantEntity(INNER, listOf(0, o, i)) } },
        "every inner row enumerated concretely (wildcard-only gate should keep these UNKNOWN)",
    )

    /**
     * **The sibling-site discriminator.** Outer row 1 is wildcarded, outer row 2 is enumerated concretely and
     * completely. A row-blind gate admits both; the kernel decides per row.
     */
    @Test
    fun oneOuterRowWildcardedAndTheOtherConcreteAgree() = agree(
        setOf(kTotal, Relevant.of(INNER, 0, 1, 0), Relevant.of(INNER, 0, 2, 1), Relevant.of(INNER, 0, 2, 2)),
        setOf(
            iTotal,
            RelevantEntity(INNER, listOf(0, 1, RelevantEntity.ALL)),
            RelevantEntity(INNER, listOf(0, 2, 1)),
            RelevantEntity(INNER, listOf(0, 2, 2)),
        ),
        "outer row 1 wildcarded, outer row 2 concretely complete",
    )

    private companion object {
        const val CODE = "SUM"
        const val CART = "/Cart"
        const val OUTER = "/Cart/Outer"
        const val TOTAL = "/Cart/Outer/Total"
        const val INNER = "/Cart/Outer/Inner"
        const val QTY = "/Cart/Outer/Inner/Qty"
    }
}
