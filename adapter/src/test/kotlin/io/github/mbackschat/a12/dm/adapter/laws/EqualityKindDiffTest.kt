package io.github.mbackschat.a12.dm.adapter.laws

import com.mgmtp.a12.kernel.core.customfieldtype.ICustomFieldTypeCheckError
import com.mgmtp.a12.kernel.core.customfieldtype.ICustomFieldTypeFactory
import com.mgmtp.a12.kernel.core.customfieldtype.ICustomFieldTypeValidationParam
import com.mgmtp.a12.kernel.core.customfieldtype.ICustomFieldValidator
import io.github.mbackschat.a12.dm.core.ValueKind
import io.github.mbackschat.a12.dm.interpreter.DmInterpreter
import io.github.mbackschat.a12.dm.interpreter.eval.newDocument
import io.github.mbackschat.a12.dm.interpreter.load.ModelLoader
import io.github.mbackschat.a12.dm.interpreter.model.CustomFieldType
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import io.github.mbackschat.a12.dm.modelkit.FieldKind
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel
import io.github.mbackschat.a12.dm.rulekit.dsl.Condition
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.util.Optional
import java.util.function.Consumer

/**
 * Equality's complete published value-kind matrix. For every comparable family, equal and unequal
 * documents must make `==` and `!=` exact complements on dynamic Groovy, static Java, and the
 * kernel-free interpreter. This is deliberately a value matrix, separate from the admission laws
 * for cross-kind and incompatible-enum pairs.
 */
class EqualityKindDiffTest {

    private val base = DocumentModelDraft.named("equality").locale("en_US")
        .rootGroup("Equality", Consumer { root ->
            root.stringField("Guard", Consumer { })
                .numberField("Number", Consumer { it.fractionDigits(0) })
                .stringField("Text", Consumer { })
                .enumField("Priority", Consumer { it.value("EXPRESS").value("STANDARD") })
                .field("Boolean", FieldKind.BOOLEAN)
                .field("Confirm", FieldKind.CONFIRM)
                .dateField("DateLeft", Consumer { it.format("yyyy-MM-dd") })
                .dateField("DateRight", Consumer { it.format("yyyy-MM-dd") })
                .dateTimeField("DateTimeLeft", Consumer { it.format("yyyy-MM-dd'T'HH:mm:ss") })
                .dateTimeField("DateTimeRight", Consumer { it.format("yyyy-MM-dd'T'HH:mm:ss") })
                .timeField("Time", Consumer { it.format("HH:mm:ss") })
                .dateFragmentField("MonthLeft", Consumer { it.fragmentFormat("yyyy-MM") })
                .dateFragmentField("MonthRight", Consumer { it.fragmentFormat("yyyy-MM") })
                .dateRangeField("RangeLeft", Consumer { it.format("dd.MM.yyyy").separator("-") })
                .dateRangeField("RangeRight", Consumer { it.format("dd.MM.yyyy").separator("-") })
                .customField("Custom", Consumer { it.type(CUSTOM_TYPE) })
        }).build().toJson()
    private val m = BoundModel.ofJson(base)
    private val customTypes = mapOf(CUSTOM_TYPE to CustomFieldType { _, _ -> null })
    private val kernelTypes = object : ICustomFieldTypeFactory {
        override fun createCustomFieldValidator(name: String): Optional<ICustomFieldValidator> =
            if (name != CUSTOM_TYPE) Optional.empty() else Optional.of(object : ICustomFieldValidator {
                override fun validate(
                    value: String,
                    parameter: ICustomFieldTypeValidationParam,
                    isDisplay: Boolean,
                ): Optional<ICustomFieldTypeCheckError> = Optional.empty()
            })
    }

