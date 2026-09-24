package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.interpreter.DmInterpreter
import io.github.mbackschat.a12.dm.interpreter.eval.formatPointer
import io.github.mbackschat.a12.dm.interpreter.eval.newDocument
import io.github.mbackschat.a12.dm.interpreter.load.ModelLoader
import io.github.mbackschat.a12.dm.interpreter.model.ComputeOutcome
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft.GroupBuilder
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.util.function.Consumer

/**
 * Differential — the two mechanisms the A3 re-drill's CVV minimal compounds (own-domain re-expression of a
 * per-row "count my duplicates" computation over a DOUBLE star):
 *
 *  1. **`NumberOfFilledFields` honors a `Having` filter on its starred operand** (IF94) — the kernel's
 *     `anzahlAngegebenerFelder` rides the same filtered `EntityIterator` as every field-list aggregate, so the
 *     count consumes only filter-kept rows (a `$`-correlated filter resolves against the computing/iterating
 *     row). The interpreter's count previously IGNORED the filter — `NumberOfFilledFields(S* / I* / Code Having
 *     [Code] == [$Code]) > 1` then over-counted and fired/computed where the kernel does not.
 *
 *  2. **A MULTI-star operand binds at the FIRST `*`-marked level** (IF94, refining IF93) — for
 *     `../../Sections* / Items* / ItemCode` read from a per-Items row, the enclosing row binds only the levels
 *     strictly above `Sections` (the first star): the read spans ALL sections' items, not just the current
 *     section's. The IF93 single-star cap (the LEAF group's parent) left the current Sections row bound.
 *
 * The discriminating instance: a duplicate (Code, Grade) pair ACROSS sections — S1/I1 and S2/I2 match, S2/I1
 * is unique. Correct semantics: count = 2 for the matching pair (→ compute fires, value `count−1 = 1`) and 1
 * for the unique row (→ precondition `> 1` false → CLEARED). Tri-check groovy-dynamic == java-static ==
 * interpreter; computed targets seeded so kernel clears report.
 */
class MultiStarFilteredCountDiffTest {

    private val CODE = "/Shop/Catalog/Sections/Items/ItemCode"
    private val GRADE = "/Shop/Catalog/Sections/Items/ItemGrade"
    private val QTY = "/Shop/Catalog/Sections/Items/Qty"
    private val USAGE = "/Shop/Catalog/Sections/Items/UsageCount"

    private val base = DocumentModelDraft.named("shop").locale("en_US")
        .rootGroup("Shop", Consumer { g: GroupBuilder ->
            g.group("Catalog", Consumer { c ->
                c.group("Sections", Consumer { s ->
                    s.repeatable(99).group("Items", Consumer { i ->
                        i.repeatable(99)
                            .stringField("ItemCode", Consumer { })
                            .stringField("ItemGrade", Consumer { })
                            .numberField("Qty", Consumer { it.fractionDigits(2, 2) })
                            .numberField("QtyTotal", Consumer { it.fractionDigits(2, 2) })
                            .numberField("UsageCount", Consumer { })
                    })
                })
            })
        }).build().toJson()

    private val ITEMS_GROUP = "/Shop/Catalog/Sections/Items"
    private val DUP_COUNT =
        "NumberOfFilledFields(../../Sections*/Items*/ItemCode Having ([ItemCode] == [\$ItemCode] And [ItemGrade] == [\$ItemGrade]))"

    /** The corpus minimal's shape: per-Items `UsageCount = <dup count> − 1` guarded by `<dup count> > 1`. */
    private fun dupCountModel(): String = RuntimeLaws.withTieredComputation(base, ITEMS_GROUP, "UsageComp",
        "../UsageCount", null, listOf(RuntimeLaws.CompAlt("$DUP_COUNT > 1", "$DUP_COUNT - 1")))

