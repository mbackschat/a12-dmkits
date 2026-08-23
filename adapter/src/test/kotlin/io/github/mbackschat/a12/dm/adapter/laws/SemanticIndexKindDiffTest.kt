package io.github.mbackschat.a12.dm.adapter.laws

import com.mgmtp.a12.kernel.core.customfieldtype.ICustomFieldTypeCheckError
import com.mgmtp.a12.kernel.core.customfieldtype.ICustomFieldTypeFactory
import com.mgmtp.a12.kernel.core.customfieldtype.ICustomFieldTypeValidationParam
import com.mgmtp.a12.kernel.core.customfieldtype.ICustomFieldValidator
import io.github.mbackschat.a12.dm.interpreter.DmInterpreter
import io.github.mbackschat.a12.dm.interpreter.eval.newDocument
import io.github.mbackschat.a12.dm.interpreter.load.ModelLoader
import io.github.mbackschat.a12.dm.interpreter.model.CustomFieldType
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel
import io.github.mbackschat.a12.dm.rulekit.dsl.Calculation
import io.github.mbackschat.a12.dm.rulekit.dsl.Condition
import io.github.mbackschat.a12.dm.rulekit.dsl.DslRenderer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.util.Optional
import java.util.function.Consumer

/** Every public semantic-index value kind, exercised through both kernel strategies and the interpreter. */
class SemanticIndexKindDiffTest {

    private val base = DocumentModelDraft.named("policy").locale("en_US")
        .rootGroup("Policy", Consumer { policy ->
            policy.stringField("PolicyNumber", Consumer { })
                .numberField("ExpectedNumber", Consumer { it.fractionDigits(0) })
                .dateField("ExpectedDate", Consumer { it.format("yyyy-MM-dd") })
                .dateFragmentField("ExpectedMonth", Consumer { it.fragmentFormat("yyyy-MM") })
                .timeField("ExpectedTime", Consumer { it.format("HH:mm:ss") })
                .dateTimeField("ExpectedDateTime", Consumer { it.format("yyyy-MM-dd'T'HH:mm:ss") })
                .dateRangeField("ExpectedWindow", Consumer { it.format("yyyy-MM-dd").separator("/") })
                .group("Coverages", Consumer { coverage ->
                    coverage.repeatable(20).indexField("Code")
                        .stringField("Code", Consumer { })
                        .numberField("Limit", Consumer { it.fractionDigits(0) })
                        .stringField("Tier", Consumer { })
                        .customField("RiskCode", Consumer { it.type(CUSTOM_TYPE) })
                        .enumField("Status", Consumer { it.value("ACTIVE").value("LAPSED") })
                        .dateField("StartDate", Consumer { it.format("yyyy-MM-dd") })
                        .dateFragmentField("CoverageMonth", Consumer { it.fragmentFormat("yyyy-MM") })
                        .timeField("StartTime", Consumer { it.format("HH:mm:ss") })
                        .dateTimeField("StartedAt", Consumer { it.format("yyyy-MM-dd'T'HH:mm:ss") })
                        .dateRangeField("CoverageWindow", Consumer { it.format("yyyy-MM-dd").separator("/") })
                })
        }).build().toJson()
    private val m = BoundModel.ofJson(base)
    private val render = DslRenderer()
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
    fun everyPublishedValueKindReadsTheIndexedRow() {
        val guard = m.str(POLICY_NUMBER).filled()
        val cases = listOf(
            Case("number", "num", guard.and(m.num(LIMIT).forIndex(KEY).gt(m.num(EXPECTED_NUMBER).asNumber())),
                LIMIT, "7", EXPECTED_NUMBER, "5", LIMIT, "3"),
            Case("string", "str", guard.and(m.str(TIER).forIndex(KEY).eq("GOLD")),
                TIER, "GOLD", null, null, TIER, "SILVER"),
            Case("custom", "custom", guard.and(m.custom(RISK_CODE).forIndex(KEY).eq("HIGH")),
                RISK_CODE, "HIGH", null, null, RISK_CODE, "LOW"),
            Case("enum", "enumOf", guard.and(m.enumOf(STATUS).forIndex(KEY).eq("ACTIVE")),
                STATUS, "ACTIVE", null, null, STATUS, "LAPSED"),
            Case("date", "date", guard.and(m.date(START_DATE).forIndex(KEY).after(m.date(EXPECTED_DATE))),
                START_DATE, "2024-06-02", EXPECTED_DATE, "2024-06-01", START_DATE, "2024-05-31"),
            Case("date_fragment", "dateFragment", guard.and(
                m.dateFragment(COVERAGE_MONTH).forIndex(KEY).eq(m.dateFragment(EXPECTED_MONTH))),
                COVERAGE_MONTH, "2024-06", EXPECTED_MONTH, "2024-06", COVERAGE_MONTH, "2024-05"),
            Case("time", "time", guard.and(m.time(START_TIME).forIndex(KEY).eq(m.time(EXPECTED_TIME))),
                START_TIME, "10:30:00", EXPECTED_TIME, "10:30:00", START_TIME, "10:31:00"),
            Case("date_time", "dateTime", guard.and(m.dateTime(STARTED_AT).forIndex(KEY).eq(m.dateTime(EXPECTED_DATE_TIME))),
                STARTED_AT, "2024-06-01T10:30:00", EXPECTED_DATE_TIME, "2024-06-01T10:30:00",
                STARTED_AT, "2024-06-01T10:31:00"),
            Case("date_range", "dateRange", guard.and(
                m.dateRange(COVERAGE_WINDOW).forIndex(KEY).eq(m.dateRange(EXPECTED_WINDOW))),
                COVERAGE_WINDOW, "2024-06-01/2024-06-30", EXPECTED_WINDOW, "2024-06-01/2024-06-30",
                COVERAGE_WINDOW, "2024-07-01/2024-07-31"),
        )

        val publishedEntryPoints = BoundModel::class.java.methods
            .filter { method ->
                method.parameterTypes.contentEquals(arrayOf(String::class.java)) &&
                    method.returnType.methods.any { candidate ->
                        candidate.name == "forIndex" &&
                            candidate.parameterTypes.contentEquals(arrayOf(String::class.java)) &&
                            candidate.returnType.methods.any { resultMember ->
                                resultMember.name == "expression" &&
                                    resultMember.returnType == Calculation::class.java
                            }
                    }
            }
            .map { it.name }
            .toSet()
        assertEquals(publishedEntryPoints, cases.map { it.entryPoint }.toSet(),
            "every BoundModel handle that publishes forIndex(String) needs a tri-engine case")

        cases.forEach(::assertCase)
    }

