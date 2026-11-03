package io.github.mbackschat.a12.dm.adapter.laws

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.databind.node.ObjectNode
import io.github.mbackschat.a12.dm.adapter.KernelConsistencyOracle
import io.github.mbackschat.a12.dm.consistency.ConsistencyReport
import io.github.mbackschat.a12.dm.consistency.ConsistencyWorkspace
import io.github.mbackschat.a12.dm.consistency.ModelSource
import io.github.mbackschat.a12.dm.consistency.check
import io.github.mbackschat.a12.dm.core.ModelResolver
import io.github.mbackschat.a12.dm.core.ReferenceResolvers
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

/**
 * A12's id sentence, "The id [..] of element on path '..' is not unique: Used by [..].", against the portable checker
 * (KERNEL-FINDINGS KF661).
 *
 * Kernel 31.1.1's `IdUniqueRule` states it once for every element that shares its id, at that element's path, whatever
 * the kinds, and goes on: a rule naming no field beside two sharers is still refused, and a rule naming the second
 * sharer is judged as if nothing were wrong. An empty id shares like any other, a blank-named sharer the converter
 * leaves out still counts, and a type definition never shares. An element that arrived through a mount never shares,
 * while the mount group written in the model does. Each row compares A12's sentences and classes, as `code@path` in
 * A12's order, with the checker's findings.
 *
 * Own domain (an insurance policy and an order with two addresses).
 */
class CheckerDuplicateIdAgreementTest {

    private val mapper = ObjectMapper()
    private val oracle = KernelConsistencyOracle()

    private val base = DocumentModelDraft.named("policy-ids").locale("en_US")
        .rootGroup("Policy") { p -> p.stringField("Ref") { }.stringField("Code") { }.stringField("Note") { }
            .group("Items") { g -> g.repeatable(3).stringField("Label") { } } }
        .build().toJson().let { RuntimeLaws.withRule(it, "/Policy", "RefCheck", "../Ref", "REF", "FieldFilled(Ref)") }

    @Test
    fun theCheckerStatesEverySharedIdAsA12Does() {
        val rows = linkedMapOf(
            "the base — the control" to edited(),
            "two fields" to edited("Code" to "shared", "Note" to "shared"),
            "three fields" to edited("Ref" to "shared", "Code" to "shared", "Note" to "shared"),
            "two pairs" to edited("Ref" to "a", "Code" to "a", "Note" to "b", "Items" to "b"),
            "a field and a rule" to edited("Code" to "shared", "RefCheck" to "shared"),
            "a field and a group" to edited("Code" to "shared", "Items" to "shared"),
            "a field and a nested field" to edited("Code" to "shared", "Items/Label" to "shared"),
            "the root group and a field" to edited("Code" to "shared") { root(it).put("id", "shared") },
            "a field and a computation" to edited("Code" to "shared") { tree ->
                val body = elements(tree).addObject().put("type", "Computation").put("id", "shared").put("name", "NoteCalc")
                    .putObject("Computation").put("computedFieldRelPath", "../Note")
                body.putArray("computationAlternatives").addObject().put("operation", "\"x\"")
                body.putArray("errorMessage").addObject().put("locale", "en_US").put("text", "Check the note.")
            },
            "a field taking another's id" to edited("Note" to "/Policy/Code"),
            "two fields with an empty id" to edited("Code" to "", "Note" to ""),
            "a blank-named field sharing an id" to edited("Code" to "shared", "Note" to "shared") { named(it, "Note").put("name", " ") },
            "a type definition sharing a field's id" to edited("Code" to "shared") { tree ->
                (tree.at("/content") as ObjectNode).withArray("/typeDefinitions").addObject().put("id", "shared")
                    .put("name", "CodeType").putObject("fieldType").put("type", "StringType")
            },
            "two fields, a rule naming the second" to edited("Code" to "shared", "Note" to "shared") { tree ->
                (named(tree, "RefCheck").at("/Rule") as ObjectNode).put("errorCondition", "FieldFilled(Note)")
            },
            "two fields, beside a rule naming no field" to edited("Code" to "shared", "Note" to "shared") { tree ->
                (named(tree, "RefCheck").at("/Rule") as ObjectNode).put("errorCondition", "FieldFilled(Nowhere)")
            },
        )
        val observed = rows.mapValues { (_, json) -> a12(json, ReferenceResolvers.selfContained()) to ours(check(ModelSource(json))) }
        assertEquals(observed.mapValues { (_, it) -> it.first to it.first }, observed, "(row → A12's classes in order, the checker's)")
        val shared = listOf("$ID@/Policy/Code", "$ID@/Policy/Note")
        assertEquals(linkedMapOf(
            "the base — the control" to emptyList(),
            "two fields" to shared,
            "two fields, beside a rule naming no field" to shared + "MVK_INVALID_ENTITY@/Policy/RefCheck",
            "a type definition sharing a field's id" to emptyList(),
            "two fields with an empty id" to shared + listOf("RK_ID_EMPTY@/Policy/Code", "RK_ID_EMPTY@/Policy/Note"),
        ), observed.filterKeys { it in PINNED }.mapValues { it.value.first }.let { m -> PINNED.associateWith { m.getValue(it) } },
            "A12's own answer on the rows that separate the account from its rivals")
    }