    /** An Items row (section s, item i) with a code, a grade, and a stale UsageCount seed. */
    private fun Both.item(s: Int, i: Int, code: String, grade: String, qty: String? = null): Both {
        group("/Shop/Catalog/Sections", 1, 1, s).group(ITEMS_GROUP, 1, 1, s, i)
            .field(CODE, code, 1, 1, s, i, 1).field(GRADE, grade, 1, 1, s, i, 1)
            .field(USAGE, "7", 1, 1, s, i, 1)
        qty?.let { field(QTY, it, 1, 1, s, i, 1) }
        return this
    }

    private fun agree(kModel: String, msg: String, expect: Map<String, String>, build: (Both) -> Unit) {
        val def = ModelLoader.load(kModel)
        val b = Both(RuntimeLaws.doc(), def.newDocument())
        build(b)
        val groovy = RuntimeLaws.compute(kModel, b.kDoc).associate { "${it.path()}@${it.pointer()}" to kSig(it) }
        val engine = RuntimeLaws.kernelJavaEngine(kModel)
        val kDoc2 = RuntimeLaws.doc()
        build(Both(kDoc2, def.newDocument()))
        val javaStatic = engine.compute(engine.instance(kDoc2)).associate { "${it.path()}@${it.pointer()}" to kSig(it) }
        val interp = DmInterpreter(def).compute(b.iDoc).associate {
            "${it.path}@${formatPointer(it.path, it.indices)}" to iSig(it)
        }
        assertEquals(expect, groovy, "$msg — the kernel groovy-dynamic outcome")
        assertEquals(groovy, javaStatic, "$msg — groovy vs java-static (tri-check)")
        assertEquals(groovy, interp, "$msg — kernel vs interpreter")
    }

    private fun kSig(c: RuntimeLaws.Computed): String =
        if (c.cleared()) "CLEARED" else if (c.errored()) "ERRORED" else "VALUE|${c.value().orElse("")}"

    private fun iSig(c: io.github.mbackschat.a12.dm.interpreter.model.Computed): String = when (c.outcome) {
        ComputeOutcome.CLEARED -> "CLEARED"
        ComputeOutcome.ERRORED -> "ERRORED"
        ComputeOutcome.VALUE -> "VALUE|${c.value}"
    }

    private fun usageAt(s: Int, i: Int) = "$USAGE@/Shop[1]/Catalog[1]/Sections[$s]/Items[$i]/UsageCount"

    // ── the corpus minimal's shape: $-Having count over a double star, per-row ──────────────────────────

    @Test fun allDistinctPairsClearEverywhere() =
        agree(dupCountModel(), "all (code, grade) pairs distinct → every count = 1 → precondition > 1 false → all CLEARED",
            mapOf(usageAt(1, 1) to "CLEARED", usageAt(2, 1) to "CLEARED", usageAt(2, 2) to "CLEARED")) { d ->
            d.group("/Shop", 1).group("/Shop/Catalog", 1, 1)
            d.item(1, 1, "A", "X").item(2, 1, "B", "Y").item(2, 2, "C", "X")
        }

    @Test fun crossSectionDuplicatePairCountsAcrossSections() =
        agree(dupCountModel(), "S1/I1 and S2/I2 carry the SAME (code, grade) → their counts = 2 (cross-section!) → value 1; the unique S2/I1 clears",
            mapOf(usageAt(1, 1) to "VALUE|1", usageAt(2, 1) to "CLEARED", usageAt(2, 2) to "VALUE|1")) { d ->
            d.group("/Shop", 1).group("/Shop/Catalog", 1, 1)
            d.item(1, 1, "A", "X").item(2, 1, "B", "Y").item(2, 2, "A", "X")
        }