    private fun assertCase(case: Case) {
        val condition = render.render(case.condition)
        val code = "SI_${case.name.uppercase()}"
        val model = RuntimeLaws.withRule(base, "/Policy", "R_${case.name}", "../PolicyNumber", code, condition)
        val definition = ModelLoader.load(model)
        val java = RuntimeLaws.kernelJavaEngine(model)

        assertOutcome(case, model, definition, java, case.matchPath, case.matchValue, expected = true)
        assertOutcome(case, model, definition, java, case.missPath, case.missValue, expected = false)
    }

    private fun assertOutcome(
        case: Case,
        model: String,
        definition: io.github.mbackschat.a12.dm.interpreter.model.ModelDef,
        java: RuntimeLaws.JavaKernelEngine,
        selectedPath: String,
        selectedValue: String,
        expected: Boolean,
    ) {
        val kernelDoc = RuntimeLaws.doc().group("/Policy", 1).group("/Policy/Coverages", 1, 1)
            .field(POLICY_NUMBER, "P-1", 1, 1)
            .field(CODE, KEY, 1, 1, 1)
            .field(selectedPath, selectedValue, 1, 1, 1)
        val interpreterDoc = definition.newDocument()
            .group("/Policy", 1).group("/Policy/Coverages", 1, 1)
            .field(POLICY_NUMBER, "P-1", 1, 1)
            .field(CODE, KEY, 1, 1, 1)
            .field(selectedPath, selectedValue, 1, 1, 1)
        if (case.expectedPath != null) {
            kernelDoc.field(case.expectedPath, case.expectedValue!!, 1, 1)
            interpreterDoc.field(case.expectedPath, case.expectedValue, 1, 1)
        }

        val dynamic = RuntimeLaws.messages(model, kernelDoc, kernelTypes)
            .any { it.code() == "SI_${case.name.uppercase()}" }
        val generated = java.messages(java.instance(kernelDoc), kernelTypes)
            .any { it.code() == "SI_${case.name.uppercase()}" }
        val interpreter = DmInterpreter(definition, customFieldTypes = customTypes).validateFull(interpreterDoc)
            .any { it.code == "SI_${case.name.uppercase()}" }

        assertEquals(expected, dynamic, "${case.name} dynamic Groovy")
        assertEquals(dynamic, generated, "${case.name} generated Java")
        assertEquals(dynamic, interpreter, "${case.name} interpreter")
    }

    private data class Case(
        val name: String,
        val entryPoint: String,
        val condition: Condition,
        val matchPath: String,
        val matchValue: String,
        val expectedPath: String?,
        val expectedValue: String?,
        val missPath: String,
        val missValue: String,
    )

    private companion object {
        const val KEY = "AUTO"
        const val CUSTOM_TYPE = "RiskCode"
        const val POLICY_NUMBER = "/Policy/PolicyNumber"
        const val EXPECTED_NUMBER = "/Policy/ExpectedNumber"
        const val EXPECTED_DATE = "/Policy/ExpectedDate"
        const val EXPECTED_MONTH = "/Policy/ExpectedMonth"
        const val EXPECTED_TIME = "/Policy/ExpectedTime"
        const val EXPECTED_DATE_TIME = "/Policy/ExpectedDateTime"
        const val EXPECTED_WINDOW = "/Policy/ExpectedWindow"
        const val CODE = "/Policy/Coverages/Code"
        const val LIMIT = "/Policy/Coverages/Limit"
        const val TIER = "/Policy/Coverages/Tier"
        const val RISK_CODE = "/Policy/Coverages/RiskCode"
        const val STATUS = "/Policy/Coverages/Status"
        const val COVERAGE_WINDOW = "/Policy/Coverages/CoverageWindow"
        const val START_DATE = "/Policy/Coverages/StartDate"
        const val COVERAGE_MONTH = "/Policy/Coverages/CoverageMonth"
        const val START_TIME = "/Policy/Coverages/StartTime"
        const val STARTED_AT = "/Policy/Coverages/StartedAt"
    }
}
