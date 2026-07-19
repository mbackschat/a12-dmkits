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

/** Consumer-owned custom-validator messages preserve plain text and replace every literal `$<fieldName>$`. */
class CustomFieldTypeMessageDiffTest {

    private val model = DocumentModelDraft.named("projectcodes")
        .locale("en_US")
        .locale("de_DE")
        .rootGroup("Order", Consumer { group ->
            group.customField("Code", Consumer<CustomFieldBuilder> { field ->
                field.type("ProjectCode")
                    .label("en_US", "Project code")
                    .label("de_DE", "Projektcode")
            })
        })
        .build()
        .toJson()

    @Test
    fun plainConsumerMessageIsUnchanged() {
        assertMessage(Locale.US, "Project code rejected", "Project code rejected")
    }

    @Test
    fun everyFieldNamePlaceholderUsesTheLocalizedFieldLabel() {
        assertMessage(
            Locale.US,
            "Invalid \$<fieldName>\$ / \$<fieldName>\$",
            "Invalid Project code / Project code",
        )
        assertMessage(
            Locale.GERMANY,
            "Ungültig: \$<fieldName>\$ / \$<fieldName>\$",
            "Ungültig: Projektcode / Projektcode",
        )
    }

    private fun assertMessage(locale: Locale, template: String, expected: String) {
        val factory = rejectingFactory(template)
        val doc = RuntimeLaws.doc().group("/Order", 1).field(CODE, "bad", 1, 1)
        val dynamic = RuntimeLaws.customFieldErrorTextOf(model, REJECTION, doc, factory, locale).orElseThrow()

        val staticEngine = RuntimeLaws.kernelJavaEngine(model)
        val javaStatic = staticEngine.customFieldErrorTextOf(
            staticEngine.instance(doc),
            REJECTION,
            rejectingFactory(template),
            locale,
        ).orElseThrow()

        val interpreterModel = ModelLoader.load(model)
        val customTypes = mapOf("ProjectCode" to CustomFieldType { _, _ ->
            CustomTypeRejection(REJECTION, template)
        })
        val interpreterDoc = interpreterModel.newDocument().group("/Order", 1).field(CODE, "bad", 1, 1)
        val interpreter = DmInterpreter(interpreterModel, customFieldTypes = customTypes)
            .validate(interpreterDoc, locale.toLanguageTag().replace('-', '_'))
            .messages.single { it.code == REJECTION }.text

        assertEquals(expected, dynamic, "Groovy-dynamic sanity")
        assertEquals(dynamic, javaStatic, "static-Java message")
        assertEquals(dynamic, interpreter, "interpreter message")
    }

    private fun rejectingFactory(message: String) = object : ICustomFieldTypeFactory {
        override fun createCustomFieldValidator(name: String): Optional<ICustomFieldValidator> =
            if (name != "ProjectCode") Optional.empty()
            else Optional.of(object : ICustomFieldValidator {
                override fun validate(
                    value: String,
                    param: ICustomFieldTypeValidationParam,
                    isDisplay: Boolean,
                ): Optional<ICustomFieldTypeCheckError> =
                    Optional.of(object : ICustomFieldTypeCheckError {
                        override fun getErrorMessage() = message
                        override fun getErrorKey() = REJECTION
                    })
            })
    }

    private companion object {
        const val CODE = "/Order/Code"
        const val REJECTION = "PROJECT_CODE_INVALID"
    }
}
