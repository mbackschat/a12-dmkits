package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.interpreter.DmInterpreter
import io.github.mbackschat.a12.dm.interpreter.eval.Document
import io.github.mbackschat.a12.dm.interpreter.model.FieldDef
import io.github.mbackschat.a12.dm.interpreter.model.Kind
import io.github.mbackschat.a12.dm.interpreter.model.ModelDef
import io.github.mbackschat.a12.dm.interpreter.model.RuleDef
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel
import io.github.mbackschat.a12.dm.rulekit.dsl.Condition
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl
import io.github.mbackschat.a12.dm.rulekit.dsl.DslRenderer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Differential — the STARRED filled-groups count `NumberOfFilledGroups(/Group*)` (IG26), the interpreter twin
 * of the kernel-only [io.github.mbackschat.a12.dm.rulekit.validate.laws.RootGroupCountLawsTest] (accept-only)
 * and [NumberOfFilledDiffTest] (which feeds only the PLAIN multi-group form). The starred single-repeatable-
 * group form (`RootGroupCount` and `Dsl.numberOfFilledGroups(repeatableGroup)`) renders to
 * `NumberOfFilledGroups(/Group*)` — a bare `Expr.Star` operand that used to crash the evaluator with a
 * `ClassCastException` (R1: `pathOf(Star)`). The kernel counts the group's INSTANTIATED repetitions — a
 * created-but-empty row counts too (probed 2026-07-06) — so this locks the interpreter's count against the
 * kernel across filled / empty-instantiated / mixed / absent rows. Authored via the typed DSL, rendered, and
 * run through BOTH engines over the same document.
 *
 * Also locks the firing half of the group-cardinality correction (KERNEL-FINDINGS §9 "group cardinality",
 * corrected 2026-07-09): a minItems-style `NumberOfFilledGroups(G*) < N` rule fires at 0 and 1 rows and is
 * silent at N, in BOTH anchorings — the outside-anchored guarded shape and the unguarded shape whose error
 * field sits INSIDE the counted group. (The accept half: `validate.laws.RootGroupCountLawsTest`.)
 */
class NumberOfFilledGroupsStarDiffTest {

    private val order = RuntimeLaws.model("models/order.dm.json")
    private val m = BoundModel.ofJson(order)
    private val items = m.group("/Order/Items")
    private fun render(c: Condition): String = DslRenderer().render(c)

    // A gate on a filled root field keeps the root row evaluated; the count is the comparison's left side.
    private fun cond(op: (io.github.mbackschat.a12.dm.rulekit.dsl.NumberExpr) -> Condition): String =
        render(m.field(CN).filled().and(op(Dsl.numberOfFilledGroups(items))))

    private val eq2 = cond { it.eq(2) }
    private val gt0 = cond { it.gt(0) }
    private val ge3 = cond { it.ge(3) }
    private val lt2Guarded = cond { it.lt(2) }
    private val lt2InGroup = render(Dsl.numberOfFilledGroups(items).lt(2))

    private val fields = listOf(FieldDef(CN, Kind.STRING), FieldDef(SKU, Kind.STRING), FieldDef(COUNT, Kind.NUMBER, scale = 0))

    /** [rows]: one entry per Items repetition — a non-null Sku value fills the row, null leaves it
     *  instantiated-but-empty. Both engines get the identical shape. */
    private fun kFires(cond: String, rows: List<String?>, errorField: String = "../CustomerName"): Boolean {
        val doc = RuntimeLaws.doc().group("/Order", 1).field(CN, "Acme", 1, 1)
        rows.forEachIndexed { i, sku ->
            val r = i + 1
            doc.group("/Order/Items", 1, r)
            if (sku != null) doc.field(SKU, sku, 1, r, 1)
        }
        val model = RuntimeLaws.withRule(order, "/Order", "R", errorField, "NF", cond)
        return RuntimeLaws.messages(model, doc).any { it.code() == "NF" }
    }

    private fun iFires(cond: String, rows: List<String?>, errorField: String = "../CustomerName"): Boolean {
        val def = ModelDef(fields, listOf(RuleDef("/Order/R", "/Order", cond, "NF", errorField)),
            repeatableGroups = setOf("/Order/Items"), declaredReps = mapOf("/Order/Items" to 50), indexFields = setOf(SKU))
        val doc = Document(def.kinds, def.declaredReps, def.unsignedFields, def.rangeConfig, def.dateFormats, def.scales)
        doc.field(CN, "Acme", 1, 1)
        rows.forEachIndexed { i, sku ->
            val r = i + 1
            doc.group("/Order/Items", 1, r)
            if (sku != null) doc.field(SKU, sku, 1, r, 1)
        }
        return DmInterpreter(def).validateFull(doc).any { it.code == "NF" }
    }

    private fun agree(cond: String, rows: List<String?>) =
        assertEquals(kFires(cond, rows), iFires(cond, rows), "rows=$rows cond=$cond")

    @Test fun twoFilledRows() {
        agree(eq2, listOf("A", "B"))   // count 2 == 2 → fires
        agree(gt0, listOf("A", "B"))
        agree(ge3, listOf("A", "B"))   // 2 not >= 3
    }

    @Test fun emptyInstantiatedRowStillCounts() {
        agree(eq2, listOf("A", null))          // filled + empty-instantiated → count 2
        agree(eq2, listOf("A", null, "C"))     // count 3, not 2
        agree(ge3, listOf("A", null, "C"))     // count 3 >= 3 → fires
    }

    @Test fun soleEmptyAndAbsent() {
        agree(gt0, listOf(null))       // one empty-instantiated row → count 1 > 0
        agree(eq2, emptyList())        // no rows → count 0
        agree(gt0, emptyList())        // 0 not > 0
    }

    /** The minItems shape, guarded anchoring: fires at 0 and 1 rows, silent at 2 — both engines. */
    @Test fun minItemsGuardedShapeFiresOnTooFewRowsIncludingZero() {
        agree(lt2Guarded, emptyList())
        agree(lt2Guarded, listOf("A"))
        agree(lt2Guarded, listOf("A", "B"))
    }

    /** The minItems shape, error field INSIDE the counted group, unguarded: fires even at ZERO rows. */
    @Test fun minItemsInGroupShapeFiresOnTooFewRowsIncludingZero() {
        for (rows in listOf(emptyList(), listOf("A"), listOf<String?>("A", "B"))) {
            assertEquals(kFires(lt2InGroup, rows, "../Items/Sku"), iFires(lt2InGroup, rows, "../Items/Sku"),
                "rows=$rows cond=$lt2InGroup")
        }
        assertEquals(true, kFires(lt2InGroup, emptyList(), "../Items/Sku"), "kernel must fire at zero rows")
    }

    private companion object {
        const val CN = "/Order/CustomerName"
        const val SKU = "/Order/Items/Sku"
        const val COUNT = "/Order/Items/Count"
    }
}
