package io.github.mbackschat.a12.dm.adapter.laws

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import io.github.mbackschat.a12.dm.interpreter.ModelPreparationError
import io.github.mbackschat.a12.dm.interpreter.PreparedModel
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * How A12 reads a model's condition-language code, and where it refuses one (KERNEL-FINDINGS KF650). One English rule
 * tells the three readings apart: it parses under English, draws `MVK_UNEXPECTED_TOKEN` under German, and a language
 * A12 does not support stops the check and both strategies with its uncoded "Sprache … wird nicht unterstützt". A code
 * its locale parser refuses ("Invalid locale format") is not read at all. A12 reads the code by the locale key it
 * parses, so `de-DE` is German; the interpreter reads it alike, refuses the unread codes at load and the unsupported
 * ones at runtime preparation. Own domain: an insurance policy whose reference is required.
 */
class UnsupportedConditionLanguageStageDiffTest {

    private val mapper = ObjectMapper()

    private val base: String = DocumentModelDraft.named("policy-language").locale("en_US")
        .rootGroup("Policy") { p -> p.stringField("Ref") { } }
        .build().toJson()

    private val sound: String = RuntimeLaws.withRule(base, "/Policy", "RRef", "../Ref", "REF",
        RuntimeLaws.render(BoundModel.ofJson(base).str("/Policy/Ref").notFilled()))

    private fun declaring(code: String): String {
        val root = mapper.readTree(sound) as ObjectNode
        (root.at("/content/modelConfig") as ObjectNode).putObject("conditionLanguage").put("code", code)
        return root.toString()
    }

    /**
     * Which language a column read the English rule in: `en` when it parses, `de` when the German vocabulary refuses
     * `FieldNotFilled`, `unsupported` for A12's uncoded refusal of the language, `unread` when A12's reader refuses the
     * code. Anything else is reported verbatim, so an outcome this classification did not foresee cannot hide in it.
     */
    private fun reading(stage: () -> String): String = try {
        when (val answer = stage()) {
            "", "consistent" -> "en"
            "REF" -> "en, fires"
            else -> if ("MVK_UNEXPECTED_TOKEN" in answer) "de" else answer
        }
    } catch (failed: RuntimeException) {
        val text = generateSequence<Throwable>(failed) { it.cause }.joinToString(" <- ") { "${it::class.simpleName}: ${it.message}" }
        when {
            "wird nicht unterstützt" in text -> "unsupported"
            "Invalid locale format" in text -> "unread"
            "MVK_UNEXPECTED_TOKEN" in text || "is not a function keyword in de_DE" in text -> "de"
            else -> text
        }
    }

    /** `[check, dynamic Groovy, generated Java, interpreter]` over a policy with no `Ref`. */
    private fun observed(model: String): List<String> {
        val kernelDoc = RuntimeLaws.doc().group("/Policy", 1)
        return listOf(
            reading { RuntimeLaws.consistencyMessages(model).joinToString(" | ").ifEmpty { "consistent" } },
            reading { RuntimeLaws.messages(model, kernelDoc).joinToString { it.code() } },
            reading { RuntimeLaws.kernelJavaEngine(model).messagesModern(kernelDoc, null, java.util.Locale.US).joinToString { it.code() } },
            reading {
                try {
                    val prepared = PreparedModel.loadExpandedJson(model)
                    prepared.createInterpreter().validateFull(prepared.documents.readDocument("""{"Policy":{}}"""))
                        .findings.joinToString { it.code }
                } catch (refused: ModelPreparationError) {
                    "refused " + refused.diagnostics.joinToString { "${it.code} ${it.path}" }
                }
            },
        )
    }

    @Test
    fun eachCodeIsReadByItsLocaleKeyAndRefusedWhereA12Refuses() {
        val english = listOf("en", "en, fires", "en, fires", "en, fires")
        val german = listOf("de", "de", "de", "de")
        val language = "refused INVALID_MODEL_SOURCE content.modelConfig.conditionLanguage"
        val unsupported = listOf("unsupported", "unsupported", "unsupported", language)
        val unread = listOf("unread", "unread", "unread", language)
        assertEquals(
            mapOf(
                "en_US" to english, "de_DE" to german, "en-US" to english, "de-DE" to german,
                "de_DE_x" to unsupported, "zz" to unsupported, "" to unsupported, "fr_FR" to unsupported,
                "de" to unsupported, "en" to unsupported,
                "en_us" to unread, "EN_US" to unread, "klingon" to unread, "en_USA" to unread,
                " " to unread, "\u00a0" to unread, "\u2003" to unread,
            ),
            listOf(
                "en_US", "de_DE", "en-US", "de-DE", "de_DE_x", "zz", "", "fr_FR", "de", "en",
                "en_us", "EN_US", "klingon", "en_USA", " ", "\u00a0", "\u2003",
            ).associateWith { observed(declaring(it)) },
            "as [check, dynamic Groovy, generated Java, interpreter]",
        )
    }

    /** The reader keeps no spelling: A12 writes back the hyphenated code it read as the locale's own form. */
    @Test
    fun theReaderKeepsTheLocaleNotItsSpelling() {
        assertEquals("de_DE", mapper.readTree(RuntimeLaws.modelRoundTrip(declaring("de-DE")))
            .at("/content/modelConfig/conditionLanguage").let { it.path("code").takeIf { c -> !c.isMissingNode } ?: it }.asText())
    }
}
