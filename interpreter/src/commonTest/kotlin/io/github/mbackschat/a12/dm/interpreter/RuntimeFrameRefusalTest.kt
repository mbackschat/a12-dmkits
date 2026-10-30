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
