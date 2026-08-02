package io.github.mbackschat.a12.dm.adapter.laws

import com.mgmtp.a12.kernel.core.customfieldtype.ICustomFieldTypeCheckError
import com.mgmtp.a12.kernel.core.customfieldtype.ICustomFieldTypeFactory
import com.mgmtp.a12.kernel.core.customfieldtype.ICustomFieldTypeValidationParam
import com.mgmtp.a12.kernel.core.customfieldtype.ICustomFieldValidator
import io.github.mbackschat.a12.dm.interpreter.DmInterpreter
import io.github.mbackschat.a12.dm.interpreter.eval.newDocument
import io.github.mbackschat.a12.dm.interpreter.load.ModelLoader
import io.github.mbackschat.a12.dm.interpreter.model.ComputeOutcome
import io.github.mbackschat.a12.dm.interpreter.model.CustomFieldType
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel
import io.github.mbackschat.a12.dm.rulekit.dsl.Computation
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.util.Optional
import java.util.function.Consumer

/** Missing per-kind FFV identities: BOOLEAN, CUSTOM and DATE_FRAGMENT, through all three engines. */
class FirstFilledValueAdditionalKindDiffTest {

    private val base = DocumentModelDraft.named("review").locale("en_US")
        .rootGroup("Review", Consumer { root ->
            root.field("FirstApproved", io.github.mbackschat.a12.dm.modelkit.FieldKind.BOOLEAN)
                .customField("FirstCode", Consumer { it.type(CUSTOM_TYPE) })
                .dateFragmentField("FirstMonth", Consumer { it.fragmentFormat("MM") })
                .group("Rows", Consumer { rows ->
                    rows.repeatable(10)
                        .field("Approved", io.github.mbackschat.a12.dm.modelkit.FieldKind.BOOLEAN)
                        .customField("Code", Consumer { it.type(CUSTOM_TYPE) })
                        .dateFragmentField("Month", Consumer { it.fragmentFormat("MM") })
                })
        }).build().toJson()
    private val model = BoundModel.ofJson(base)
    private val computations = listOf(
        computation("Approved", model.bool(FIRST_APPROVED).field(),
            Dsl.firstFilledBoolean(model.flatten(ROWS_APPROVED_STAR)).expression()),
        computation("Code", model.custom(FIRST_CODE).field(),
            Dsl.firstFilledCustom(model.flatten(ROWS_CODE_STAR)).expression()),
        computation("Month", model.dateFragment(FIRST_MONTH).field(),
            Dsl.firstFilledDateFragment(model.flatten(ROWS_MONTH_STAR)).expression()),
    )
    private val modelJson = computations.fold(base, TypedRules::inject)
    private val definition = ModelLoader.load(modelJson)
    private val generated = RuntimeLaws.kernelJavaEngine(modelJson)
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
    fun allEmptySelectionsClearEveryAdditionalKind() {
        agree(empty = true, expected = mapOf(
            FIRST_APPROVED to "CLEARED",
            FIRST_CODE to "CLEARED",
            FIRST_MONTH to "CLEARED",
        ))
    }

    @Test
    fun filledSelectionsPreserveEveryAdditionalKind() {
        agree(empty = false, expected = mapOf(
            FIRST_APPROVED to "VALUE|true",
            FIRST_CODE to "VALUE|A7",
            FIRST_MONTH to "VALUE|06",
        ))
    }

    private fun agree(empty: Boolean, expected: Map<String, String>) {
        val kernelDocument = RuntimeLaws.doc().group(ROOT, 1).group(ROWS, 1, 1)
            .field(FIRST_APPROVED, "false", 1, 1)
            .field(FIRST_CODE, "SEED", 1, 1)
            .field(FIRST_MONTH, "01", 1, 1)
        val interpreterDocument = definition.newDocument().group(ROOT, 1).group(ROWS, 1, 1)
            .field(FIRST_APPROVED, "false", 1, 1)
            .field(FIRST_CODE, "SEED", 1, 1)
            .field(FIRST_MONTH, "01", 1, 1)
        if (!empty) {
            kernelDocument.field(ROWS_APPROVED, "true", 1, 1, 1)
                .field(ROWS_CODE, "A7", 1, 1, 1)
                .field(ROWS_MONTH, "06", 1, 1, 1)
            interpreterDocument.field(ROWS_APPROVED, "true", 1, 1, 1)
                .field(ROWS_CODE, "A7", 1, 1, 1)
                .field(ROWS_MONTH, "06", 1, 1, 1)
        }

        val dynamic = signatures(RuntimeLaws.compute(modelJson, kernelDocument, kernelTypes))
        val java = signatures(generated.compute(generated.instance(kernelDocument), kernelTypes))
        val interpreted = DmInterpreter(definition, customFieldTypes = customTypes).compute(interpreterDocument)
            .filter { it.path in expected }
            .associate { result -> result.path to when (result.outcome) {
                ComputeOutcome.CLEARED -> "CLEARED"
                ComputeOutcome.ERRORED -> "ERRORED"
                ComputeOutcome.VALUE -> "VALUE|${result.value.orEmpty()}"
            } }

        assertEquals(expected, dynamic, "normative dynamic-Groovy")
        assertEquals(dynamic, java, "generated Java")
        assertEquals(dynamic, interpreted, "kernel-free interpreter")
    }

    private fun signatures(results: List<RuntimeLaws.Computed>): Map<String, String> = results
        .filter { it.path() in setOf(FIRST_APPROVED, FIRST_CODE, FIRST_MONTH) }
        .associate { result -> result.path() to when {
            result.cleared() -> "CLEARED"
            result.errored() -> "ERRORED"
            else -> "VALUE|${result.value().orElse("")}"
        } }

    private fun computation(name: String, target: io.github.mbackschat.a12.dm.rulekit.dsl.FieldRef,
                            operation: io.github.mbackschat.a12.dm.rulekit.dsl.Calculation): Computation =
        model.computation(target).name("ProjectFirst$name")
            .calc(operation)
            .message(Dsl.en("Project the first filled $name."))
            .build()

    private companion object {
        const val CUSTOM_TYPE = "ReviewCode"
        const val ROOT = "/Review"
        const val ROWS = "/Review/Rows"
        const val FIRST_APPROVED = "/Review/FirstApproved"
        const val FIRST_CODE = "/Review/FirstCode"
        const val FIRST_MONTH = "/Review/FirstMonth"
        const val ROWS_APPROVED = "/Review/Rows/Approved"
        const val ROWS_CODE = "/Review/Rows/Code"
        const val ROWS_MONTH = "/Review/Rows/Month"
        const val ROWS_APPROVED_STAR = "/Review/Rows*/Approved"
        const val ROWS_CODE_STAR = "/Review/Rows*/Code"
        const val ROWS_MONTH_STAR = "/Review/Rows*/Month"
    }
}
