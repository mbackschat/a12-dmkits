package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.interpreter.DmInterpreter
import io.github.mbackschat.a12.dm.interpreter.eval.newDocument
import io.github.mbackschat.a12.dm.interpreter.model.DatePrecision
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
 * SPEC-2026-07-27-03 — the complete DateType partial-precision suffix matrix. Dynamic Groovy is normative,
 * generated Java is co-evidence, and the interpreter must match both. The modelkit fixture already carries
 * the real A12 DateType shape; each case changes only its declared precision.
 */
class PartialDatePrecisionDiffTest {

    private val order = RuntimeLaws.model("models/order.dm.json")
    private val bound = BoundModel.ofJson(order)
    private val approx = bound.date(APPROX)
    private fun render(condition: Condition): String = RuntimeLaws.render(condition)

    private fun model(precision: String, condition: String): String =
        RuntimeLaws.withRule(
            order.replace(
                """"datePrecision": "DAY_OPTIONAL"""",
                """"datePrecision": "$precision"""",
            ),
            "/Order",
            "PartialPrecision",
            "../ApproxDate",
            CODE,
            condition,
        )

    private fun definition(precision: String, condition: String) = ModelDef(
        fields = listOf(
            FieldDef(
                APPROX,
                Kind.DATE,
                format = "dd.MM.yyyy",
                datePrecision = enumValueOf<DatePrecision>(precision),
            ),
        ),
        rules = listOf(RuleDef("/Order/PartialPrecision", "/Order", condition, CODE, "../ApproxDate")),
    )

    private fun check(
        precision: String,
        value: String?,
        condition: Condition,
        expected: List<String>,
        label: String,
    ) {
        val rendered = render(condition)
        val model = model(precision, rendered)
        val kernelDoc = RuntimeLaws.doc().group("/Order", 1).also { doc ->
            if (value == null) doc.emptyField(APPROX, 1, 1) else doc.field(APPROX, value, 1, 1)
        }
        val relevant = setOf(CODE, "datumFormatFalsch", "datumFalsch")
        val dynamicAll = RuntimeLaws.messages(model, kernelDoc).map { it.code() }.sorted()
        val dynamic = dynamicAll.filter { it in relevant }
        val engine = RuntimeLaws.kernelJavaEngine(model)
        val javaStatic = engine.messages(engine.instance(kernelDoc)).map { it.code() }.filter { it in relevant }.sorted()
        val interpreterDoc = definition(precision, rendered).newDocument().group("/Order", 1).also { doc ->
            if (value == null) doc.emptyField(APPROX, 1, 1) else doc.field(APPROX, value, 1, 1)
        }
        val interpreted = DmInterpreter(definition(precision, rendered)).validateFull(interpreterDoc)
            .map { it.code }.filter { it in relevant }.sorted()

        assertEquals(expected, dynamic, "$label — kernel outcome moved (all messages: $dynamicAll)")
        assertEquals(dynamic, javaStatic, "$label — generated Java differs from dynamic Groovy")
        assertEquals(dynamic, interpreted, "$label — interpreter differs from the kernel")
    }

    @Test
    fun unknownMonthCompletesBeforeTheSelectedDayBoundary() {
        check(
            "MONTH_OPTIONAL",
            "00.00.2024",
            Dsl.valueAsDateFirstDay(bound.field(APPROX)).eq(Dsl.dateValue("01.01.2024")),
            listOf(CODE),
            "FirstDay selects January, then day 1",
        )
        check(
            "MONTH_OPTIONAL",
            "00.00.2024",
            Dsl.valueAsDateLastDay(bound.field(APPROX)).eq(Dsl.dateValue("31.12.2024")),
            listOf(CODE),
            "LastDay selects December, then its last day",
        )
    }

    @Test
    fun widerModesRetainTheNarrowerLegalShapes() {
        for (precision in listOf("MONTH_OPTIONAL", "YEAR_OPTIONAL")) {
            check(
                precision,
                "00.06.2024",
                Dsl.valueAsDateLastDay(bound.field(APPROX)).eq(Dsl.dateValue("30.06.2024")),
                listOf(CODE),
                "$precision accepts a known-year day omission",
            )
            check(
                precision,
                "15.06.2024",
                Dsl.valueAsDateFirstDay(bound.field(APPROX)).eq(Dsl.dateValue("15.06.2024")),
                listOf(CODE),
                "$precision accepts a fully known value",
            )
        }
    }

    @Test
    fun unknownYearIsUnknownRatherThanEmptyOrYear2000() {
        val distinguishesUnknown = approx.notFilled().or(
            Dsl.valueAsDateFirstDay(bound.field(APPROX)).eq(Dsl.dateValue("01.01.2000")),
        )
        check(
            "YEAR_OPTIONAL",
            "00.00.0000",
            distinguishesUnknown,
            emptyList(),
            "an unknown year is non-relevant",
        )
        check(
            "YEAR_OPTIONAL",
            null,
            distinguishesUnknown,
            listOf(CODE),
            "an ordinary empty value still takes the not-filled branch",
        )
        check(
            "YEAR_OPTIONAL",
            "00.00.0000",
            Dsl.valueAsDateFirstDay(bound.field(APPROX)).eq(Dsl.dateValue("01.01.2000")),
            emptyList(),
            "ValueAsDate does not leak the formal checker's temporary year 2000",
        )
    }

    @Test
    fun unknownComponentsMustFormASuffix() {
        check(
            "MONTH_OPTIONAL",
            "15.00.2024",
            approx.filled(),
            listOf("datumFalsch"),
            "an unknown month requires an unknown day",
        )
        check(
            "YEAR_OPTIONAL",
            "15.06.0000",
            approx.filled(),
            listOf("datumFalsch"),
            "an unknown year requires unknown month and day",
        )
    }

    private companion object {
        const val APPROX = "/Order/ApproxDate"
        const val CODE = "PD"
    }
}
