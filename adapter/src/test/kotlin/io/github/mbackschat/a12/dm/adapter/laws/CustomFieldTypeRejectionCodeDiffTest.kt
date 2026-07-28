package io.github.mbackschat.a12.dm.adapter.laws

import com.fasterxml.jackson.databind.ObjectMapper
import com.mgmtp.a12.kernel.core.customfieldtype.ICustomFieldTypeCheckError
import com.mgmtp.a12.kernel.core.customfieldtype.ICustomFieldTypeFactory
import com.mgmtp.a12.kernel.core.customfieldtype.ICustomFieldTypeValidationParam
import com.mgmtp.a12.kernel.core.customfieldtype.ICustomFieldValidator
import io.github.mbackschat.a12.dm.interpreter.CustomFieldTypeRegistry
import io.github.mbackschat.a12.dm.interpreter.CustomFieldTypeFactory
import io.github.mbackschat.a12.dm.interpreter.CustomFieldValidationResult
import io.github.mbackschat.a12.dm.interpreter.DmInterpreter
import io.github.mbackschat.a12.dm.interpreter.InterpreterConfiguration
import io.github.mbackschat.a12.dm.interpreter.InterpreterIntegrationError
import io.github.mbackschat.a12.dm.interpreter.InterpreterIntegrationPhase
import io.github.mbackschat.a12.dm.interpreter.InterpreterIntegrationReason
import io.github.mbackschat.a12.dm.interpreter.PreparedModel
import io.github.mbackschat.a12.dm.interpreter.eval.newDocument
import io.github.mbackschat.a12.dm.interpreter.model.CustomFieldType
import io.github.mbackschat.a12.dm.interpreter.model.CustomFieldTypeDeclaration
import io.github.mbackschat.a12.dm.interpreter.model.CustomTypeRejection
import io.github.mbackschat.a12.dm.interpreter.model.FieldDef
import io.github.mbackschat.a12.dm.interpreter.model.Kind
import io.github.mbackschat.a12.dm.interpreter.model.ModelDef
import io.github.mbackschat.a12.dm.modelkit.CustomFieldBuilder
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.util.Optional
import java.util.function.Consumer

/**
 * Differential — a rejected custom-field-type cell carries the validator's **project-specific error code** as its
 * formal error code, bit-for-bit with the kernel (IF150). The kernel's `ICustomFieldValidator`
 * returns an `ICustomFieldTypeCheckError` whose `getErrorKey()` becomes the cell's `formalErrorCode`; the
 * interpreter's [CustomFieldType] SPI now returns a [CustomTypeRejection] carrying the same key (before IF150 the
 * SPI was `Boolean` and the interpreter always emitted the constant `customFieldTypeInvalid`, a machine-observable
 * mismatch — see the corrected note in `CustomFieldTypeBoundsDiffTest`).
 *
 * This tri-locks (interp == Groovy-dynamic == static-Java codegen) the per-cell formal CODE for an ACCEPTED value
 * (no formal error) and MULTIPLE distinct project keys (`CODE_TOO_SHORT` / `CODE_TOO_LONG`). Own-domain e-commerce
 * fixture authored through modelkit; the kernel validator is registered via the per-call
 * `DocumentProcessingConfig.customFieldTypeFactory`.
 */
class CustomFieldTypeRejectionCodeDiffTest {

    private val json = DocumentModelDraft.named("form").locale("en_US")
        .rootGroup("Order", Consumer { g ->
            g.customField("Code", Consumer<CustomFieldBuilder> { it.type("Code").minLength(3).maxLength(5) })
        })
        .build().toJson()
    private val def = ModelDef(listOf(
        FieldDef(CODE, Kind.STRING, customType = CustomFieldTypeDeclaration("Code", 3, 5)),
    ), emptyList())

    /** The shared reject rule: too short → CODE_TOO_SHORT, too long → CODE_TOO_LONG, else accepted. */
    private fun rejectionKey(value: String): String? = when {
        value.length < 3 -> "CODE_TOO_SHORT"
        value.length > 5 -> "CODE_TOO_LONG"
        else -> null
    }

    private val codeTypes = mapOf("Code" to CustomFieldType { value, _ ->
        rejectionKey(value)?.let { CustomTypeRejection(it) }
    })
    private val publicPrepared by lazy { PreparedModel.loadExpandedJson(json) }
    private val publicInterpreter by lazy {
        val fieldTypes = CustomFieldTypeRegistry.builder()
            .register("Code") { value, _ ->
                rejectionKey(value)?.let {
                    CustomFieldValidationResult.Rejected(it, "bad code")
                } ?: CustomFieldValidationResult.Accepted
            }
            .build()
        publicPrepared.createInterpreter(
            InterpreterConfiguration.builder()
                .customFieldTypes(fieldTypes)
                .build(),
        )
    }

