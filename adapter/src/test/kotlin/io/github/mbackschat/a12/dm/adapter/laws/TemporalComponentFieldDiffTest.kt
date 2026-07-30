package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.interpreter.DmInterpreter
import io.github.mbackschat.a12.dm.interpreter.eval.newDocument
import io.github.mbackschat.a12.dm.interpreter.load.ModelLoader
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import io.github.mbackschat.a12.dm.adapter.KernelAdapter
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel
import io.github.mbackschat.a12.dm.rulekit.dsl.Condition
import io.github.mbackschat.a12.dm.rulekit.dsl.ConditionLanguage
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl
import io.github.mbackschat.a12.dm.rulekit.dsl.DslRenderer
import io.github.mbackschat.a12.dm.rulekit.dsl.Rule
import io.github.mbackschat.a12.dm.rulekit.dsl.Severity
import io.github.mbackschat.a12.dm.rulekit.dsl.TemporalComponent
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl.en
import io.github.mbackschat.a12.dm.rulekit.read.ReadResult
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Test

/**
 * Runtime half of SPEC-2026-07-27-13/-08: admitted String and exact-`yyyy` Date fields are converted through
 * the constructor's numeric component route. Dynamic Groovy is normative, generated Java is co-evidence, and
 * the kernel-free interpreter consumes the same modelkit/rulekit-authored DM-JSON on JVM. The multiplatform
 * peer is `interpreter TemporalComponentFieldTest`.
 */
class TemporalComponentFieldDiffTest {

    private val baseJson = DocumentModelDraft.named("component-runtime").locale("en_US")
        .rootGroup("Parts") { group ->
            group.stringField("Day") { it.maxLength(2).pattern("[0-9]+", "digits") }
                .dateField("Year") { it.format("yyyy") }
                .stringField("Hour") { it.maxLength(2).pattern("\\d+", "digits") }
                .stringField("Second") { it.maxLength(2).pattern("[0-9]{2}", "digits") }
                .dateField("DateTarget") { it.format("yyyy-MM-dd") }
                .timeField("TimeTarget") { it.format("HH:mm:ss") }
        }
        .build().toJson()

    private val model = BoundModel.ofJson(baseJson)
    private val day: TemporalComponent = model.field(DAY)
    private val year: TemporalComponent = model.field(YEAR)
    private val hour: TemporalComponent = model.field(HOUR)
    private val second: TemporalComponent = model.field(SECOND)
    private val date = Dsl.date(day, Dsl.temporalComponent("01"), year)
    private val time = Dsl.time(hour, Dsl.temporalComponent("30"), second)
    private val authoredModel = model.addAll(
        listOf(
            rule(DATE_VALID, DAY, Dsl.valid(date)),
            rule(DATE_INVALID, DAY, Dsl.invalid(date)),
            rule(TIME_EQUAL, HOUR, model.time(TIME_TARGET).eq(time)),
        ),
        emptyList(),
    )
    private val modelJson = authoredModel.toJson()
    private val definition = ModelLoader.load(modelJson)
    private val javaEngine = RuntimeLaws.kernelJavaEngine(modelJson)

    @Test
    fun leadingZeroStringAndYearDateReachTheSameRealDate() {
        check(
            values = mapOf(DAY to "01", YEAR to "1963", DATE_TARGET to "1963-01-01"),
            expected = listOf("$DATE_VALID|VALUE"),
            label = "String Day plus yyyy Date Year",
            relevant = DATE_CODES,
        )
    }

    @Test
    fun presentOutOfRangeStringStaysUnrealRatherThanEmpty() {
        check(
            values = mapOf(DAY to "32", YEAR to "1963", DATE_TARGET to "1963-01-01"),
            expected = listOf("$DATE_INVALID|VALUE"),
            label = "present Day 32",
            relevant = DATE_CODES,
        )
    }

    @Test
    fun emptyAndFormalStringSourcesKeepTheirDistinctNoValueReasons() {
        check(
            values = mapOf(YEAR to "1963", DATE_TARGET to "1963-01-01"),
            empty = setOf(DAY),
            expected = listOf("$DATE_INVALID|OMISSION"),
            label = "empty Day",
            relevant = DATE_CODES,
        )
        check(
            values = mapOf(DAY to "xx", YEAR to "1963", DATE_TARGET to "1963-01-01"),
            expected = emptyList(),
            label = "formally invalid Day",
            relevant = DATE_CODES,
        )
    }

