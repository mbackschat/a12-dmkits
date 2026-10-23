package io.github.mbackschat.a12.dm.interpreter

import io.github.mbackschat.a12.dm.interpreter.eval.Document
import io.github.mbackschat.a12.dm.interpreter.eval.newDocument
import io.github.mbackschat.a12.dm.interpreter.model.FieldDef
import io.github.mbackschat.a12.dm.interpreter.model.Kind
import io.github.mbackschat.a12.dm.interpreter.model.MessageLabelProvider
import io.github.mbackschat.a12.dm.interpreter.model.ModelDef
import io.github.mbackschat.a12.dm.interpreter.model.RuleDef
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Exact validation-message display policy on both KMP targets. Evaluation consumes the normalized cell value,
 * while interpolation consumes the original display value; name and empty-value fallback retain the distinctions
 * required by the kernel instead of collapsing them into a single string-or-kind shortcut.
 */
class MessageDisplayPolicyTest {

    @Test
    fun presentEmptyProviderResultWinsOverModelLabel() {
        val model = model(Kind.STRING, "FieldFilled(Value)", "[\$Value\$]", label = "Model label")
        val doc = model.newDocument().field(VALUE, "x", 1, 1)

        assertEquals(
            "[]",
            message(model, doc, MessageLabelProvider { _, _ -> "" }),
            "a configured provider's empty result is a result, not an absent value",
        )
    }

    @Test
    fun absentProviderResultFallsThroughModelLabelThenDebugIdentity() {
        val labeled = model(Kind.STRING, "FieldFilled(Value)", "\$Value\$", label = "Model label")
        val labeledDoc = labeled.newDocument().field(VALUE, "x", 1, 1)
        assertEquals("Model label", message(labeled, labeledDoc, MessageLabelProvider { _, _ -> null }))

        val unlabeled = model(Kind.STRING, "FieldFilled(Value)", "\$Value\$")
        val unlabeledDoc = unlabeled.newDocument().field(VALUE, "x", 1, 1)
        assertEquals("/Order/Value/1.1", message(unlabeled, unlabeledDoc, null))
    }

    @Test
    fun missingAndExplicitEmptyScaledNumbersUseExactFormatDefault() {
        val model = model(Kind.NUMBER, "FieldNotFilled(Value)", "[\$Value.value\$]", scale = 2)
        val missing = model.newDocument().field(ANCHOR, "x", 1, 1)
        val explicitEmpty = model.newDocument().field(ANCHOR, "x", 1, 1).field(VALUE, "", 1, 1)

        assertEquals("[0.00]", message(model, missing))
        assertEquals("[0.00]", message(model, explicitEmpty))
    }

    @Test
    fun scaleZeroAndStringDefaultsRemainZeroAndEmpty() {
        val number = model(Kind.NUMBER, "FieldNotFilled(Value)", "[\$Value.value\$]", scale = 0)
        val string = model(Kind.STRING, "FieldNotFilled(Value)", "[\$Value.value\$]")

        assertEquals("[0]", message(number, number.newDocument().field(ANCHOR, "x", 1, 1)))
        assertEquals("[]", message(string, string.newDocument().field(ANCHOR, "x", 1, 1)))
    }

    @Test
    fun evaluationReadsNormalizedCrlfWhileMessageReadsOriginalDisplayValue() {
        val model = model(Kind.STRING, "Length(Value) == 5", "\$Value.value\$")
        val doc = model.newDocument().field(VALUE, "AB\r\nCD", 1, 1)

        assertEquals("AB\r\nCD", message(model, doc))
    }

    @Test
    fun replacementBytesAreNotParsedAsMorePlaceholders() {
        val model = model(Kind.STRING, "FieldFilled(Value)", "\$Value.value\$")
        val doc = model.newDocument().field(VALUE, "\$Value\$", 1, 1)

        assertEquals("\$Value\$", message(model, doc))
    }

    @Test
    fun literalDollarDecodesWithoutReparsingTheReplacement() {
        val model = model(Kind.STRING, "FieldFilled(Value)", "Cost \$\$ [\$Value.value\$]")
        val doc = model.newDocument().field(VALUE, "\$Value\$", 1, 1)

        assertEquals("Cost \$ [\$Value\$]", message(model, doc))
    }

    private fun model(
        kind: Kind,
        condition: String,
        template: String,
        label: String? = null,
        scale: Int? = null,
    ) = ModelDef(
        fields = listOf(
            FieldDef(ANCHOR, Kind.STRING),
            FieldDef(VALUE, kind, labels = label?.let { mapOf("en_US" to it) }.orEmpty(), scale = scale,
                minFractionalDigits = scale, lineBreaksPermitted = true),
        ),
        rules = listOf(RuleDef(RULE, "/Order", condition, CODE, "../Value",
            messages = mapOf("en_US" to template))),
    )

    private fun message(
        model: ModelDef,
        doc: Document,
        provider: MessageLabelProvider? = MessageLabelProvider.MODEL_LABEL_OR_NAME,
    ): String = DmInterpreter(model, messageLabelProvider = provider)
        .validateFull(doc, "en_US")
        .single { it.code == CODE }
        .text

    private companion object {
        const val ANCHOR = "/Order/Anchor"
        const val VALUE = "/Order/Value"
        const val RULE = "/Order/DisplayPolicy"
        const val CODE = "DISPLAY_POLICY"
    }
}
