package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.interpreter.DmInterpreter
import io.github.mbackschat.a12.dm.interpreter.eval.Document
import io.github.mbackschat.a12.dm.interpreter.model.ComputationDef
import io.github.mbackschat.a12.dm.interpreter.model.FieldDef
import io.github.mbackschat.a12.dm.interpreter.model.Kind
import io.github.mbackschat.a12.dm.interpreter.model.ModelDef
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel
import io.github.mbackschat.a12.dm.rulekit.dsl.Calculation
import io.github.mbackschat.a12.dm.rulekit.dsl.ConditionLanguage
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl
import io.github.mbackschat.a12.dm.rulekit.dsl.DslRenderer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Differential — a Date/DateTime `Add*` **amount** is an ordinary numeric operation converted through Java
 * `BigDecimal.intValue()`: truncate toward zero, then keep the low signed 32 bits (SPEC-2026-07-27-04, kernel
 * `BedingungsOperatorHelper.addTime` → `VkBigDecimal.intValue`). Large amounts are neither rejected nor
 * saturated, so the boundary is observable IN RANGE: `4294967303` (= 2³² + 7) shifts by exactly 7.
 *
 * Before the fix the interpreter parsed the amount with `toLongOrNull()`, so a fractional amount became NO
 * VALUE instead of a truncated shift, and the sub-day units multiplied the un-narrowed `Long`.
 *
 * The empty/expression-amount cases carry no expectation of their own: they compare the two engines, which is
 * what settles whether the interpreter's "empty amount ⇒ valueless result" projection is observable against
 * the kernel's "empty amount supplies zero, marked not-given".
 */
class DateShiftAmountDiffTest {

    private val schedule = schedule()
    private val m = BoundModel.ofJson(schedule)
    private fun renderOp(c: Calculation): String = DslRenderer(ConditionLanguage.EN, "/Schedule").render(c)

    private val dateFields = listOf(
        FieldDef(BASIS, Kind.DATE, format = "yyyy-MM-dd"),
        FieldDef(SHIFT, Kind.NUMBER, scale = 0),
        FieldDef(LANDED, Kind.DATE, format = "yyyy-MM-dd"),
    )
    private val stampFields = listOf(
        FieldDef(STAMP, Kind.DATE_TIME, format = "yyyy-MM-dd'T'HH:mm:ss"),
        FieldDef(SHIFT, Kind.NUMBER, scale = 0),
        FieldDef(LANDED_AT, Kind.DATE_TIME, format = "yyyy-MM-dd'T'HH:mm:ss"),
    )

    /** Kernel and interpreter computed value of one `AddDays(Basis, amount)` operation. */
    private fun days(amount: String, basis: String = "2024-06-01", shift: String? = null): Pair<String?, String?> =
        both(renderOp(Dsl.addDays(m.date(BASIS), Dsl.number(amount)).expression()), dateFields, LANDED,
            BASIS to basis, shift)

    private fun seconds(amount: String, stamp: String = "2024-06-01T10:00:00"): Pair<String?, String?> =
        both(renderOp(Dsl.addSeconds(m.dateTime(STAMP), Dsl.number(amount)).expression()), stampFields, LANDED_AT,
            STAMP to stamp, null)

    private fun both(
        operation: String,
        fields: List<FieldDef>,
        target: String,
        source: Pair<String, String>,
        shift: String?,
    ): Pair<String?, String?> {
        val targetRel = "../" + target.substringAfterLast('/')
        val model = RuntimeLaws.withComputation(schedule, "/Schedule", "LandComp", targetRel, operation)
        val kDoc = RuntimeLaws.doc().group("/Schedule", 1).field(source.first, source.second, 1, 1)
        shift?.let { kDoc.field(SHIFT, it, 1, 1) }
        val kernel = RuntimeLaws.computedValue(RuntimeLaws.compute(model, kDoc), target).orElse(null)

        val def = ModelDef(fields, emptyList(), computations = listOf(ComputationDef(target, "/Schedule", operation)))
        val iDoc = Document(def.kinds).group("/Schedule", 1).field(source.first, source.second, 1, 1)
        shift?.let { iDoc.field(SHIFT, it, 1, 1) }
        val interp = DmInterpreter(def).compute(iDoc).firstOrNull { it.path == target }?.value
        return kernel to interp
    }