    /**
     * A computation whose target the checker withholds still carries A12's id sentence, on both withholding arms. The
     * id sentences alone are compared: beside them A12 states the target's own classes, and its empty-target basic
     * sentence names no element where the checker names the computation, which `CheckerBasicSentenceAgreementTest` owns.
     * The control is the shared-id computation with a declared target, which nothing withholds.
     */
    @Test
    fun aWithheldComputationStillSharesItsId() {
        val rows = linkedMapOf(
            "a declared target — the control" to sharedIdComputation("../Note"),
            "an empty target" to sharedIdComputation(""),
            "a target naming no field" to sharedIdComputation("../Missing"),
        )
        val observed = rows.mapValues { (_, json) ->
            a12(json, ReferenceResolvers.selfContained()).idSentences() to ours(check(ModelSource(json))).idSentences()
        }
        val shared = listOf("$ID@/Policy/Code", "$ID@/Policy/NoteCalc")
        assertEquals(rows.keys.associateWith { shared to shared }, observed, "(row → A12's id sentences, the checker's)")
    }

    private fun sharedIdComputation(target: String) = edited("Code" to "shared") { tree ->
        val body = elements(tree).addObject().put("type", "Computation").put("id", "shared").put("name", "NoteCalc")
            .putObject("Computation").put("computedFieldRelPath", target)
        body.putArray("computationAlternatives").addObject().put("operation", "\"x\"")
    }

    private fun List<String>.idSentences() = filter { it.startsWith("$ID@") }

    @Test
    fun anElementThatArrivedThroughAMountSharesNothing(@TempDir dir: Path) {
        Files.writeString(dir.resolve("address.dm.json"), ADDRESS)
        val rows = linkedMapOf(
            "one mount — the control" to order(""),
            "two mounts" to order(""",{"type":"Group","id":"g2","name":"BillTo","Group":{"includeConfig":{"reference":"address"}}}"""),
            "a host field sharing the provider field's id" to
                order(""",{"type":"Field","id":"f-Street","name":"Street","Field":{"fieldType":{"type":"StringType"}}}"""),
            "a host field sharing the mount's id" to
                order(""",{"type":"Field","id":"g1","name":"Note","Field":{"fieldType":{"type":"StringType"}}}"""),
        )
        val observed = rows.mapValues { (_, json) ->
            a12(json, ReferenceResolvers.directory(dir)) to
                ours(ConsistencyWorkspace.fromSources(listOf(ModelSource(json), ModelSource(ADDRESS))).check("order"))
        }
        assertEquals(observed.mapValues { (_, it) -> it.first to it.first }, observed, "(row → A12's classes in order, the checker's)")
        assertEquals(listOf("$ID@/Order/ShipTo", "$ID@/Order/Note"), observed.getValue("a host field sharing the mount's id").first,
            "the mount group shares its id, so the rows that stay silent are not silent for want of a sentence")
    }

    private fun a12(json: String, resolver: ModelResolver): List<String> = oracle.checkConsistency(json, resolver).map {
        val message = it.message().trim()
        val at = Regex("""(?:on path|with path) '([^']*)'""").find(message)?.groupValues?.get(1) ?: "(unattached)"
        "${legibleNotificationClass(message)}@$at"
    }

    private fun ours(report: ConsistencyReport): List<String> =
        report.findings.filter { it.justifiesRejection }.map { "${it.code}@${it.at}" }

    private fun root(tree: ObjectNode) = tree.at("/content/modelRoot/rootGroups/0") as ObjectNode
    private fun elements(tree: ObjectNode, group: String? = null): ArrayNode {
        val top = root(tree).at("/Group/elements") as ArrayNode
        return if (group == null) top else top.single { it.path("name").asText() == group }.at("/Group/elements") as ArrayNode
    }
    private fun named(tree: ObjectNode, name: String, group: String? = null) =
        elements(tree, group).single { it.path("name").asText() == name } as ObjectNode

    /** The base with each `name` (or `Group/name`) given the paired id, then [extra] applied. */
    private fun edited(vararg ids: Pair<String, String>, extra: (ObjectNode) -> Unit = {}): String {
        val tree = mapper.readTree(base) as ObjectNode
        for ((at, id) in ids) {
            val parts = at.split("/")
            named(tree, parts.last(), parts.getOrNull(parts.size - 2)).put("id", id)
        }
        extra(tree)
        return tree.toString()
    }

    private fun order(extra: String) = """
        {"header":{"id":"order","modelType":"document","modelVersion":"29.4.0","locales":[{"code":"en_US"}],
          "modelReferences":[{"purpose":"include","reference":"address"}]},
         "content":{"modelInfo":{"name":"order"},"modelRoot":{"rootGroups":[{"type":"Group","id":"g0","name":"Order","Group":{"elements":[
          {"type":"Group","id":"g1","name":"ShipTo","Group":{"includeConfig":{"reference":"address"}}}$extra]}}]}}}
    """.trimIndent()

    private companion object {
        const val ID = "RK_ELEMENT_ID_NOT_UNIQUE"
        val PINNED = listOf("the base — the control", "two fields", "two fields, beside a rule naming no field",
            "a type definition sharing a field's id", "two fields with an empty id")
        val ADDRESS = """
            {"header":{"id":"address","modelType":"document","modelVersion":"29.4.0","locales":[{"code":"en_US"}]},
             "content":{"modelInfo":{"name":"address"},"modelRoot":{"rootGroups":[{"type":"Group","id":"ga","name":"Address",
              "Group":{"elements":[{"type":"Field","id":"f-Street","name":"Street","Field":{"fieldType":{"type":"StringType"}}}]}}]}}}
        """.trimIndent()
    }
}
