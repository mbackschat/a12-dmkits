package io.github.mbackschat.a12.dm.interpreter

import io.github.mbackschat.a12.dm.interpreter.eval.FORMAL_RULE_PATH
import io.github.mbackschat.a12.dm.interpreter.eval.MANDATORY_FIELD
import io.github.mbackschat.a12.dm.interpreter.eval.newDocument
import io.github.mbackschat.a12.dm.interpreter.model.ConditionLanguage
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
    fun patternTokensFollowConditionLanguageAndKeepTerminalDollarPairs() {
        for (language in ConditionLanguage.entries) {
            val fieldToken = if (language == ConditionLanguage.EN) "field" else "Feld"
            val valueToken = if (language == ConditionLanguage.EN) "field.value" else "Feld.Wert"
            for ((template, expected) in mapOf(
                "$$fieldToken$: $$valueToken$" to "Code: bad",
                "Cost \$\$" to "Cost \$\$",
                "\$\$" to "\$\$",
            )) {
                for (locale in listOf("en_US", "de_DE")) {
                    val field = FieldDef(CODE, Kind.STRING, pattern = "[A-Z]+",
                        patternMessages = mapOf(locale to template))
                    val model = ModelDef(listOf(field), emptyList(), conditionLanguage = language)
                    val doc = model.newDocument().group(ORDER, 1).field(CODE, "bad", 1, 1)
                    val outcome = DmInterpreter(model).validate(doc, locale)
                    assertEquals(emptyList(), outcome.unsupported, "$language/$locale/$template")
                    assertEquals(listOf(expected), outcome.messages.map { it.text }, "$language/$locale/$template")
                }
            }
        }
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
    fun invalidPatternTokensAreReportedUnsupported() {
        val patternField = FieldDef(
            CODE,
            Kind.STRING,
            pattern = "[A-Z]+",
            patternMessages = mapOf("en_US" to "Value \$Field\$"),
        )
        val patternModel = ModelDef(listOf(patternField), emptyList())
        val invalid = patternModel.newDocument().group(ORDER, 1).field(CODE, "bad", 1, 1)

        val localeOutcome = DmInterpreter(patternModel).validate(invalid)
        assertEquals(emptyList(), localeOutcome.messages)
        assertTrue(localeOutcome.unsupported.single().reason.contains("\$Field\$"))
        assertFailsWith<IllegalStateException> {
            DmInterpreter(patternModel).validateFull(invalid)
        }
    }

    /** The requiredness producer renders a value token as the empty value it has (RequirednessTextSubstitutionDiffTest). */
    @Test
    fun requirednessRendersTheValueTokenEmpty() {
        val requiredField = FieldDef(
            CODE,
            Kind.STRING,
            required = Requiredness.ABSOLUTE,
            requiredMessages = mapOf("en_US" to "Value \$field.value\$ \$Feld.Wert\$end"),
        )
        val requiredModel = ModelDef(listOf(requiredField), emptyList())
        val empty = requiredModel.newDocument().group(ORDER, 1).emptyField(CODE, 1, 1)
        val outcome = DmInterpreter(requiredModel).validate(empty)
        assertEquals(emptyList(), outcome.unsupported)
        assertEquals(listOf("$MANDATORY_FIELD@Value  end"), outcome.messages.map { "${it.code}@${it.text}" })
    }

    private companion object {
        const val ORDER = "/Order"
        const val CODE = "/Order/Code"
    }
}
