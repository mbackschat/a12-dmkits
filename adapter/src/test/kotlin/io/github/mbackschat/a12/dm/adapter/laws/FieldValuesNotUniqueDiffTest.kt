package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.interpreter.DmInterpreter
import io.github.mbackschat.a12.dm.interpreter.eval.newDocument
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
 * `FieldValuesNotUnique(f1, f2, …)` against the kernel — fires iff two of the listed fields hold the **same
 * value** (their filled values are not all distinct). Probed semantics (KERNEL-FINDINGS): a VALUE error;
 * **empties are skipped** (two empties are NOT a duplicate); equality is **numeric** (`5` == `5.00` across
 * different-scale fields) for numbers and string equality for strings; a filled-but-**malformed** operand
 * makes the rule non-evaluable → suppressed. On the non-repeatable `/Order` (`Quantity`/`BackorderQuantity`/
 * `StockOnHand` scale 0, `UnitPrice` scale 2, `CustomerName`/`ProductCode` strings).
 */
class FieldValuesNotUniqueDiffTest {

    private val order = RuntimeLaws.model("models/order.dm.json")
    private val fields = listOf(
        FieldDef(QTY, Kind.NUMBER, scale = 0), FieldDef(BACK, Kind.NUMBER, scale = 0),
        FieldDef(STOCK, Kind.NUMBER, scale = 0), FieldDef(PRICE, Kind.NUMBER, scale = 2),
        FieldDef(NAME, Kind.STRING), FieldDef(CODE, Kind.STRING),
    )

    // Conditions are authored with the rulekit typed DSL ([m]) and rendered to the canonical string fed to BOTH
    // engines — no free-text condition literals. ABSOLUTE paths; both engines accept them.
    private val m = BoundModel.ofJson(order)
    private fun render(c: Condition): String = DslRenderer().render(c)

    /** Inject [cond] at /Order (error field [errorRel] — must be referenced in the condition), set each
     *  (path → value) cell (null = leave empty), assert firing agrees with the kernel. */
    private fun agree(cond: String, vararg cells: Pair<String, String?>, errorRel: String = "../Quantity", msg: String) {
        val kModel = RuntimeLaws.withRule(order, "/Order", "FU", errorRel, "FU", cond)
        val def = ModelDef(fields, listOf(RuleDef("/Order/FU", "/Order", cond, "FU", errorRel)))
        val kDoc = RuntimeLaws.doc().group("/Order", 1)
        val iDoc = def.newDocument()
            .group("/Order", 1)
        for ((path, value) in cells) if (value != null) {
            kDoc.field(path, value, 1, 1); iDoc.field(path, value, 1, 1)
        }
        val kernel = RuntimeLaws.fires(kModel, "FU", kDoc)
        val interp = DmInterpreter(def).validateFull(iDoc).any { it.code == "FU" }
        assertEquals(kernel, interp, "$msg  (kernel=$kernel interp=$interp)")
    }

    private val two = render(Dsl.fieldValuesNotUnique(m.field(QTY), m.field(BACK)))
    private val three = render(Dsl.fieldValuesNotUnique(m.field(QTY), m.field(BACK), m.field(STOCK)))

    @Test fun twoEqualFires() = agree(two, QTY to "5", BACK to "5", msg = "5 == 5 → not all distinct → fires")
    @Test fun twoDistinctDoesNotFire() = agree(two, QTY to "5", BACK to "9", msg = "5 != 9 → distinct → not fire")
    @Test fun oneEmptyDoesNotFire() = agree(two, QTY to "5", BACK to null, msg = "only one value → no duplicate")
    @Test fun bothEmptyDoesNotFire() = agree(two, QTY to null, BACK to null, msg = "two empties are NOT a duplicate")
    @Test fun malformedOperandSuppresses() = agree(two, QTY to "abc", BACK to "5", msg = "malformed operand → suppressed")

    @Test fun threeWithTwoEqualFires() = agree(three, QTY to "5", BACK to "9", STOCK to "5", msg = "two of three equal → fires")
    @Test fun threeAllDistinctDoesNotFire() = agree(three, QTY to "5", BACK to "9", STOCK to "3", msg = "all distinct → not fire")
    @Test fun threeTwoEqualPlusEmptyFires() = agree(three, QTY to "5", BACK to "5", STOCK to null, msg = "the two 5s duplicate; empty skipped")
    @Test fun threeOneFilledTwoEmptyDoesNotFire() = agree(three, QTY to "5", BACK to null, STOCK to null, msg = "one value, empties skipped → no duplicate")

    @Test fun crossScaleNumericEqualFires() =
        agree(render(Dsl.fieldValuesNotUnique(m.field(QTY), m.field(PRICE))), QTY to "5", PRICE to "5.00", msg = "5 == 5.00 numerically across scales → fires")

