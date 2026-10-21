package io.github.mbackschat.a12.dm.consistency

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * What leaves A12 unable to build the entry from a model it reaches, an included model or a type-definition library,
 * is reported as the entry model's source refusal, naming the model that carries it (KF594): a model-level member its
 * reader cannot bind, an entry locale the reached model does not declare, and a library's root group. A12's side of
 * it is `adapter.WorkspaceReachedModelDiffTest`; this holds the checker's reading on every platform it runs on.
 */
class ReachedModelRefusalTest {

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

    /** The entry also declares `de_DE`, which each reached model in turn leaves out; a superset is the control. */
    @Test
    fun aReachedModelMissingAnEntryLocaleRefusesTheEntry() {
        val both = """[{"code":"en_US"},{"code":"de_DE"}]"""
        val observed = listOf("addr", "lib").associateWith { narrow ->
            refusals(addr = "", lib = "", entryLocales = both,
                addrLocales = if (narrow == "addr") ENGLISH else both, libLocales = if (narrow == "lib") ENGLISH else both)
        }
        assertEquals(mapOf(
            "addr" to listOf("The model `addr` does not declare the entry model's locale de_DE, so A12 does not expand it"),
            "lib" to listOf("The model `lib` does not declare the entry model's locale de_DE, so A12 does not expand it"),
        ), observed)
        val superset = """[{"code":"en_US"},{"code":"de_DE"},{"code":"fr_FR"}]"""
        assertEquals(emptyList(), refusals(addr = "", lib = "", entryLocales = both, addrLocales = superset, libLocales = superset))
    }

    /** A library declares no root group; one, even empty, is refused. The include keeps its own root group. */
    @Test
    fun aTypeDefinitionLibraryWithARootGroupRefusesTheEntry() {
        assertEquals(
            listOf("The type-definition library `lib` declares a root group, so A12 cannot expand the import"),
            refusals(addr = "", lib = "", libRoots = """[{"type":"Group","id":"t0","name":"Types","Group":{"elements":[]}}]"""),
        )
    }

    /** The entry's not-buildable findings, each cut to the sentence naming what refused it. */
    private fun refusals(
        addr: String,
        lib: String,
        entryLocales: String = ENGLISH,
        addrLocales: String = ENGLISH,
        libLocales: String = ENGLISH,
        libRoots: String = "[]",
    ): List<String> =
        ConsistencyWorkspace.fromSources(listOf(order(entryLocales), address(addr, addrLocales), library(lib, libLocales, libRoots)))
            .check("order").findings
            .filter { it.code == CK_MODEL_NOT_BUILDABLE }
            .map { it.explain.substringBefore(", which A12 cannot bind").substringBefore(". A12 reads every model") }

    private fun order(locales: String) = ModelSource(
        """
        {"header":{"id":"order","locales":$locales,"modelReferences":[
           {"alias":"addr","modelType":"document","purpose":"include","reference":"addr"},
           {"alias":"lib","modelType":"document","purpose":"typeDefinitions","reference":"lib"}]},
         "content":{"modelRoot":{"rootGroups":[
           {"type":"Group","id":"g0","name":"Order","Group":{"elements":[
             {"type":"Field","id":"f0","name":"Code","Field":{"fieldType":{"type":"TypeDefType","TypeDefType":{"typeDefinitionId":"OrderCode"}}}},
             {"type":"Group","id":"g1","name":"ShipTo","Group":{"modelAlias":"addr","elements":[]}}
           ]}}]}}}
        """.trimIndent(),
    )

    private fun address(edit: String, locales: String) = ModelSource(
        """
        {"header":{"id":"addr","locales":$locales},
         "content":{$edit"modelRoot":{"rootGroups":[
           {"type":"Group","id":"a0","name":"Address","Group":{"elements":[
             {"type":"Field","id":"a1","name":"Street","Field":{"fieldType":{"type":"StringType"}}}]}}]}}}
        """.trimIndent(),
    )

    private fun library(edit: String, locales: String, roots: String) = ModelSource(
        """
        {"header":{"id":"lib","locales":$locales},
         "content":{$edit"typeDefinitions":[{"id":"OrderCode","name":"OrderCode","fieldType":{"type":"StringType"}}],
          "modelRoot":{"rootGroups":$roots}}}
        """.trimIndent(),
    )

    private companion object {
        const val ENGLISH = """[{"code":"en_US"}]"""
    }
}