    @Test fun sameSectionDuplicatesCountWithinTheSectionToo() =
        agree(dupCountModel(), "two duplicates INSIDE one section (control — visible even under the old own-section binding)",
            mapOf(usageAt(1, 1) to "CLEARED", usageAt(2, 1) to "VALUE|1", usageAt(2, 2) to "VALUE|1")) { d ->
            d.group("/Shop", 1).group("/Shop/Catalog", 1, 1)
            d.item(1, 1, "A", "X").item(2, 1, "B", "Y").item(2, 2, "B", "Y")
        }

    // ── the unfiltered double-star aggregate binds at the FIRST star too ────────────────────────────────

    @Test fun unfilteredDoubleStarSumSpansAllSections() {
        val m = RuntimeLaws.withComputation(base, ITEMS_GROUP, "TotalComp", "../QtyTotal",
            "Sum(../../Sections*/Items*/Qty)")
        val TOTAL = "/Shop/Catalog/Sections/Items/QtyTotal"
        fun totalAt(sec: Int, item: Int) = "$TOTAL@/Shop[1]/Catalog[1]/Sections[$sec]/Items[$item]/QtyTotal"
        agree(m, "a per-Items Sum over Sections* then Items* then Qty totals ALL sections' items, not the own section's",
            mapOf(totalAt(1, 1) to "VALUE|6.00", totalAt(2, 1) to "VALUE|6.00", totalAt(2, 2) to "VALUE|6.00")) { d ->
            d.group("/Shop", 1).group("/Shop/Catalog", 1, 1)
            d.item(1, 1, "A", "X", qty = "1.00").item(2, 1, "B", "Y", qty = "2.00").item(2, 2, "C", "Z", qty = "3.00")
        }
    }

    // ── the filter drops a malformed cell's row BEFORE the count's non-evaluable check ──────────────────

    @Test fun malformedCellInAFilteredOutRowDoesNotPoisonTheCount() {
        // Count Qty cells over rows kept by a grade filter; the filtered-OUT row's Qty is min-frac-INVALID.
        // The kernel's filtered iterator never visits the dropped row, so the count stays evaluable.
        val m = RuntimeLaws.withTieredComputation(base, ITEMS_GROUP, "UsageComp", "../UsageCount", null,
            listOf(RuntimeLaws.CompAlt(
                "NumberOfFilledFields(../../Sections*/Items*/Qty Having [ItemGrade] == \"X\") > 0",
                "NumberOfFilledFields(../../Sections*/Items*/Qty Having [ItemGrade] == \"X\")")))
        agree(m, "the malformed Qty sits in a filtered-out (grade Y) row → count over grade-X rows computes 2",
            mapOf(usageAt(1, 1) to "VALUE|2", usageAt(2, 1) to "VALUE|2", usageAt(2, 2) to "VALUE|2")) { d ->
            d.group("/Shop", 1).group("/Shop/Catalog", 1, 1)
            d.item(1, 1, "A", "X", qty = "1.00").item(2, 1, "B", "Y", qty = "5.5").item(2, 2, "C", "X", qty = "3.00")
        }
    }

    @Test fun malformedCellInAKeptRowPoisonsTheCount() {
        val m = RuntimeLaws.withTieredComputation(base, ITEMS_GROUP, "UsageComp", "../UsageCount", null,
            listOf(RuntimeLaws.CompAlt(
                "NumberOfFilledFields(../../Sections*/Items*/Qty Having [ItemGrade] == \"X\") > 0",
                "NumberOfFilledFields(../../Sections*/Items*/Qty Having [ItemGrade] == \"X\")")))
        agree(m, "the malformed Qty sits in a KEPT (grade X) row → the count is non-evaluable → every row poisons (CLEARED)",
            mapOf(usageAt(1, 1) to "CLEARED", usageAt(2, 1) to "CLEARED", usageAt(2, 2) to "CLEARED")) { d ->
            d.group("/Shop", 1).group("/Shop/Catalog", 1, 1)
            d.item(1, 1, "A", "X", qty = "1.00").item(2, 1, "B", "Y", qty = "2.00").item(2, 2, "C", "X", qty = "5.5")
        }
    }
}