    private fun agree(pair: Pair<String?, String?>, msg: String) = assertEquals(pair.first, pair.second, msg)

    /** Agreement PLUS the kernel value, so a case can never pass by both engines returning nothing. */
    private fun agree(pair: Pair<String?, String?>, kernelValue: String?, msg: String) {
        assertEquals(kernelValue, pair.first, "$msg — kernel value moved")
        assertEquals(pair.first, pair.second, msg)
    }

    @Test fun aWholeAmountShifts() = agree(days("7"), "2024-06-08", "+7 days")

    @Test fun aFractionalAmountTruncatesTowardZero() {
        agree(days("1.9"), "1.9 truncates to +1")
        agree(days("-1.9"), "-1.9 truncates to -1")
        agree(days("0.9"), "0.9 truncates to 0")
    }

    @Test fun anAmountBeyondThirtyTwoBitsWraps() {
        agree(days("4294967303"), "2^32 + 7 narrows to +7")
        agree(days("-4294967289"), "-(2^32 - 7) narrows to +7")
        agree(days("4294967296"), "2^32 narrows to 0")
    }

    @Test fun theSubDayUnitsNarrowBeforeMultiplying() {
        agree(seconds("7"), "+7 seconds")
        agree(seconds("4294967303"), "2^32 + 7 seconds narrows to +7")
        agree(seconds("1.9"), "1.9 seconds truncates to +1")
    }

    @Test fun aDomainInvalidAmountLeavesNoValue() =
        agree(both(renderOp(Dsl.addDays(m.date(BASIS), m.num(SHIFT).asNumber().dividedBy(Dsl.number("0"))).expression()),
            dateFields, LANDED, BASIS to "2024-06-01", "5"), null,
            "a domain-invalid amount supplies no amount, so the target stays valueless")

    @Test fun anEmptyDirectAmountFieldAgrees() =
        agree(both(renderOp(Dsl.addDays(m.date(BASIS), m.num(SHIFT).asNumber()).expression()),
            dateFields, LANDED, BASIS to "2024-06-01", null), "2024-06-01",
            "an empty amount field supplies zero, so the source date is stored unchanged")

    @Test fun anEmptyAmountInsideAnExpressionAgrees() =
        agree(both(renderOp(Dsl.addDays(m.date(BASIS), m.num(SHIFT).asNumber().plus(Dsl.number("1"))).expression()),
            dateFields, LANDED, BASIS to "2024-06-01", null), "2024-06-02",
            "an empty field inside an expression still supplies a CONCRETE amount of 1")

    private companion object {
        const val BASIS = "/Schedule/Basis"
        const val STAMP = "/Schedule/Stamp"
        const val SHIFT = "/Schedule/Shift"
        const val LANDED = "/Schedule/Landed"
        const val LANDED_AT = "/Schedule/LandedAt"

        fun schedule(): String = DocumentModelDraft.named("schedule").locale("en_US")
            .rootGroup("Schedule") { s ->
                s.dateField("Basis") { d -> d.format("yyyy-MM-dd") }
                    .dateTimeField("Stamp") { d -> d.format("yyyy-MM-dd'T'HH:mm:ss") }
                    .numberField("Shift") { n -> n.fractionDigits(0) }
                    .dateField("Landed") { d -> d.format("yyyy-MM-dd") }
                    .dateTimeField("LandedAt") { d -> d.format("yyyy-MM-dd'T'HH:mm:ss") }
            }
            .build().toJson()
    }
}
