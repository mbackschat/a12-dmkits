package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.interpreter.DmInterpreter
import io.github.mbackschat.a12.dm.interpreter.eval.Document
import io.github.mbackschat.a12.dm.interpreter.eval.newDocument
import io.github.mbackschat.a12.dm.interpreter.model.ComputationDef
import io.github.mbackschat.a12.dm.interpreter.model.FieldDef
import io.github.mbackschat.a12.dm.interpreter.model.Kind
import io.github.mbackschat.a12.dm.interpreter.model.ModelDef
import io.github.mbackschat.a12.dm.interpreter.model.RuleDef
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft.GroupBuilder
import io.github.mbackschat.a12.dm.modelkit.FieldKind
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.util.function.Consumer

/**
 * Differential — the 4-arg century `Date(day, month, century, year)` construction (IG12). The kernel splits the
 * year into a 2-digit century + 2-digit year (full year = `century*100 + year`) and evaluates a real date; the
 * interpreter previously declined it as `MALFORMED` (only 3 parts modeled), so `YearFromDate`/`Valid` diverged
 * silently. Date params must be field refs (the kernel rejects integer literals), so the parts are number fields.
 * Authored with the typed DSL (`Dsl.date/4` + `Dsl.yearFromDate`). The year arithmetic is proven by comparing
 * the COMPUTED `YearFromDate` value to the kernel's — no hardcoded expectation.
 */
class Date4ArgConstructionDiffTest {

    // Date-construction operands must carry a date-compatible NumberType format (ranges) — the kernel rejects a
    // plain number field with MVK_WRONG_DATE_FORMAT_FOR_OP. Day 1–31, Month 1–12, Century/Year 0–99 (2-digit).
    private val json = DocumentModelDraft.named("cal").locale("en_US")
        .rootGroup("Cal", Consumer { g: GroupBuilder ->
            g.numberField("Day", Consumer { it.min("1").max("31") })
                .numberField("Month", Consumer { it.min("1").max("12") })
                .numberField("Century", Consumer { it.min("0").max("99") })
                .numberField("Year2", Consumer { it.min("0").max("99") })
                .field("YearOut", FieldKind.NUMBER)
        })
        .build().toJson()

    private val m = BoundModel.ofJson(json)
    private val date4 = Dsl.date(m.num(DAY).asNumber(), m.num(MON).asNumber(), m.num(CENT).asNumber(), m.num(YR2).asNumber())
    private val fields = listOf(FieldDef(DAY, Kind.NUMBER), FieldDef(MON, Kind.NUMBER),
        FieldDef(CENT, Kind.NUMBER), FieldDef(YR2, Kind.NUMBER), FieldDef(OUT, Kind.NUMBER))

    private fun fill(doc: RuntimeLaws.Doc) = doc.group("/Cal", 1)
        .field(DAY, "15", 1, 1).field(MON, "6", 1, 1).field(CENT, "20", 1, 1).field(YR2, "24", 1, 1)
    private fun iFill(doc: Document) = doc.group("/Cal", 1)
        .field(DAY, "15", 1, 1).field(MON, "6", 1, 1).field(CENT, "20", 1, 1).field(YR2, "24", 1, 1)

    /** YearFromDate(Date(15, 6, 20, 24)) computed into YearOut — both engines must agree on the value. */
    @Test fun fourArgYearArithmeticMatchesKernel() {
        val op = RuntimeLaws.render("/Cal", Dsl.yearFromDate(date4).expression())
        val kModel = RuntimeLaws.withComputation(json, "/Cal", "YearOutComp", "../YearOut", op)
        val def = ModelDef(fields, emptyList(), computations = listOf(ComputationDef(OUT, "/Cal", op)))
        val kValue = RuntimeLaws.computedValue(RuntimeLaws.compute(kModel, fill(RuntimeLaws.doc())), OUT).orElse(null)
        val iValue = DmInterpreter(def).compute(iFill(def.newDocument())).firstOrNull { it.path == OUT }?.value
        assertEquals(kValue, iValue, "YearFromDate(Date(15,6,20,24)) computed — kernel=$kValue interp=$iValue")
    }

    /** Valid(Date(15, 6, 20, 24)) — a real century date fires on both engines (the 4-arg form is recognized). */
    @Test fun fourArgValidFiresLikeKernel() {
        val cond = RuntimeLaws.render("/Cal", m.num(YR2).filled().and(Dsl.valid(date4)))
        val kModel = RuntimeLaws.withRule(json, "/Cal", "V", "../Year2", "V", cond)
        val def = ModelDef(fields, listOf(RuleDef("/Cal/V", "/Cal", cond, "V", "../Year2")))
        val kFires = RuntimeLaws.firedCodes(kModel, fill(RuntimeLaws.doc())).contains("V")
        val iFires = DmInterpreter(def).validate(iFill(def.newDocument())).messages.any { it.code == "V" }
        assertEquals(kFires, iFires, "Valid(Date(15,6,20,24)) — kernel=$kFires interp=$iFires")
    }

    private companion object {
        const val DAY = "/Cal/Day"; const val MON = "/Cal/Month"; const val CENT = "/Cal/Century"
        const val YR2 = "/Cal/Year2"; const val OUT = "/Cal/YearOut"
    }
}