    @Test fun equalStringsFires() =
        agree(render(Dsl.fieldValuesNotUnique(m.field(NAME), m.field(CODE))), NAME to "AB", CODE to "AB",
            errorRel = "../CustomerName", msg = "\"AB\" == \"AB\" → fires")

    @Test fun distinctStringsDoesNotFire() =
        agree(render(Dsl.fieldValuesNotUnique(m.field(NAME), m.field(CODE))), NAME to "AB", CODE to "CD",
            errorRel = "../CustomerName", msg = "\"AB\" != \"CD\" → not fire")

    // The STARRED form `FieldValuesNotUnique(Items* / Count)` — cross-ROW distinctness of one repeatable field
    // (the no-`--group` alternative to RepetitionNotUnique). Count is non-index (Sku is the index field, kept
    // distinct per row so its auto uniqueIndex/mandatory checks don't pollute the probe).
    private val itemFields = listOf(FieldDef(SKU, Kind.STRING), FieldDef(COUNT, Kind.NUMBER, scale = 0))

    private fun starAgree(cond: String, counts: List<String?>, msg: String) {
        val kModel = RuntimeLaws.withRule(order, "/Order", "FU", "../Items/Count", "FU", cond)
        val def = ModelDef(
            itemFields, listOf(RuleDef("/Order/FU", "/Order", cond, "FU", "../Items/Count")),
            repeatableGroups = setOf(ITEMS), declaredReps = mapOf(ITEMS to 100), indexFields = setOf(SKU),
        )
        val kDoc = RuntimeLaws.doc().group("/Order", 1)
        val iDoc = def.newDocument()
            .group("/Order", 1)
        counts.forEachIndexed { i, c ->
            val r = i + 1
            kDoc.group(ITEMS, 1, r).field(SKU, "K$r", 1, r, 1)
            iDoc.group(ITEMS, 1, r).field(SKU, "K$r", 1, r, 1)
            if (c != null) { kDoc.field(COUNT, c, 1, r, 1); iDoc.field(COUNT, c, 1, r, 1) }
        }
        val kernel = RuntimeLaws.fires(kModel, "FU", kDoc)
        val interp = DmInterpreter(def).validateFull(iDoc).any { it.code == "FU" }
        assertEquals(kernel, interp, "$msg  (kernel=$kernel interp=$interp)")
    }

    private val star = render(Dsl.fieldValuesNotUnique(m.flatten("/Order/Items*/Count")))

    @Test fun starredCrossRowDupFires() = starAgree(star, listOf("5", "9", "5"), msg = "two rows Count=5 → fires")
    @Test fun starredCrossRowDistinctDoesNotFire() = starAgree(star, listOf("5", "9", "3"), msg = "all distinct rows → not fire")
    @Test fun starredEmptyRowSkippedStillDup() = starAgree(star, listOf("5", null, "5"), msg = "empty row skipped; the two 5s duplicate → fires")
    @Test fun starredDistinctWithEmptyDoesNotFire() = starAgree(star, listOf("5", null, "9"), msg = "distinct rows + empty → not fire")
    @Test fun starredSingleRowDoesNotFire() = starAgree(star, listOf("5"), msg = "one value → no duplicate")

    // A `Having` on the starred operand — kernel-probed to be ACCEPTED + EVALUATED (unlike SumOfProducts, which
    // the kernel authoring-rejects). The keep-law is the overlap-predicate one (keep iff the filter is TRUE);
    // filtering the rows CHANGES which values are compared for distinctness (IF72).
    private val items = m.group("/Order/Items")
    private val havingLeq2 = render(Dsl.fieldValuesNotUnique(m.flatten("/Order/Items*/Count").having(Dsl.currentRepetition(items).le(2))))
    private val havingGeq2 = render(Dsl.fieldValuesNotUnique(m.flatten("/Order/Items*/Count").having(Dsl.currentRepetition(items).ge(2))))

    @Test fun starHavingKeepsDistinctDoesNotFire() =
        starAgree(havingLeq2, listOf("5", "9", "9"), msg = "Having <=2 keeps rows 1,2 = [5,9] distinct → not fire (filter evaluated)")
    @Test fun starHavingKeepsDuplicateFires() =
        starAgree(havingGeq2, listOf("5", "9", "9"), msg = "Having >=2 keeps rows 2,3 = [9,9] duplicate → fires (filter evaluated)")

    private companion object {
        const val QTY = "/Order/Quantity"
        const val BACK = "/Order/BackorderQuantity"
        const val STOCK = "/Order/StockOnHand"
        const val PRICE = "/Order/UnitPrice"
        const val NAME = "/Order/CustomerName"
        const val CODE = "/Order/ProductCode"
        const val ITEMS = "/Order/Items"
        const val SKU = "/Order/Items/Sku"
        const val COUNT = "/Order/Items/Count"
    }
}
