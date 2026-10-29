package io.github.mbackschat.a12.dm.consistency

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The frame reader on both runtimes, one row per binding kind and tier (KERNEL-FINDINGS KF650). The Kernel-gate grid
 * is `adapter.laws.CheckerModelFrameAgreementTest`; the bound frame is the control.
 */
class ModelFrameTest {

    @Test
    fun eachMemberIsJudgedByHowA12BindsIt() {
        val rows = linkedMapOf(
            "bound" to frame(""""modelConfig":{"conditionLanguage":"de_DE","supportedCharacters":["a",5]}"""),
            "object null" to frame(""""modelConfig":null"""),
            "text of the wrong kind" to frame(""""modelInfo":{"name":"policy","comment":[]}""", modelInfo = false),
            "list of the wrong kind" to frame(""""modelRoot":{"rootGroups":{}}"""),
            "language without code" to frame(""""modelConfig":{"conditionLanguage":{"code":null}}"""),
            "entry of the wrong kind" to frame(""""modelConfig":{"supportedCharacters":["a",{}]}"""),
            "needed by the check" to frame(""""modelConfig":{"supportedCharacters":[null]}""", modelInfo = false),
            "not an object" to "[]",
        )
        assertEquals(
            mapOf(
                "bound" to emptyList(),
                "object null" to listOf("READ `content.modelConfig` holds `null`"),
                "text of the wrong kind" to listOf("READ `content.modelInfo.comment` holds an array"),
                "list of the wrong kind" to listOf("READ `content.modelRoot.rootGroups` holds an object"),
                "language without code" to listOf("READ `content.modelConfig.conditionLanguage` holds an object"),
                "entry of the wrong kind" to listOf("READ `content.modelConfig.supportedCharacters[1]` holds an object"),
                "needed by the check" to listOf(
                    "CHECK `content.modelInfo` is absent",
                    "CHECK `content.modelConfig.supportedCharacters[0]` holds `null`",
                ),
                "not an object" to listOf("READ the model is not a JSON object"),
            ),
            rows.mapValues { (_, json) -> Json.parseToJsonElement(json).frameDefects().map { "${it.tier} ${it.sentence}" } },
        )
    }

    private fun frame(content: String, modelInfo: Boolean = true): String =
        """{"header":{"id":"policy","modelVersion":"28.4.0","locales":[{"code":"en_US"}]},
           "content":{${if (modelInfo) """"modelInfo":{"name":"policy"},""" else ""}$content}}"""
}
