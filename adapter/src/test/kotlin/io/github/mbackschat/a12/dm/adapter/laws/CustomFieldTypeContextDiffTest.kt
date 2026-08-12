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
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft.GroupBuilder
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

    /**
     * **The cardinality axis: one value, two cells.** Everything else here measures ONE cell, so it cannot tell a
     * per-cell contract from a per-value one — and the interpreter's cache key rests on exactly that distinction
     * ([IF284]: a field with a code-based `CustomFieldType` is keyed `(path, reps, raw)` while every other field
     * collapses to `(path, raw)`, and the per-cell contract is the only reason the cheaper key is not used
     * everywhere). It was locked interpreter-against-interpreter and had no kernel oracle at all (IG125), while
     * three doc sites stated it in per-VALUE terms — the opposite reading.
     *
     * Two in-cap rows of the same repeatable group carry the SAME value, so the two keys disagree by construction:
     * a per-value contract invokes once, a per-cell contract twice. One axis changed from
     * [declaredBoundsAndLocaleReachOneValidatorInvocationPerCell]'s proven single-cell fixture, so the count is
     * the only thing that can move.
     */
    @Test
    fun theSameValueInTwoCellsReachesTheValidatorOncePerCell() {
        val base = repeatingModel()
        val condition = DslRenderer().render(BoundModel.ofJson(base).custom(LINE_CODE).eq("bad"))
        val model = RuntimeLaws.withRule(base, "/Order/Lines", "ReadsCode", "../Code", "READS_CODE", condition)
        val doc = RuntimeLaws.doc()
            .group("/Order", 1)
            .group(LINES, 1, 1).field(LINE_CODE, "bad", 1, 1, 1)
            .group(LINES, 1, 2).field(LINE_CODE, "bad", 1, 2, 1)
        val context = Seen(Locale.US, 3, 5, false)

        val dynamicFactory = CapturingFactory()
        val dynamicCodes = RuntimeLaws.messages(model, doc, dynamicFactory, Locale.US).map { it.code() }
        // The positive control: without a rejection per cell the count below could be measuring nothing.
        assertEquals(listOf(REJECTION, REJECTION), dynamicCodes, "Groovy-dynamic formal result, one per cell")
        assertEquals(listOf(context, context), dynamicFactory.seen, "Groovy-dynamic invocation count for one value")

        val staticFactory = CapturingFactory()
        val staticEngine = RuntimeLaws.kernelJavaEngine(model)
        val staticCodes = staticEngine.messages(staticEngine.instance(doc), staticFactory, Locale.US).map { it.code() }
        assertEquals(dynamicCodes, staticCodes, "static-Java formal result")
        assertEquals(dynamicFactory.seen, staticFactory.seen, "static-Java invocation count for one value")

        val interpreterModel = ModelLoader.load(model)
        val interpreterSeen = mutableListOf<CustomFieldValidationContext>()
        val customTypes = mapOf("ProjectCode" to CustomFieldType { _, seen ->
            interpreterSeen += seen
            CustomTypeRejection(REJECTION)
        })
        val interpreterDoc = interpreterModel.newDocument()
            .group("/Order", 1)
            .group(LINES, 1, 1).field(LINE_CODE, "bad", 1, 1, 1)
            .group(LINES, 1, 2).field(LINE_CODE, "bad", 1, 2, 1)
        val interpreterCodes = DmInterpreter(interpreterModel, customFieldTypes = customTypes)
            .validate(interpreterDoc, "en_US").messages.map { it.code }
        assertEquals(dynamicCodes, interpreterCodes, "interpreter formal result")
        assertEquals(
            dynamicFactory.seen.size,
            interpreterSeen.size,
            "the interpreter's cache key must invoke the validator as often as the kernel does for one value in " +
                "two cells — a smaller count means the cell-shaped key in IF284 is wrong and collapses to (path, raw)",
        )
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

    /** The single-cell fixture's group made repeatable, so one value can occupy two cells. */
    private fun repeatingModel(): String =
        DocumentModelDraft.named("projectcodes")
            .locale("en_US")
            .rootGroup("Order", Consumer { order ->
                order.group("Lines", Consumer<GroupBuilder> { lines ->
                    lines.repeatable(2).customField("Code", Consumer<CustomFieldBuilder> { field ->
                        field.type("ProjectCode").minLength(3).maxLength(5)
                    })
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
        const val LINES = "/Order/Lines"
        const val LINE_CODE = "/Order/Lines/Code"
        const val REJECTION = "PROJECT_CODE_INVALID"
    }
}