    @Test
    fun everyComparableValueKindUsesStoredValueEquality() {
        val cases = listOf(
            Case(ValueKind.NUMBER, m.num(NUMBER).eq("3"), m.num(NUMBER).ne("3"),
                mapOf(NUMBER to "3"), mapOf(NUMBER to "4")),
            Case(ValueKind.STRING, m.str(TEXT).eq("Acme"), m.str(TEXT).ne("Acme"),
                mapOf(TEXT to "Acme"), mapOf(TEXT to "Other")),
            Case(ValueKind.ENUMERATION, m.enumOf(PRIORITY).eq("EXPRESS"), m.enumOf(PRIORITY).ne("EXPRESS"),
                mapOf(PRIORITY to "EXPRESS"), mapOf(PRIORITY to "STANDARD")),
            Case(ValueKind.BOOLEAN, m.bool(BOOLEAN).isTrue(), m.bool(BOOLEAN).isNotTrue(),
                mapOf(BOOLEAN to "true"), mapOf(BOOLEAN to "false")),
            Case(ValueKind.CONFIRM, m.confirm(CONFIRM).confirmed(), m.confirm(CONFIRM).notConfirmed(),
                mapOf(CONFIRM to "true"), emptyMap()),
            Case(ValueKind.DATE, m.date(DATE_LEFT).eq(m.date(DATE_RIGHT)), m.date(DATE_LEFT).ne(m.date(DATE_RIGHT)),
                mapOf(DATE_LEFT to "2024-06-01", DATE_RIGHT to "2024-06-01"),
                mapOf(DATE_LEFT to "2024-06-01", DATE_RIGHT to "2024-06-02")),
            Case(ValueKind.DATE_TIME, m.dateTime(DATE_TIME_LEFT).eq(m.dateTime(DATE_TIME_RIGHT)),
                m.dateTime(DATE_TIME_LEFT).ne(m.dateTime(DATE_TIME_RIGHT)),
                mapOf(DATE_TIME_LEFT to "2024-06-01T10:00:00", DATE_TIME_RIGHT to "2024-06-01T10:00:00"),
                mapOf(DATE_TIME_LEFT to "2024-06-01T10:00:00", DATE_TIME_RIGHT to "2024-06-01T10:00:01")),
            Case(ValueKind.TIME, m.time(TIME).eq(Dsl.time("10", "00", "00")),
                m.time(TIME).ne(Dsl.time("10", "00", "00")),
                mapOf(TIME to "10:00:00"), mapOf(TIME to "10:00:01")),
            Case(ValueKind.DATE_FRAGMENT, m.dateFragment(MONTH_LEFT).eq(m.dateFragment(MONTH_RIGHT)),
                m.dateFragment(MONTH_LEFT).ne(m.dateFragment(MONTH_RIGHT)),
                mapOf(MONTH_LEFT to "2024-06", MONTH_RIGHT to "2024-06"),
                mapOf(MONTH_LEFT to "2024-06", MONTH_RIGHT to "2024-07")),
            Case(ValueKind.DATE_RANGE, m.dateRange(RANGE_LEFT).eq(m.dateRange(RANGE_RIGHT)),
                m.dateRange(RANGE_LEFT).ne(m.dateRange(RANGE_RIGHT)),
                mapOf(RANGE_LEFT to "01.06.2024-30.06.2024", RANGE_RIGHT to "01.06.2024-30.06.2024"),
                mapOf(RANGE_LEFT to "01.06.2024-30.06.2024", RANGE_RIGHT to "01.06.2024-29.06.2024")),
            Case(ValueKind.CUSTOM, m.custom(CUSTOM).eq("A7"), m.custom(CUSTOM).ne("A7"),
                mapOf(CUSTOM to "A7"), mapOf(CUSTOM to "B8")),
        )

        val publishedKinds = ValueKind.entries.filterNot { it == ValueKind.UNKNOWN }.toSet()
        assertEquals(cases.size, cases.map { it.kind }.toSet().size, "one equality case per value kind")
        assertEquals(publishedKinds, cases.map { it.kind }.toSet(),
            "every published value kind needs a tri-engine equality case")
        cases.forEach(::check)
    }

