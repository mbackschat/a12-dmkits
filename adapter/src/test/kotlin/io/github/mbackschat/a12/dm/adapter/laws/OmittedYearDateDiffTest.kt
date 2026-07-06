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
 * Differential — the OMITTED-YEAR date literal `"13.07."` (IG22), a day-month partial-date constant. It is a
 * **reference-year** feature: the kernel injects the model's `baseYear` into the literal (`"13.07."` →
 * `13.07.<baseYear>`), so `[OrderDate] > "13.07."` is a full-date comparison against `13.07.<baseYear>` — NOT a
 * recurring month/day compare and NOT a floor (probe-confirmed 2026-07-06: on the order fixture's `baseYear =
 * 2020`, a 2019 date does not fire). A model with NO base year rejects the literal at validation
 * (`MVK_INVALID_COMPARE_TO_DATE`), so the interpreter only ever sees it with a base year present. The
 * interpreter's `coerceDate` handled only the full `dd.MM.yyyy` form, so the omitted-year literal stayed an
 * un-coerced string and diverged. Authored via the typed DSL, rendered, run through BOTH engines.
 */
class OmittedYearDateDiffTest {

    private val order = RuntimeLaws.model("models/order.dm.json")   // modelInfo.baseYear = 2020
    private val m = BoundModel.ofJson(order)
    private val baseYear = 2020
    private fun render(c: Condition) = DslRenderer().render(c)

    private val fields = listOf(FieldDef(OD, Kind.DATE))
    private val after = render(m.date(OD).filled().and(m.date(OD).after(Dsl.dateValue("13.07."))))
    private val before = render(m.date(OD).filled().and(m.date(OD).before(Dsl.dateValue("13.07."))))

    private fun kFires(cond: String, date: String): Boolean =
        RuntimeLaws.messages(RuntimeLaws.withRule(order, "/Order", "R", "../OrderDate", "OY", cond),
            RuntimeLaws.doc().group("/Order", 1).field(OD, date, 1, 1)).any { it.code() == "OY" }

    private fun iFires(cond: String, date: String): Boolean {
        val def = ModelDef(fields, listOf(RuleDef("/Order/R", "/Order", cond, "OY", "../OrderDate")), baseYear = baseYear)
        val doc = Document(def.kinds, dateFormats = def.dateFormats).field(OD, date, 1, 1)
        return DmInterpreter(def).validateFull(doc).any { it.code == "OY" }
    }

    private fun agree(cond: String, date: String) = assertEquals(kFires(cond, date), iFires(cond, date), "cond=$cond date=$date")

    @Test fun afterInjectsBaseYear() {
        agree(after, "2024-07-20")   // 2024 > 2020 → fires
        agree(after, "2019-07-20")   // 2019 < 2020 → NOT fire (not a floor)
        agree(after, "2020-07-20")   // 2020-07-20 > 2020-07-13 (day/month, same year) → fires
        agree(after, "2020-07-10")   // 2020-07-10 < 2020-07-13 → NOT fire
        agree(after, "2020-07-13")   // equal → not strictly after
    }

    @Test fun beforeInjectsBaseYear() {
        agree(before, "2019-07-20")  // 2019 < 2020 → before → fires
        agree(before, "2020-07-10")  // 2020-07-10 < 2020-07-13 → fires
        agree(before, "2020-07-20")  // after → not before
        agree(before, "2024-01-01")  // 2024 > 2020 → not before
    }

    private companion object {
        const val OD = "/Order/OrderDate"
    }
}
