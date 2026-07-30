package io.github.mbackschat.a12.dm.interpreter

import io.github.mbackschat.a12.dm.interpreter.eval.FORMAL_RULE_PATH
import io.github.mbackschat.a12.dm.interpreter.eval.MANDATORY_FIELD
import io.github.mbackschat.a12.dm.interpreter.eval.newDocument
import io.github.mbackschat.a12.dm.interpreter.model.FieldDef
import io.github.mbackschat.a12.dm.interpreter.model.Kind
import io.github.mbackschat.a12.dm.interpreter.model.ModelDef
import io.github.mbackschat.a12.dm.interpreter.model.Requiredness
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * EXP-2026-07-30-01 / IF236 — field-owned templates are two distinct channels, neither of which uses the
 * rule-message dollar decoder. These common tests are the maintained JVM+Node half of the kernel differentials.
 */
class FieldOwnedMessageTest {

    @Test
    fun patternTemplateInsertsTheStoredValueOnce() {
        val field = FieldDef(
            CODE,
            Kind.STRING,
            pattern = "[A-Z]+",
            patternMessages = mapOf("en_US" to "Value [\$field.value\$]"),
        )
        val model = ModelDef(listOf(field), emptyList())
        val doc = model.newDocument().group(ORDER, 1).field(CODE, "\$field\$", 1, 1)

        val outcome = DmInterpreter(model).validate(doc)

        assertEquals(emptyList(), outcome.unsupported)
        assertEquals(
            listOf("stringFalschesMuster@$FORMAL_RULE_PATH@Value [\$field\$]"),
            outcome.messages.map { "${it.code}@${it.rulePath}@${it.text}" },
        )
    }

    @Test
    fun requirednessTemplatePreservesLiteralDollarPair() {
        val field = FieldDef(
            CODE,
            Kind.STRING,
            required = Requiredness.ABSOLUTE,
            requiredMessages = mapOf("en_US" to "Cost \$\$ \$field\$"),
        )
        val model = ModelDef(listOf(field), emptyList())
        val doc = model.newDocument().group(ORDER, 1).emptyField(CODE, 1, 1)

        val outcome = DmInterpreter(model).validate(doc)

        assertEquals(emptyList(), outcome.unsupported)
        assertEquals(
            listOf("$MANDATORY_FIELD@Cost \$\$ Code"),
            outcome.messages.map { "${it.code}@${it.text}" },
        )
    }

    @Test
    fun unmeasuredProducerFormsAndLocalesAreReportedUnsupported() {
        val patternField = FieldDef(
            CODE,
            Kind.STRING,
            pattern = "[A-Z]+",
            patternMessages = mapOf("de_DE" to "Wert \$field\$"),
        )
        val patternModel = ModelDef(listOf(patternField), emptyList())
        val invalid = patternModel.newDocument().group(ORDER, 1).field(CODE, "bad", 1, 1)

        val localeOutcome = DmInterpreter(patternModel).validate(invalid, "de_DE")
        assertEquals(emptyList(), localeOutcome.messages)
        assertTrue(localeOutcome.unsupported.single().reason.contains("de_DE"))
        assertFailsWith<IllegalStateException> {
            DmInterpreter(patternModel).validateFull(invalid, "de_DE")
        }

        val requiredField = FieldDef(
            CODE,
            Kind.STRING,
            required = Requiredness.ABSOLUTE,
            requiredMessages = mapOf("en_US" to "Value \$field.value\$"),
        )
        val requiredModel = ModelDef(listOf(requiredField), emptyList())
        val empty = requiredModel.newDocument().group(ORDER, 1).emptyField(CODE, 1, 1)
        val producerOutcome = DmInterpreter(requiredModel).validate(empty)
        assertEquals(emptyList(), producerOutcome.messages)
        assertTrue(producerOutcome.unsupported.single().reason.contains("\$field.value\$"))
    }

    private companion object {
        const val ORDER = "/Order"
        const val CODE = "/Order/Code"
    }
}