    @Test
    fun timeStringComponentsConvertLeadingZerosAndDoNotClampImpossibleHours() {
        check(
            values = mapOf(HOUR to "09", SECOND to "05", TIME_TARGET to "09:30:05"),
            expected = listOf("$TIME_EQUAL|VALUE"),
            label = "String Time components",
            relevant = TIME_CODES,
        )
        check(
            values = mapOf(HOUR to "24", SECOND to "05", TIME_TARGET to "00:30:05"),
            expected = emptyList(),
            label = "present impossible Hour",
            relevant = TIME_CODES,
        )
        check(
            values = mapOf(SECOND to "05", TIME_TARGET to "00:30:05"),
            empty = setOf(HOUR),
            expected = emptyList(),
            label = "empty Hour",
            relevant = TIME_CODES,
        )
    }

    @Test
    fun mixedTypedComponentsRoundTripThroughTheKernelModel() {
        val renderer = DslRenderer(ConditionLanguage.EN)
        val adapter = KernelAdapter.of(authoredModel)
        val dateRead = assertInstanceOf(ReadResult.Converted::class.java, adapter.read("$GROUP/$DATE_VALID"))
        val timeRead = assertInstanceOf(ReadResult.Converted::class.java, adapter.read("$GROUP/$TIME_EQUAL"))

        assertEquals(renderer.render(Dsl.valid(date)), renderer.render(dateRead.condition()))
        assertEquals(renderer.render(model.time(TIME_TARGET).eq(time)), renderer.render(timeRead.condition()))
    }

    private fun rule(code: String, errorPath: String, condition: Condition): Rule =
        model.rule(model.group(GROUP))
            .name(code)
            .errorField(model.field(errorPath))
            .`when`(condition)
            .code(code)
            .severity(Severity.ERROR)
            .message(en("probe"))
            .build()

    private fun check(
        values: Map<String, String>,
        empty: Set<String> = emptySet(),
        expected: List<String>,
        label: String,
        relevant: Set<String>,
    ) {
        val kernelDoc = RuntimeLaws.doc().group(GROUP, 1).also { doc ->
            values.forEach { (path, value) -> doc.field(path, value, 1, 1) }
            empty.forEach { path -> doc.emptyField(path, 1, 1) }
        }
        val dynamic = RuntimeLaws.messages(modelJson, kernelDoc).mapNotNull { kernelSignature(it, relevant) }.sorted()
        val generated = javaEngine.messages(javaEngine.instance(kernelDoc))
            .mapNotNull { kernelSignature(it, relevant) }.sorted()
        val interpreterDoc = definition.newDocument().group(GROUP, 1).also { doc ->
            values.forEach { (path, value) -> doc.field(path, value, 1, 1) }
            empty.forEach { path -> doc.emptyField(path, 1, 1) }
        }
        val interpreted = DmInterpreter(definition).validateFull(interpreterDoc).mapNotNull { message ->
            if (message.code !in relevant) null else "${message.code}|${message.type.name}"
        }.sorted()

        assertEquals(expected, dynamic, "$label — dynamic Groovy")
        assertEquals(dynamic, generated, "$label — generated Java")
        assertEquals(dynamic, interpreted, "$label — interpreter")
    }

    private fun kernelSignature(message: RuntimeLaws.Msg, relevant: Set<String>): String? =
        if (message.code() !in relevant) null
        else "${message.code()}|${message.type().name.removeSuffix("_ERROR")}"

    private companion object {
        const val GROUP = "/Parts"
        const val DAY = "$GROUP/Day"
        const val YEAR = "$GROUP/Year"
        const val HOUR = "$GROUP/Hour"
        const val SECOND = "$GROUP/Second"
        const val DATE_TARGET = "$GROUP/DateTarget"
        const val TIME_TARGET = "$GROUP/TimeTarget"
        const val DATE_VALID = "DATE_VALID"
        const val DATE_INVALID = "DATE_INVALID"
        const val TIME_EQUAL = "TIME_EQUAL"
        val DATE_CODES = setOf(DATE_VALID, DATE_INVALID)
        val TIME_CODES = setOf(TIME_EQUAL)
    }
}
