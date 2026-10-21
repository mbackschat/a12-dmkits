package io.github.mbackschat.a12.dm.consistency

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * A model-level member A12's reader cannot bind in a model the entry reaches, an included model or a type-definition
 * library, is reported as the entry model's source refusal, naming the model that carries it (KF594). A12's side of it
 * is `adapter.WorkspaceBlankTextDiffTest`; this holds the checker's reading on every platform it runs on.
 */
class ReachedModelMemberTest {

    private val members = linkedMapOf(
        "baseYear" to (""""modelInfo":{"baseYear":"NULL"},""" to "`content.modelInfo.baseYear` holds `\"NULL\"`"),
        "immutable" to (""""modelInfo":{"immutable":1.5},""" to "`content.modelInfo.immutable` holds `1.5`"),
        "metaData" to (""""modelInfo":{"metaData":1.5},""" to "`content.modelInfo.metaData` holds `1.5`"),
        "fieldRefByShortNameAllowed" to
            (""""modelConfig":{"fieldRefByShortNameAllowed":1.5},""" to "`content.modelConfig.fieldRefByShortNameAllowed` holds `1.5`"),
    )

    @Test
    fun eachReachedModelsUnbindableMemberRefusesTheEntryNamingThatModel() {
        val observed = listOf("addr", "lib").flatMap { reached ->
            members.map { (member, edit) ->
                "$reached $member" to refusals(addr = if (reached == "addr") edit.first else "", lib = if (reached == "lib") edit.first else "")
            }
        }.toMap()
        assertEquals(listOf("addr", "lib").flatMap { reached ->
            members.map { (member, edit) -> "$reached $member" to listOf("In the model `$reached`, ${edit.second}") }
        }.toMap(), observed)
    }

    @Test
    fun theUntouchedWorkspaceIsBuildable() {
        assertEquals(emptyList(), refusals(addr = "", lib = ""))
    }

    /** The entry's not-buildable findings, each cut to the sentence naming the member. */
    private fun refusals(addr: String, lib: String): List<String> =
        ConsistencyWorkspace.fromSources(listOf(order(), address(addr), library(lib))).check("order").findings
            .filter { it.code == CK_MODEL_NOT_BUILDABLE }
            .map { it.explain.substringBefore(", which A12 cannot bind") }

    private fun order() = ModelSource(
        """
        {"header":{"id":"order","locales":[{"code":"en_US"}],"modelReferences":[
           {"alias":"addr","modelType":"document","purpose":"include","reference":"addr"},
           {"alias":"lib","modelType":"document","purpose":"typeDefinitions","reference":"lib"}]},
         "content":{"modelRoot":{"rootGroups":[
           {"type":"Group","id":"g0","name":"Order","Group":{"elements":[
             {"type":"Field","id":"f0","name":"Code","Field":{"fieldType":{"type":"TypeDefType","TypeDefType":{"typeDefinitionId":"OrderCode"}}}},
             {"type":"Group","id":"g1","name":"ShipTo","Group":{"modelAlias":"addr","elements":[]}}
           ]}}]}}}
        """.trimIndent(),
    )

    private fun address(edit: String) = ModelSource(
        """
        {"header":{"id":"addr","locales":[{"code":"en_US"}]},
         "content":{$edit"modelRoot":{"rootGroups":[
           {"type":"Group","id":"a0","name":"Address","Group":{"elements":[
             {"type":"Field","id":"a1","name":"Street","Field":{"fieldType":{"type":"StringType"}}}]}}]}}}
        """.trimIndent(),
    )

    private fun library(edit: String) = ModelSource(
        """
        {"header":{"id":"lib","locales":[{"code":"en_US"}]},
         "content":{$edit"typeDefinitions":[{"id":"OrderCode","name":"OrderCode","fieldType":{"type":"StringType"}}],
          "modelRoot":{"rootGroups":[]}}}
        """.trimIndent(),
    )
}
