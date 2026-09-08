package io.github.mbackschat.a12.dm.bridge

import io.github.mbackschat.a12.dm.core.ModelIndex
import io.github.mbackschat.a12.dm.core.ReferenceResolvers
import io.github.mbackschat.a12.dm.interpreter.model.ConditionLanguage
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import io.github.mbackschat.a12.dm.modelkit.FieldKind
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl
import io.github.mbackschat.a12.dm.rulekit.dsl.Severity
import io.github.mbackschat.a12.dm.rulekit.read.ReadResult
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel
import io.github.mbackschat.a12.dm.rulekit.dsl.ComparisonCondition
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * The bridge reads condition TEXT, so its extraction operand arrives as a bare expression with no declared
 * format attached — which is exactly the case rulekit's typed handles cannot cover. Routing through
 * `Dsl.dateExtraction`/`dateTimePart` gives a kernel-free consumer the real verdict where it used to get
 * silence: the kernel refuses `DayFromDate` over a `yyyy-MM` field (KF1001), and so does this.
 *
 * The refusal is rulekit's, not the bridge's — the bridge states no rule of its own here.
 */
class TemporalFormatGateTest {

    private val json = DocumentModelDraft.named("bridge-format-gate").locale("en_US")
        .rootGroup("Order") { o ->
            o.field("Error", FieldKind.STRING)
                .dateField("YearMonth") { it.format("yyyy-MM") }
                .dateField("FullDate") { it.format("yyyy-MM-dd") }
                .dateTimeField("Stamp") { it.format("yyyy-MM-dd'T'HH:mm:ss") }
                .dateFragmentField("YearOnly") { it.fragmentFormat("yyyy") }
        }
        .build().toJson()

    private val bridge = ConditionBridge(
        BoundModel.ofJson(json),
        ModelIndex.of(json, ReferenceResolvers.selfContained()),
    )

    private fun translate(condition: String) =
        bridge.translate(condition, "/Order", ConditionLanguage.EN)

    @Test
    fun `an extraction whose component the declared format carries converts`() {
        for (condition in listOf(
            "YearFromDate(YearMonth) > 2000",
            "DayFromDate(FullDate) > 1",
            "YearFromDate(YearOnly) > 2000",
            "HoursFromTime(Stamp) > 0",
        )) {
            assertInstanceOf(ComparisonCondition::class.java, translate(condition), condition)
        }
    }

    @Test
    fun `an extraction whose component the declared format lacks is refused`() {
        val refusal = assertThrows<IllegalArgumentException> { translate("DayFromDate(YearMonth) > 1") }
        assertTrue(refusal.message!!.contains("MVK_WRONG_DATE_FORMAT_FOR_OP"), refusal.message)
        assertTrue(refusal.message!!.contains("yyyy-MM"), refusal.message)

        assertThrows<IllegalArgumentException> { translate("MonthFromDate(YearOnly) > 1") }
        assertThrows<IllegalArgumentException> { translate("HoursFromTime(FullDate) > 0") }
    }

    @Test
    fun `the split family needs a source carrying both halves`() {
        assertInstanceOf(ComparisonCondition::class.java, translate("DateFromDateTime(Stamp) == Today"))
        assertThrows<IllegalArgumentException> { translate("DateFromDateTime(FullDate) == Today") }
    }

    /** An operand with no declaration to read stays delegated — the bridge must not invent a verdict. */
    @Test
    fun `an expression operand is delegated rather than refused`() {
        assertInstanceOf(
            ComparisonCondition::class.java,
            translate("YearFromDate(AddDays(FullDate, 1)) > 2000"),
        )
    }

    /**
     * What the refusal costs a CONSUMER, which is the question the throw above does not answer: `readAll`
     * catches every RuntimeException per rule, so a format-illegal condition becomes `Opaque` carrying its
     * verbatim text and the reason, the rest of the model still reads, and nothing is lost or unreadable.
     *
     * Such a model is not kernel-legal in the first place — the kernel refuses the condition at authoring —
     * so the fixture has to be corrupted after emission to produce one.
     */
    @Test
    fun `a stored format-illegal condition reads back as Opaque, not as a failure`() {
        val bound = BoundModel.ofJson(json)
        val legal = bound.add(
            bound.rule(bound.group("/Order")).name("Extraction")
                .errorField(bound.field("/Order/Error"))
                .`when`(
                    Dsl.fieldFilled(bound.field("/Order/Error"))
                        .and(Dsl.secondsFromTime(bound.dateTime("/Order/Stamp")).gt(0)),
                )
                .code("C").severity(Severity.ERROR).message(Dsl.en("x")).build(),
        ).toJson()

        // Repoint the operand at a date-only field: legal text when written, format-illegal as stored.
        val corrupted = legal.replace("SecondsFromTime(Stamp)", "SecondsFromTime(FullDate)")
        assertTrue(corrupted != legal, "the corruption under test did not apply")

        val read = InterpreterConditionReadBack()
            .readAll(corrupted, ReferenceResolvers.selfContained())
        val result = read["/Order/Extraction"]
        val opaque = assertInstanceOf(ReadResult.Opaque::class.java, result)
        assertTrue(opaque.canonicalText().contains("SecondsFromTime"), opaque.canonicalText())
        assertTrue(opaque.reason().contains("MVK_WRONG_DATE_FORMAT_FOR_OP"), opaque.reason())
    }
}