    private fun check(case: Case) {
        val guard = m.str(GUARD).filled()
        val ruleSuffix = case.kind.name.lowercase()
        val modelWithEqual = RuntimeLaws.withRule(base, ROOT, "Equal_$ruleSuffix", "../Guard",
            EQUAL, RuntimeLaws.render(guard.and(case.equal)))
        val model = RuntimeLaws.withRule(modelWithEqual, ROOT, "NotEqual_$ruleSuffix", "../Guard",
            NOT_EQUAL, RuntimeLaws.render(guard.and(case.notEqual)))
        val definition = ModelLoader.load(model)
        val static = RuntimeLaws.kernelJavaEngine(model)

        assertOutcomes(case, model, definition, static, case.equalValues, setOf(EQUAL))
        assertOutcomes(case, model, definition, static, case.differentValues, setOf(NOT_EQUAL))
    }

    private fun assertOutcomes(
        case: Case,
        model: String,
        definition: io.github.mbackschat.a12.dm.interpreter.model.ModelDef,
        static: RuntimeLaws.JavaKernelEngine,
        values: Map<String, String>,
        expected: Set<String>,
    ) {
        val allValues = values + (GUARD to "present")
        val kernelDocument = RuntimeLaws.doc().group(ROOT, 1)
        allValues.forEach { (path, value) -> kernelDocument.field(path, value, 1, 1) }
        val dynamicCodes = RuntimeLaws.messages(model, kernelDocument, kernelTypes)
            .map { it.code() }.filter { it == EQUAL || it == NOT_EQUAL }.toSet()
        val staticCodes = static.messages(static.instance(kernelDocument), kernelTypes)
            .map { it.code() }.filter { it == EQUAL || it == NOT_EQUAL }.toSet()

        val interpreterDocument = definition.newDocument().group(ROOT, 1)
        allValues.forEach { (path, value) -> interpreterDocument.field(path, value, 1, 1) }
        val interpreterCodes = DmInterpreter(definition, customFieldTypes = customTypes).validateFull(interpreterDocument)
            .map { it.code }.filter { it == EQUAL || it == NOT_EQUAL }.toSet()

        assertEquals(expected, dynamicCodes, "${case.kind} dynamic Groovy")
        assertEquals(expected, staticCodes, "${case.kind} static Java")
        assertEquals(expected, interpreterCodes, "${case.kind} interpreter")
    }

    private data class Case(
        val kind: ValueKind,
        val equal: Condition,
        val notEqual: Condition,
        val equalValues: Map<String, String>,
        val differentValues: Map<String, String>,
    )

    private companion object {
        const val CUSTOM_TYPE = "EqualityCode"
        const val ROOT = "/Equality"
        const val GUARD = "/Equality/Guard"
        const val NUMBER = "/Equality/Number"
        const val TEXT = "/Equality/Text"
        const val PRIORITY = "/Equality/Priority"
        const val BOOLEAN = "/Equality/Boolean"
        const val CONFIRM = "/Equality/Confirm"
        const val DATE_LEFT = "/Equality/DateLeft"
        const val DATE_RIGHT = "/Equality/DateRight"
        const val DATE_TIME_LEFT = "/Equality/DateTimeLeft"
        const val DATE_TIME_RIGHT = "/Equality/DateTimeRight"
        const val TIME = "/Equality/Time"
        const val MONTH_LEFT = "/Equality/MonthLeft"
        const val MONTH_RIGHT = "/Equality/MonthRight"
        const val RANGE_LEFT = "/Equality/RangeLeft"
        const val RANGE_RIGHT = "/Equality/RangeRight"
        const val CUSTOM = "/Equality/Custom"
        const val EQUAL = "EQ_KIND"
        const val NOT_EQUAL = "NE_KIND"
    }
}
