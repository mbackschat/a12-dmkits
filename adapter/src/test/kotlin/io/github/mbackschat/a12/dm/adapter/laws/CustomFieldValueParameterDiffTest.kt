package io.github.mbackschat.a12.dm.adapter.laws

import com.mgmtp.a12.kernel.core.customfieldtype.ICustomFieldTypeCheckError
import com.mgmtp.a12.kernel.core.customfieldtype.ICustomFieldTypeFactory
import com.mgmtp.a12.kernel.core.customfieldtype.ICustomFieldTypeValidationParam
import com.mgmtp.a12.kernel.core.customfieldtype.ICustomFieldValidator
import io.github.mbackschat.a12.dm.interpreter.DmInterpreter
import io.github.mbackschat.a12.dm.interpreter.eval.newDocument
import io.github.mbackschat.a12.dm.interpreter.load.ModelLoader
import io.github.mbackschat.a12.dm.interpreter.model.CustomFieldType
import io.github.mbackschat.a12.dm.interpreter.model.CustomTypeRejection
import io.github.mbackschat.a12.dm.modelkit.CustomFieldBuilder
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import java.util.Locale
import java.util.Optional
import java.util.function.Consumer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * A rule message that interpolates a custom-typed field's value, `$/Order/Code.value$` — the changelog's A12K-4331,
 * fixed in kernel 31.1.1 (and 30.8.6), where it "could lead to an exception during validation". Kernel 31.1.1's
 * value conversion receives the configured custom-field-type factory (KERNEL-31-UPGRADE-PROPOSAL, "The ext0 → ext1
 * delta"); these rows read what that conversion renders, on both kernel strategies and the interpreter.
 *
 * A12 admits a message parameter only for a field its rule's condition reads, so the condition is
 * `FieldFilled(Note) Or FieldFilled(Code)` with the error on `Note`, which every row fills. The three rows vary only
 * the stored code and are the within-model controls for one another; a row whose rule stays silent reads `(silent)`. Own domain (project
 * codes on an order).
 */
class CustomFieldValueParameterDiffTest {

    private companion object {
        const val ORDER = "/Order"
        const val CODE = "/Order/Code"
        const val NOTE = "/Order/Note"
        const val RULE = "CODE_ECHO"
        const val REJECTION = "PROJECT_CODE_INVALID"
        const val TYPE = "ProjectCode"
    }

    private val model: String = RuntimeLaws.withRuleMessages(
        DocumentModelDraft.named("projectcode-echo").locale("en_US").locale("de_DE")
            .rootGroup("Order", Consumer { group ->
                group.stringField("Note") { }
                    .customField("Code", Consumer<CustomFieldBuilder> { it.type(TYPE) })
            }).build().toJson(),
        ORDER, "CodeEcho", "../Note", RULE, "FieldFilled(Note) Or FieldFilled(Code)",
        "code=[\$/Order/Code.value\$]", "Code=[\$/Order/Code.value\$]",
    )

    /** Accepts every code but `bad`, which it rejects with [REJECTION]. */
    private val kernelFactory = object : ICustomFieldTypeFactory {
        override fun createCustomFieldValidator(name: String): Optional<ICustomFieldValidator> =
            if (name != TYPE) Optional.empty()
            else Optional.of(object : ICustomFieldValidator {
                override fun validate(value: String, param: ICustomFieldTypeValidationParam, isDisplay: Boolean):
                    Optional<ICustomFieldTypeCheckError> =
                    if (value != "bad") Optional.empty()
                    else Optional.of(object : ICustomFieldTypeCheckError {
                        override fun getErrorMessage() = "rejected"
                        override fun getErrorKey() = REJECTION
                    })
            })
    }

    private val interpreterTypes = mapOf(TYPE to CustomFieldType { value, _ ->
        if (value == "bad") CustomTypeRejection(REJECTION, "rejected") else null
    })

    @Test
    fun theModelIsAdmitted() = assertEquals(listOf<String>(), RuntimeLaws.consistencyMessages(model))

    @Test
    fun aCustomTypedValueRendersInARuleMessage() {
        val rows = linkedMapOf("an accepted code" to "AB-12", "a rejected code" to "bad", "no code" to null)
        assertEquals(
            linkedMapOf("an accepted code" to "code=[AB-12]", "a rejected code" to "code=[bad]", "no code" to "code=[]"),
            rows.mapValues { (_, code) -> agreedText(code) },
        )
    }

    /** The Groovy-dynamic text, asserted equal to generated Java's and the interpreter's. */
    private fun agreedText(code: String?): String? {
        val kDoc = RuntimeLaws.doc().group(ORDER, 1).field(NOTE, "n", 1, 1)
        code?.let { kDoc.field(CODE, it, 1, 1) }
        val dynamic = RuntimeLaws.customFieldErrorTextOf(model, RULE, kDoc, kernelFactory, Locale.US).orElse(null)
        val java = RuntimeLaws.kernelJavaEngine(model)
        val generated = java.customFieldErrorTextOf(java.instance(kDoc), RULE, kernelFactory, Locale.US).orElse(null)
        val definition = ModelLoader.load(model)
        val iDoc = definition.newDocument().group(ORDER, 1).field(NOTE, "n", 1, 1)
        code?.let { iDoc.field(CODE, it, 1, 1) }
        val interpreted = DmInterpreter(definition, customFieldTypes = interpreterTypes).validate(iDoc, "en_US")
            .messages.singleOrNull { it.code == RULE }?.text
        assertEquals(dynamic, generated, "generated Java  kernel=$dynamic java=$generated")
        assertEquals(dynamic, interpreted, "interpreter  kernel=$dynamic interp=$interpreted")
        return dynamic
    }
}
