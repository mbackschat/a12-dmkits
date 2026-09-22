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
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Differential — **`NumberOfValueInFields(value In f1, f2, …)`**: the count of the listed fields whose FILLED
 * value equals the constant. The exact-match catch (kernel `RuntimeController.anzahlWertInFeldlisteIntern`): an
 * **empty** field is NOT counted — even when the constant is `0`, an empty number does not count as `0` (unlike
 * empty-as-0 in a comparison) — so a `>= 2` threshold over one matching + one empty field does NOT fire. Each
 * rule is guarded with `FieldFilled(Qty)`; firing must agree.
 */
class NumberOfValueInFieldsDiffTest {

    private val fuzz = RuntimeLaws.model("models/fuzz.dm.json")
    private val fields = listOf(
        FieldDef(ITEM, Kind.STRING), FieldDef(QTY, Kind.NUMBER, scale = 0), FieldDef(PRICE, Kind.NUMBER, scale = 2),
    )

    // Conditions are authored with the rulekit typed DSL ([m]) and rendered to the canonical string fed to BOTH
    // engines — no free-text condition literals. `Dsl.numberOfValueInFields(0L, m.field(a), m.field(b))` renders
    // `NumberOfValueInFields(0 In a, b)`; the `In` separates the counted VALUE from the field list.
    private val m = BoundModel.ofJson(fuzz)
    private fun render(c: Condition): String = RuntimeLaws.render(c)

    private fun agree(cond: String, qty: String?, price: String?, msg: String) {
        val kModel = RuntimeLaws.withRule(fuzz, "/Cart/Lines", "NV", "../Qty", "NV", cond)
        val def = ModelDef(fields, listOf(RuleDef("/Cart/Lines/NV", "/Cart/Lines", cond, "NV", "../Qty")),
            repeatableGroups = setOf("/Cart/Lines"), declaredReps = mapOf("/Cart/Lines" to 1000), indexFields = setOf(ITEM))
        val kDoc = RuntimeLaws.doc().group("/Cart", 1).group("/Cart/Lines", 1, 1).field(ITEM, "A", 1, 1, 1)
        val iDoc = def.newDocument()
            .field(ITEM, "A", 1, 1, 1)
        if (qty != null) { kDoc.field(QTY, qty, 1, 1, 1); iDoc.field(QTY, qty, 1, 1, 1) }
        if (price != null) { kDoc.field(PRICE, price, 1, 1, 1); iDoc.field(PRICE, price, 1, 1, 1) }
        val kernel = RuntimeLaws.fires(kModel, "NV", kDoc)
        val interp = DmInterpreter(def).validateFull(iDoc).any { it.code == "NV" }
        assertEquals(kernel, interp, "$msg  (kernel=$kernel interp=$interp)")
    }

    private val zeroGt0 = render(m.field(QTY).filled().and(Dsl.numberOfValueInFields(0L, m.field(QTY), m.field(PRICE)).gt(0)))
    private val zeroGe2 = render(m.field(QTY).filled().and(Dsl.numberOfValueInFields(0L, m.field(QTY), m.field(PRICE)).ge(2)))
    // Scale-normalized (scale-19) Number equality: `5` matches BOTH a scale-0 Qty=5 and a scale-2 Price=5.00 —
    // the kernel's `VkBigDecimal.equals` compares by numeric value, not by scale. `Dsl.numberOfValueInFields(5L,…)`.
    private val fiveGe2 = render(m.field(QTY).filled().and(Dsl.numberOfValueInFields(5L, m.field(QTY), m.field(PRICE)).ge(2)))

    @Test fun countsOneMatchingFilledField() = agree(zeroGt0, "0", "5.00", "Qty==0 → count 1 > 0")
    @Test fun emptyFieldIsNotCountedAsZero() = agree(zeroGe2, "0", null, "Qty==0, Price empty → count 1 (empty not 0), not >= 2")
    @Test fun bothFieldsMatchTheConstant() = agree(zeroGe2, "0", "0.00", "both == 0 → count 2 >= 2")
    @Test fun noFieldMatches() = agree(zeroGt0, "5", "5.00", "neither == 0 → count 0, not > 0")
    @Test fun scaleNormalizedEqualityCountsBoth() = agree(fiveGe2, "5", "5.00", "5 and 5.00 both == 5 (scale-19) → count 2 >= 2")
    @Test fun scaleNormalizedEqualityRejectsUnequal() = agree(fiveGe2, "5", "5.01", "5.01 != 5 → count 1, not >= 2")

    private companion object {
        const val ITEM = "/Cart/Lines/ItemId"
        const val QTY = "/Cart/Lines/Qty"
        const val PRICE = "/Cart/Lines/Price"
    }
}
