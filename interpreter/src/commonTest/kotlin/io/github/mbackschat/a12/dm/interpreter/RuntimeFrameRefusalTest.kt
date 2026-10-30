package io.github.mbackschat.a12.dm.interpreter

import io.github.mbackschat.a12.dm.interpreter.load.ModelLoader
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * A model whose frame A12 binds and then cannot evaluate is refused by runtime preparation, by its path, and still
 * loads: a missing `content.modelInfo`, absent or `null` `header.locales`, and a `null` supported character stop both
 * Kernel strategies before any rule runs, while a missing `header.modelVersion` costs only the consistency check and
 * is evaluated (KERNEL-FINDINGS KF650; `adapter.laws.CheckOnlyFrameRuntimeDiffTest`). The load column is the control
 * that only preparation for evaluation refuses, as A12 reads and expands every one of these models.
 */
class RuntimeFrameRefusalTest {

    private val rows = linkedMapOf(
        "complete" to model(),
        "modelVersion absent" to model(header = """"id":"policy","locales":[{"code":"en_US"}]"""),
        "modelInfo absent" to model(info = ""),
        "locales absent" to model(header = """"id":"policy","modelVersion":"28.4.0""""),
        "locales null" to model(header = """"id":"policy","modelVersion":"28.4.0","locales":null"""),
        "a null supported character" to model(config = """"modelConfig":{"supportedCharacters":["a-z",null]},"""),
    )

    @Test
    fun runtimePreparationRefusesWhatA12sRuntimeCannotEvaluate() {
        val observed = rows.mapValues { (_, json) ->
            ModelLoader.load(json)
            try {
                PreparedModel.loadExpandedJson(json)
                "prepared"
            } catch (refused: ModelPreparationError) {
                refused.diagnostics.joinToString { "${it.code} ${it.path}" }
            }
        }
        assertEquals(
            mapOf(
                "complete" to "prepared",
                "modelVersion absent" to "prepared",
                "modelInfo absent" to "INVALID_MODEL_SOURCE content.modelInfo",
                "locales absent" to "INVALID_MODEL_SOURCE header.locales",
                "locales null" to "INVALID_MODEL_SOURCE header.locales",
                "a null supported character" to "INVALID_MODEL_SOURCE content.modelConfig.supportedCharacters[1]",
            ),
            observed,
        )
    }

    /**
     * A condition language is read by the locale key A12's parser makes of its code, so `de-DE` declares German as
     * `de_DE` does; a code the parser refuses is not loaded, and one it reads but A12 does not support (the empty code
     * included) is refused only for evaluation (`adapter.laws.UnsupportedConditionLanguageStageDiffTest`).
     */
    @Test
    fun aConditionLanguageIsReadByItsLocaleKeyAndRefusedWhereA12RefusesIt() {
        val codes = listOf("en_US", "de_DE", "en-US", "de-DE", "de_DE_x", "zz", "", "fr_FR", "en_us", "klingon", " ", "\u00a0")
        val observed = codes.associateWith { code ->
            val json = model(config = """"modelConfig":{"conditionLanguage":{"code":"$code"}},""")
            val loaded = try {
                ModelLoader.load(json).conditionLanguage.name
            } catch (refused: IllegalArgumentException) {
                "unread"
            }
            val prepared = try {
                PreparedModel.loadExpandedJson(json)
                "prepared"
            } catch (refused: ModelPreparationError) {
                refused.diagnostics.joinToString { "${it.code} ${it.path}" }
            }
            "$loaded, $prepared"
        }
        val unsupported = "EN, INVALID_MODEL_SOURCE content.modelConfig.conditionLanguage"
        val unread = "unread, INVALID_MODEL_SOURCE content.modelConfig.conditionLanguage"
        assertEquals(
            mapOf(
                "en_US" to "EN, prepared", "de_DE" to "DE, prepared", "en-US" to "EN, prepared", "de-DE" to "DE, prepared",
                "de_DE_x" to unsupported, "zz" to unsupported, "" to unsupported, "fr_FR" to unsupported,
                "en_us" to unread, "klingon" to unread, " " to unread, "\u00a0" to unread,
            ),
            observed,
        )
    }

    private fun model(
        header: String = """"id":"policy","modelVersion":"28.4.0","locales":[{"code":"en_US"}]""",
        info: String = """"modelInfo":{"name":"policy"},""",
        config: String = "",
    ) = """
        {"header":{$header},
         "content":{$info$config"modelRoot":{"rootGroups":[{"type":"Group","id":"/Policy","name":"Policy","Group":{"elements":[
           {"type":"Field","id":"/Policy/Ref","name":"Ref","Field":{"fieldType":{"type":"StringType"}}}]}}]}}}
    """.trimIndent()
}
