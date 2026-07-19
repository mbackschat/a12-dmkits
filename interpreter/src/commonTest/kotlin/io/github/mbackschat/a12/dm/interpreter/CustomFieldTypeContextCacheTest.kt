package io.github.mbackschat.a12.dm.interpreter

import io.github.mbackschat.a12.dm.interpreter.eval.newDocument
import io.github.mbackschat.a12.dm.interpreter.model.ComputationDef
import io.github.mbackschat.a12.dm.interpreter.model.CustomFieldTypeDeclaration
import io.github.mbackschat.a12.dm.interpreter.model.CustomFieldType
import io.github.mbackschat.a12.dm.interpreter.model.CustomFieldValidationContext
import io.github.mbackschat.a12.dm.interpreter.model.CustomTypeRejection
import io.github.mbackschat.a12.dm.interpreter.model.FieldDef
import io.github.mbackschat.a12.dm.interpreter.model.Kind
import io.github.mbackschat.a12.dm.interpreter.model.ModelDef
import io.github.mbackschat.a12.dm.interpreter.model.RelevantEntity
import io.github.mbackschat.a12.dm.interpreter.model.RuleDef
import io.github.mbackschat.a12.dm.interpreter.model.Verdict
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/**
 * The custom-field validator receives the complete kernel-shaped per-call context, and one validation call
 * invokes it at most once per cell. The cached result is shared by formal-message emission, rule suppression,
 * and computation operand reads; partial validation checks relevance before it considers invoking a validator.
 */
class CustomFieldTypeContextCacheTest {

    private val declaration = CustomFieldTypeDeclaration("ProjectCode", minLength = 3, maxLength = 5)
    private val fields = listOf(
        FieldDef(CODE, Kind.STRING, labels = mapOf("de_DE" to "Projektcode"), customType = declaration),
        FieldDef(FLAG, Kind.STRING),
        FieldDef(COPY, Kind.STRING),
    )
    private val rule = RuleDef("/Order/CodeRule", "/Order", "[Code] == \"bad\"", "BAD_CODE", "../Flag")

    @Test
    fun validationPassesContextCachesTheFullRejectionAndRendersItsFieldPlaceholder() {
        val contexts = mutableListOf<CustomFieldValidationContext>()
        val validator = CustomFieldType { _, context ->
            contexts += context
            CustomTypeRejection("PROJECT_CODE_INVALID", "Invalid \$<fieldName>\$ / \$<fieldName>\$")
        }
        val model = ModelDef(fields, listOf(rule))
        val doc = model.newDocument().group("/Order", 1).field(CODE, "bad", 1, 1)

        val outcome = DmInterpreter(model, customFieldTypes = mapOf("ProjectCode" to validator))
            .validate(doc, "de_DE")

        assertEquals(
            listOf(CustomFieldValidationContext("de_DE", 3, 5, isDisplayValue = false)),
            contexts,
            "formal emission and suppression must share one complete cached rejection",
        )
        val message = outcome.messages.single { it.code == "PROJECT_CODE_INVALID" }
        assertEquals("Invalid Projektcode / Projektcode", message.text)
        assertFalse(outcome.messages.any { it.code == "BAD_CODE" }, "the rejected operand suppresses its rule")
    }

    @Test
    fun singleRuleSuppressionCarriesTheValidatorProjectCode() {
        val model = ModelDef(fields, listOf(rule))
        val validator = CustomFieldType { _, _ -> CustomTypeRejection("PROJECT_CODE_INVALID") }
        val doc = model.newDocument().group("/Order", 1).field(CODE, "bad", 1, 1)

        val result = DmInterpreter(model, customFieldTypes = mapOf("ProjectCode" to validator))
            .testRule(rule.path, doc)

        assertEquals(Verdict.SUPPRESSED, result.verdict)
        assertEquals("PROJECT_CODE_INVALID", result.suppressedBy.single().formalErrorCode)
    }

    @Test
    fun aNonRelevantCustomCellDoesNotInvokeItsValidator() {
        var invocations = 0
        val model = ModelDef(fields, listOf(rule))
        val validator = CustomFieldType { _, _ ->
            invocations++
            error("a non-relevant cell must never be validated")
        }
        val doc = model.newDocument().group("/Order", 1).field(CODE, "bad", 1, 1)

        DmInterpreter(model, customFieldTypes = mapOf("ProjectCode" to validator))
            .validatePart(doc, setOf(RelevantEntity.anyRep(FLAG)))

        assertEquals(0, invocations)
    }

    @Test
    fun computationPassesLocaleAndCachesRepeatedReadsOfAnAcceptedOperand() {
        val contexts = mutableListOf<CustomFieldValidationContext>()
        val validator = CustomFieldType { _, context ->
            contexts += context
            null
        }
        val model = ModelDef(fields, emptyList(), computations = listOf(ComputationDef(COPY, "/Order", "[Code] + [Code]")))
        val doc = model.newDocument().group("/Order", 1).field(CODE, "AB", 1, 1)

        val interpreter = DmInterpreter(model, customFieldTypes = mapOf("ProjectCode" to validator))
        val computed = interpreter.compute(doc, "de_DE")

        assertEquals("ABAB", computed.single().value)
        val expected = listOf(CustomFieldValidationContext("de_DE", 3, 5, isDisplayValue = false))
        assertEquals(expected, contexts, "two reads of one cell in one compute call must share the formal result")

        contexts.clear()
        assertEquals("ABAB", interpreter.computeField(COPY, doc, "de_DE").single().value)
        assertEquals(expected, contexts, "computeField must thread locale and keep its cache call-local")

        contexts.clear()
        val applied = interpreter.applyComputations(doc, "de_DE")
        assertEquals("ABAB", applied.onceContextFor("/Order").single().displayValue(COPY))
        assertEquals(expected, contexts, "applyComputations must thread locale and keep its cache call-local")
    }

    private companion object {
        const val CODE = "/Order/Code"
        const val FLAG = "/Order/Flag"
        const val COPY = "/Order/Copy"
    }
}
