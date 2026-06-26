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
 * Differential — a quoted German-format DATE LITERAL (`"15.06.2024"`, `dd.MM.yyyy`) typed as a date by the
 * kernel when its counterpart is a date, and canonicalized so the comparison is chronological even though the
 * field's format (`yyyy-MM-dd`) differs. Covers ==, ordering, and argument position (`DifferenceInDays`).
 * A string field vs the same literal must STAY a plain string comparison (no canonicalization). Deterministic.
 */
class DateLiteralDiffTest {

    private val order = RuntimeLaws.model("models/order.dm.json")
    private val fields = listOf(FieldDef(OD, Kind.DATE), FieldDef(CN, Kind.STRING))

    // Conditions are authored with the rulekit typed DSL ([m]) and rendered to the canonical string fed to BOTH
    // engines — no free-text condition literals. The renderer emits absolute paths; both engines accept them.
    private val m = BoundModel.ofJson(order)
    private fun render(c: Condition): String = DslRenderer().render(c)

    private fun agree(cond: String, errorRel: String, set: (RuntimeLaws.Doc) -> Unit, setI: (Document) -> Unit, msg: String) {
        val k = RuntimeLaws.fires(RuntimeLaws.withRule(order, "/Order", "R", errorRel, "DL", cond), "DL",
            RuntimeLaws.doc().group("/Order", 1).also { set(it) })
        val def = ModelDef(fields, listOf(RuleDef("/Order/R", "/Order", cond, "DL", errorRel)))
        val iDoc = Document(def.kinds).also { setI(it) }
        val i = DmInterpreter(def).validate(iDoc).messages.any { it.code == "DL" }
        assertEquals(k, i, "$msg (kernel=$k interp=$i)")
    }

    private fun onDate(cond: String, date: String, msg: String) =
        agree(cond, "../OrderDate", { it.field(OD, date, 1, 1) }, { it.field(OD, date, 1, 1) }, msg)

    @Test fun equalsLiteral() {
        val c = render(m.date(OD).filled().and(m.date(OD).eq(Dsl.dateValue("15.06.2024"))))
        onDate(c, "2024-06-15", "the ISO field value equals the German literal")
        onDate(c, "2021-01-01", "a different date does not equal the literal")
    }

    @Test fun afterLiteral() {
        val c = render(m.date(OD).filled().and(m.date(OD).after(Dsl.dateValue("01.01.2022"))))
        onDate(c, "2024-06-15", "2024 is after 2022-01-01")
        onDate(c, "2021-01-01", "2021 is not after 2022-01-01")
    }

    @Test fun beforeLiteral() {
        val c = render(m.date(OD).filled().and(m.date(OD).before(Dsl.dateValue("01.01.2022"))))
        onDate(c, "2021-01-01", "2021 is before 2022-01-01")
        onDate(c, "2024-06-15", "2024 is not before 2022-01-01")
    }

    @Test fun literalInArgumentPosition() {
        // DifferenceInDays(a, b) = b − a; b is the literal 2022-01-01.
        val c = render(m.date(OD).filled().and(Dsl.differenceInDays(m.date(OD), Dsl.dateValue("01.01.2022")).gt(0)))
        onDate(c, "2021-01-01", "2022-01-01 − 2021-01-01 = +365 > 0")
        onDate(c, "2024-06-15", "2022-01-01 − 2024-06-15 < 0")
    }

    @Test fun valueListDateLikeValueStaysString() {
        // In a value LIST a date-looking entry is a plain STRING value (not a date constant) — proving the
        // canonicalization is position-scoped (comparison-vs-date / date-arg), never a blanket literal rewrite.
        // A STRING field whose RAW value is "15.06.2024" is in the list; the ISO form is not.
        val c = render(m.str(CN).filled().and(m.str(CN).`in`("15.06.2024")))
        agree(c, "../CustomerName", { it.field(CN, "15.06.2024", 1, 1) }, { it.field(CN, "15.06.2024", 1, 1) },
            "the raw date-looking string is in the value list")
        agree(c, "../CustomerName", { it.field(CN, "2024-06-15", 1, 1) }, { it.field(CN, "2024-06-15", 1, 1) },
            "the ISO form is not in the list (the entry was not date-canonicalized)")
    }

    private companion object {
        const val OD = "/Order/OrderDate"
        const val CN = "/Order/CustomerName"
    }
}