    private val factory = object : ICustomFieldTypeFactory {
        override fun createCustomFieldValidator(name: String): Optional<ICustomFieldValidator> =
            if (name != "Code") Optional.empty() else Optional.of(object : ICustomFieldValidator {
                override fun validate(value: String, p: ICustomFieldTypeValidationParam, isDisplay: Boolean): Optional<ICustomFieldTypeCheckError> =
                    rejectionKey(value)?.let { key ->
                        Optional.of(object : ICustomFieldTypeCheckError {
                            override fun getErrorMessage() = "bad code"
                            override fun getErrorKey() = key
                        })
                    } ?: Optional.empty()
            })
    }

    /** The formal error CODE on the Code cell (or null when the cell is clean) for each engine. */
    private fun codeAgrees(value: String, expected: String?) {
        val kDoc = RuntimeLaws.doc().group("/Order", 1).field(CODE, value, 1, 1)
        val iDoc = def.newDocument().group("/Order", 1).field(CODE, value, 1, 1)
        val groovy = RuntimeLaws.messages(json, kDoc, factory).filter { it.field().contains("Code") }.map { it.code() }
        val engine = RuntimeLaws.kernelJavaEngine(json)
        val javaStatic = engine.messages(engine.instance(kDoc), factory).filter { it.field().contains("Code") }.map { it.code() }
        val interp = DmInterpreter(def, customFieldTypes = codeTypes).validate(iDoc).messages
            .filter { it.errorPath.contains("Code") }.map { it.code }
        val publicSnapshot = publicPrepared.documents.readDocument(orderJson(value))
        val public = publicInterpreter.validateFull(publicSnapshot).findings
            .filter { it.errorPath.contains("Code") }.map { it.code }
        val exp = listOfNotNull(expected)
        assertEquals(false, publicPrepared.createInterpreter().supportReport().fullySupported)
        assertEquals(true, publicInterpreter.supportReport().fullySupported)
        assertEquals(exp, groovy, "kernel groovy-dynamic code for \"$value\"")
        assertEquals(exp, javaStatic, "kernel static-Java code for \"$value\"")
        assertEquals(exp, interp, "interpreter code for \"$value\"")
        assertEquals(exp, public, "public interpreter code for \"$value\"")
    }

    @Test fun acceptedValueHasNoFormalError() = codeAgrees("abcd", null)
    @Test fun tooShortCarriesItsProjectKey() = codeAgrees("ab", "CODE_TOO_SHORT")
    @Test fun tooLongCarriesItsProjectKey() = codeAgrees("abcdef", "CODE_TOO_LONG")

    @Test
    fun publicValidatorFailureIsNormalizedAtTheEvaluationBoundary() {
        val failure = IllegalStateException("validator failed")
        val fieldTypes = CustomFieldTypeRegistry.builder()
            .register("Code") { _, _ -> throw failure }
            .build()
        val interpreter = publicPrepared.createInterpreter(
            InterpreterConfiguration.builder().customFieldTypes(fieldTypes).build(),
        )

        val error = assertThrows(InterpreterIntegrationError::class.java) {
            interpreter.validateFull(publicSnapshot("abcd"))
        }

        assertEquals(InterpreterIntegrationPhase.EVALUATE, error.phase)
        assertEquals(InterpreterIntegrationReason.CALLBACK_THREW, error.reason)
        assertSame(failure, error.cause)
    }

    @Test
    fun publicFactoryFailureIsNormalizedWhileTheInterpreterBinds() {
        val failure = IllegalStateException("factory failed")
        val fieldTypes = CustomFieldTypeRegistry.fromFactory(
            CustomFieldTypeFactory { throw failure },
        )

        val error = assertThrows(InterpreterIntegrationError::class.java) {
            publicPrepared.createInterpreter(
                InterpreterConfiguration.builder().customFieldTypes(fieldTypes).build(),
            )
        }

        assertEquals(InterpreterIntegrationPhase.BIND, error.phase)
        assertEquals(InterpreterIntegrationReason.FACTORY_THREW, error.reason)
        assertSame(failure, error.cause)
    }

    private fun publicSnapshot(value: String) =
        publicPrepared.documents.readDocument(orderJson(value))

    /** The A12 Document JSON of one `/Order` row carrying [value] in its `Code` cell. */
    private fun orderJson(value: String): String =
        ObjectMapper().writeValueAsString(mapOf("Order" to mapOf("Code" to value)))

    private companion object {
        const val CODE = "/Order/Code"
    }
}
