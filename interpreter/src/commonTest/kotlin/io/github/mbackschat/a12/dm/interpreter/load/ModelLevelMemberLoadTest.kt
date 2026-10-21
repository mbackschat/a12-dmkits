package io.github.mbackschat.a12.dm.interpreter.load

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * An expanded model loaded directly refuses on each of its model-level members A12 cannot bind, a locale code included, as workspace
 * preparation does for every model it reaches (KF594, `workspace.ReachedModelRefusalTest`). The bound row is the
 * control.
 */
class ModelLevelMemberLoadTest {

    private val rows = linkedMapOf(
        "bound" to """"modelInfo":{"baseYear":2024,"immutable":false,"metaData":true},"modelConfig":{"fieldRefByShortNameAllowed":"True"},""",
        "content.modelInfo.baseYear" to """"modelInfo":{"baseYear":"NULL"},""",
        "content.modelInfo.immutable" to """"modelInfo":{"immutable":1.5},""",
        "content.modelInfo.metaData" to """"modelInfo":{"metaData":1.5},""",
        "content.modelConfig.fieldRefByShortNameAllowed" to """"modelConfig":{"fieldRefByShortNameAllowed":1.5},""",
    )

    @Test
    fun eachUnbindableMemberRefusesTheModelByItsPath() {
        val observed = rows.mapValues { (_, members) ->
            try {
                ModelLoader.load(model(members))
                "bound"
            } catch (refused: InvalidPreparedModelSourceException) {
                refused.path
            }
        }
        assertEquals(rows.mapValues { it.key }, observed)
    }

    /**
     * A locale code A12 cannot parse refuses the model as its members do; the empty code and a locale declared twice
     * are read, as A12 builds an entry declaring them (KF594), and are the controls.
     */
    @Test
    fun aLocaleCodeA12CannotParseRefusesTheModel() {
        val observed = linkedMapOf("en_us" to """"en_US","en_us"""", "empty" to """"en_US",""""", "twice" to """"en_US","en-US"""")
            .mapValues { (_, codes) ->
                try {
                    ModelLoader.load(model("", ""","locales":[$codes]"""))
                    "loads"
                } catch (refused: InvalidPreparedModelSourceException) {
                    refused.path
                }
            }
        assertEquals(mapOf("en_us" to "header.locales", "empty" to "loads", "twice" to "loads"), observed)
    }

    private fun model(members: String, header: String = "") = """
        {"header":{"id":"policy"$header},
         "content":{$members"modelRoot":{"rootGroups":[{"type":"Group","id":"/Policy","name":"Policy","Group":{"elements":[
           {"type":"Field","id":"/Policy/Ref","name":"Ref","Field":{"fieldType":{"type":"StringType"}}}]}}]}}}
    """.trimIndent()
}
