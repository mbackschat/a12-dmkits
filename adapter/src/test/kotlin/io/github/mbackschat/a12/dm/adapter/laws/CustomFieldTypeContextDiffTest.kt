package io.github.mbackschat.a12.dm.adapter.laws

import com.mgmtp.a12.kernel.core.customfieldtype.ICustomFieldTypeCheckError
import com.mgmtp.a12.kernel.core.customfieldtype.ICustomFieldTypeFactory
import com.mgmtp.a12.kernel.core.customfieldtype.ICustomFieldTypeValidationParam
import com.mgmtp.a12.kernel.core.customfieldtype.ICustomFieldValidator
import io.github.mbackschat.a12.dm.interpreter.DmInterpreter
import io.github.mbackschat.a12.dm.interpreter.eval.newDocument
import io.github.mbackschat.a12.dm.interpreter.load.ModelLoader
import io.github.mbackschat.a12.dm.interpreter.model.CustomFieldType
import io.github.mbackschat.a12.dm.interpreter.model.CustomFieldValidationContext
import io.github.mbackschat.a12.dm.interpreter.model.CustomTypeRejection
import io.github.mbackschat.a12.dm.modelkit.CustomFieldBuilder
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel
import io.github.mbackschat.a12.dm.rulekit.dsl.DslRenderer
import java.util.Locale
import java.util.Optional
import java.util.function.Consumer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** The custom-validator locale/bounds/display inputs and one-call invocation count match both kernel strategies. */
class CustomFieldTypeContextDiffTest {

    @Test
    fun declaredBoundsAndLocaleReachOneValidatorInvocationPerCell() {
        assertContext(Locale.US, 3, 5)
        assertContext(Locale.GERMANY, 3, 5)
    }

    @Test
    fun omittedAndOneSidedBoundsUseKernelEffectiveDefaults() {
        assertContext(Locale.US, null, null, expectedMin = 1, expectedMax = 999)
        assertContext(Locale.US, 3, null, expectedMin = 3, expectedMax = 999)
        assertContext(Locale.US, null, 5, expectedMin = 1, expectedMax = 5)
    }

    private fun assertContext(
        locale: Locale,
        min: Int?,
        max: Int?,
        expectedMin: Int? = min,
        expectedMax: Int? = max,
    ) {
        val base = model(min, max)
        val condition = DslRenderer().render(BoundModel.ofJson(base).custom(CODE).eq("bad"))
        val model = RuntimeLaws.withRule(base, "/Order", "ReadsCode", "../Code", "READS_CODE", condition)
        val doc = RuntimeLaws.doc().group("/Order", 1).field(CODE, "bad", 1, 1)
        val expected = Seen(locale, expectedMin, expectedMax, false)

        val dynamicFactory = CapturingFactory()
        val dynamicCodes = RuntimeLaws.messages(model, doc, dynamicFactory, locale).map { it.code() }
        assertEquals(listOf(REJECTION), dynamicCodes, "Groovy-dynamic formal result")
        assertEquals(listOf(expected), dynamicFactory.seen, "Groovy-dynamic validator context and count")

        val staticFactory = CapturingFactory()
        val staticEngine = RuntimeLaws.kernelJavaEngine(model)
        val staticCodes = staticEngine.messages(staticEngine.instance(doc), staticFactory, locale).map { it.code() }
        assertEquals(dynamicCodes, staticCodes, "static-Java formal result")
        assertEquals(listOf(expected), staticFactory.seen, "static-Java validator context and count")

        val interpreterModel = ModelLoader.load(model)
        val interpreterSeen = mutableListOf<CustomFieldValidationContext>()
        val customTypes = mapOf("ProjectCode" to CustomFieldType { _, context ->
            interpreterSeen += context
            CustomTypeRejection(REJECTION)
        })
        val interpreterDoc = interpreterModel.newDocument().group("/Order", 1).field(CODE, "bad", 1, 1)
        val interpreterCodes = DmInterpreter(interpreterModel, customFieldTypes = customTypes)
            .validate(interpreterDoc, locale.toLanguageTag().replace('-', '_')).messages.map { it.code }
        assertEquals(dynamicCodes, interpreterCodes, "interpreter formal result")
        assertEquals(
            listOf(CustomFieldValidationContext(
                locale.toLanguageTag().replace('-', '_'),
                expectedMin,
                expectedMax,
                isDisplayValue = false,
            )),
            interpreterSeen,
            "interpreter validator context and count",
        )
    }

    private fun model(min: Int?, max: Int?): String =
        DocumentModelDraft.named("projectcodes")
            .locale("en_US")
            .locale("de_DE")
            .rootGroup("Order", Consumer { group ->
                group.customField("Code", Consumer<CustomFieldBuilder> { field ->
                    field.type("ProjectCode")
                    min?.let { field.minLength(it) }
                    max?.let { field.maxLength(it) }
                })
            })
            .build()
            .toJson()

    private data class Seen(val locale: Locale, val min: Int?, val max: Int?, val display: Boolean)

    private class CapturingFactory : ICustomFieldTypeFactory {
        val seen = mutableListOf<Seen>()

        override fun createCustomFieldValidator(name: String): Optional<ICustomFieldValidator> =
            if (name != "ProjectCode") Optional.empty()
            else Optional.of(object : ICustomFieldValidator {
                override fun validate(
                    value: String,
                    param: ICustomFieldTypeValidationParam,
                    isDisplay: Boolean,
                ): Optional<ICustomFieldTypeCheckError> {
                    seen += Seen(
                        param.errorMsgLocale,
                        param.minLength.orElse(null),
                        param.maxLength.orElse(null),
                        isDisplay,
                    )
                    return Optional.of(object : ICustomFieldTypeCheckError {
                        override fun getErrorMessage() = "rejected"
                        override fun getErrorKey() = REJECTION
                    })
                }
            })
    }

    private companion object {
        const val CODE = "/Order/Code"
        const val REJECTION = "PROJECT_CODE_INVALID"
    }
}
