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
 * Differential — the substring coercions **`RangeAsString(field, s, e)`** / **`RangeAsNumber(field, s, e)`** (a
 * 1-based inclusive character range of a STRING field) and the **empty-string comparison rule** they surface.
 *
 * Kernel exact-match catches (`RuntimeController.getSubstring`/`bereichAlsZahl`, `BedingungsOperatorHelper`):
 * - out-of-range (`end > length`) or an empty field → **`""`** for `RangeAsString`, never a partial substring;
 * - `RangeAsNumber` parses its substring **only when it is a run of digits** (`\d+`) — anything else → `0`;
 * - `vergleicheSTRING` returns **false when either operand is the empty string**, for `==` AND `!=` alike — so
 *   an empty `RangeAsString` result, or a literal `""`, makes the comparison **not fire** (the root fix this
 *   increment generalizes: a literal `[field] != ""` over a filled field must NOT fire either).
 */
class RangeCoercionDiffTest {

    private val fuzz = RuntimeLaws.model("models/fuzz.dm.json")
    private val fields = listOf(FieldDef(ITEM, Kind.STRING))

    // Conditions are authored with the rulekit typed DSL ([m]) and rendered to the canonical string fed to BOTH
    // engines — no free-text condition literals. `Dsl.rangeAsString`/`rangeAsNumber` are the substring coercions;
    // `m.str(ItemId).eq("")`/`.ne("")` author the empty-string-literal compares (the root-fix probes).
    private val m = BoundModel.ofJson(fuzz)
    private fun render(c: Condition): String = RuntimeLaws.render(c)

    private fun agree(cond: String, item: String?, msg: String) {
        val kModel = RuntimeLaws.withRule(fuzz, "/Cart/Lines", "RC", "../ItemId", "RC", cond)
        val def = ModelDef(fields, listOf(RuleDef("/Cart/Lines/RC", "/Cart/Lines", cond, "RC", "../ItemId")),
            repeatableGroups = setOf("/Cart/Lines"), declaredReps = mapOf("/Cart/Lines" to 1000), indexFields = setOf(ITEM))
        val kDoc = RuntimeLaws.doc().group("/Cart", 1).group("/Cart/Lines", 1, 1)
        val iDoc = def.newDocument()
        if (item != null) { kDoc.field(ITEM, item, 1, 1, 1); iDoc.field(ITEM, item, 1, 1, 1) }
        val kernel = RuntimeLaws.fires(kModel, "RC", kDoc)
        val interp = DmInterpreter(def).validateFull(iDoc).any { it.code == "RC" }
        assertEquals(kernel, interp, "$msg  (kernel=$kernel interp=$interp)")
    }

    // RangeAsString — 1-based inclusive substring; out-of-range → "" → empty operand → comparison does not fire.
    @Test fun rangeAsStringInRangeFires() =
        agree(render(m.str(ITEM).filled().and(Dsl.rangeAsString(m.field(ITEM), 1, 3).eq("BAD"))), "BADGE", "BADGE[1..3] = BAD")
    @Test fun rangeAsStringMismatchDoesNotFire() =
        agree(render(m.str(ITEM).filled().and(Dsl.rangeAsString(m.field(ITEM), 1, 3).eq("BAD"))), "GOOD9", "GOOD9[1..3] = GOO != BAD")
    @Test fun rangeAsStringOutOfRangeIsEmpty() =
        agree(render(m.str(ITEM).filled().and(Dsl.rangeAsString(m.field(ITEM), 1, 3).eq("AB"))), "AB", "AB shorter than 3 → \"\" → empty operand, no fire")

    // RangeAsNumber — only a pure-digit substring parses; else 0.
    @Test fun rangeAsNumberDigitsFires() =
        agree(render(m.str(ITEM).filled().and(Dsl.rangeAsNumber(m.field(ITEM), 1, 2).eq(12))), "12X", "12X[1..2] = 12")
    @Test fun rangeAsNumberNonDigitIsZero() =
        agree(render(m.str(ITEM).filled().and(Dsl.rangeAsNumber(m.field(ITEM), 1, 2).eq(0))), "AB3", "AB not digits → 0")
    @Test fun rangeAsNumberOutOfRangeIsZero() =
        agree(render(m.str(ITEM).filled().and(Dsl.rangeAsNumber(m.field(ITEM), 1, 2).eq(0))), "A", "A shorter than 2 → \"\" → 0")

    // The empty-string compare rule (the root fix): a filled field compared to "" never fires, == AND !=.
    @Test fun notEqualToEmptyLiteralDoesNotFire() =
        agree(render(m.str(ITEM).filled().and(m.str(ITEM).ne(""))), "X", "X != \"\" → empty operand → kernel does NOT fire")
    @Test fun equalToEmptyLiteralDoesNotFire() =
        agree(render(m.str(ITEM).filled().and(m.str(ITEM).eq(""))), "X", "X == \"\" → empty operand → no fire")

    private companion object {
        const val ITEM = "/Cart/Lines/ItemId"
    }
}
