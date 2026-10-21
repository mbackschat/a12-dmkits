package io.github.mbackschat.a12.dm.interpreter.load

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * An expanded model loaded directly refuses on each of its model-level members A12 cannot bind, as workspace
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

    private fun model(members: String) = """
        {"header":{"id":"policy"},
         "content":{$members"modelRoot":{"rootGroups":[{"type":"Group","id":"/Policy","name":"Policy","Group":{"elements":[
           {"type":"Field","id":"/Policy/Ref","name":"Ref","Field":{"fieldType":{"type":"StringType"}}}]}}]}}}
    """.trimIndent()
}
