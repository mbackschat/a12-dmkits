package io.github.mbackschat.a12.dm.adapter.laws

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import io.github.mbackschat.a12.dm.edit.EditSession
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import io.github.mbackschat.a12.dm.rulekit.dsl.Condition
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl
import io.github.mbackschat.a12.dm.rulekit.write.ModelWriter
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * The checker against A12 on a rule's `filterForPossiblyCausingEntities` (KERNEL-FINDINGS KF682): what each row
 * draws, as A12's class set and the checker's rejecting class set. Order is not compared; A12 states the reference
 * verdict from `NoMetaModelChecks` and the duplicate from its metamodel, ahead of other rules' condition classes.
 *
 * Every entry is compared, after A12's own spelling map, with the canonical relative path of an entity the condition
 * references, so the rows pair an admitted spelling with the near misses beside it. Own domain: an insurance policy
 * with a premium, a holder, a rider and keyed claim lines.
 */
class CheckerPossibleCauseFilterAgreementTest {

    private val mapper = ObjectMapper()

    private val base = DocumentModelDraft.named("claims").locale("en_US")
        .rootGroup("Policy") { policy -> policy
            .numberField("Premium") { }
            .stringField("Holder") { }
            .group("Rider") { it.stringField("Code") { } }
            .group("Lines") { lines -> lines.repeatable(5).indexField("Key")
                .stringField("Key") { }
                .numberField("Amount") { }
            }
        }.build().toJson()

    private val policy = EditSession.read(base).group("/Policy")
    private val lines = EditSession.read(base).group("/Policy/Lines")

    /** The condition shapes whose references differ; every one also references the rule's error field. */
    private enum class Construct(val group: String) {
        FIELDS("/Policy"), UPWARD("/Policy/Lines"), AGGREGATE("/Policy"), GROUP("/Policy"),
        SEMANTIC_INDEX("/Policy/Lines"), HAVING("/Policy"),
    }

    private fun condition(construct: Construct): Condition = when (construct) {
        Construct.FIELDS -> policy.num("Premium").filled().and(policy.str("Holder").filled())
        Construct.UPWARD -> lines.num("Amount").gt(policy.num("Premium"))
        Construct.AGGREGATE -> policy.num("Premium").filled().and(Dsl.sum(policy.flatten("Lines*/Amount")).gt(5))
        Construct.GROUP -> policy.num("Premium").filled().and(Dsl.groupFilled(policy.group("Rider")))
        Construct.SEMANTIC_INDEX -> Dsl.groupFilled(lines.ref())
            .and(lines.num("Amount").gt(lines.num("Amount").forIndex("K1")))
        Construct.HAVING -> policy.num("Premium").filled()
            .and(Dsl.sum(policy.flatten("Lines*/Amount").having(lines.num("Amount").gt(0))).gt(5))
    }

    /** The model with rule `Flagged` under [construct]'s group, its filter set to [filter]'s JSON. */
    private fun raw(construct: Construct, filter: String, host: String = base): String {
        val editor = if (construct.group == "/Policy") policy else lines
        val ruled = ModelWriter.addRule(host, editor.rule {
            it.name("Flagged").code("FLAGGED").errorField(editor.num(if (editor == policy) "Premium" else "Amount").field())
                .`when`(condition(construct)).message(Dsl.en("Check the claim.")).build()
        })
        val tree = mapper.readTree(ruled) as ObjectNode
        flagged(tree).set<ObjectNode>(FILTER, mapper.readTree(filter))
        return mapper.writeValueAsString(tree)
    }

    private fun model(construct: Construct, ifFilled: List<String>, ifNotFilled: List<String> = emptyList(),
                      host: String = base): String {
        val filter = mapper.createObjectNode().apply {
            putArray(IF_FILLED).apply { ifFilled.forEach { addObject().put(PATH, it) } }
            putArray(IF_NOT_FILLED).apply { ifNotFilled.forEach { addObject().put(PATH, it) } }
        }
        return raw(construct, filter.toString(), host)
    }

    private fun flagged(tree: ObjectNode) =
        tree.findParents("name").single { it.path("name").asText() == "Flagged" }.path("Rule") as ObjectNode

    private fun edited(json: String, edit: (ObjectNode) -> Unit) =
        mapper.writeValueAsString((mapper.readTree(json) as ObjectNode).also(edit))

    private fun assertAgree(rows: Map<String, Pair<String, Set<String>>>) {
        val expected = rows.mapValues { it.value.second }
        assertEquals(expected, rows.mapValues { kernelModelClasses(it.value.first) }, "A12's classes")
        assertEquals(expected, rows.mapValues { rejectingCodes(it.value.first).toSet() }, "the checker's classes")
    }

    @Test
    fun anEntryMustBeTheCanonicalRelativePathOfAReferencedEntity() {
        val rows = linkedMapOf(
            "fields named as written" to (model(Construct.FIELDS, listOf("Premium"), listOf("Holder")) to NONE),
            "a parent field from a repeated group" to (model(Construct.UPWARD, listOf("Amount", "../Premium")) to NONE),
            "a starred field below" to (model(Construct.AGGREGATE, listOf("Lines/Amount")) to NONE),
            "a group the condition fills" to (model(Construct.GROUP, listOf("Rider")) to NONE),
            "the implied index field, and the own group as ''" to
                (model(Construct.SEMANTIC_INDEX, listOf("Key", "Amount", "")) to NONE),
            "the own group as './'" to (model(Construct.SEMANTIC_INDEX, listOf("./")) to NONE),
            "a field read inside Having" to (model(Construct.HAVING, listOf("Lines/Amount")) to NONE),
            "the same entry in both lists" to (model(Construct.FIELDS, listOf("Premium"), listOf("Premium")) to NONE),
            "a leading ./" to (model(Construct.FIELDS, listOf("./Premium")) to IF_FILLED_REF),
            "through the root group" to (model(Construct.FIELDS, listOf("../Policy/Premium")) to IF_FILLED_REF),
            "a trailing slash" to (model(Construct.FIELDS, listOf("Premium/")) to IF_FILLED_REF),
            "a leading blank" to (model(Construct.FIELDS, listOf(" Premium")) to IF_FILLED_REF),
            "an up-step that returns" to (model(Construct.FIELDS, listOf("Lines/../Premium")) to IF_FILLED_REF),
            "a doubled slash" to (model(Construct.FIELDS, listOf("Rider//Code")) to IF_FILLED_REF),
            "a trailing up-step" to (model(Construct.FIELDS, listOf("Premium/..")) to IF_FILLED_REF),
            "a dot" to (model(Construct.FIELDS, listOf(".")) to IF_FILLED_REF),
            "the technical root" to (model(Construct.FIELDS, listOf("../")) to IF_FILLED_REF),
            "a group the condition does not fill" to (model(Construct.FIELDS, listOf("Rider")) to IF_FILLED_REF),
            "the own group the condition never names" to (model(Construct.FIELDS, listOf("")) to IF_FILLED_REF),
            "a field outside the group" to (model(Construct.UPWARD, listOf("Premium")) to IF_FILLED_REF),
            "the parent group of a field read" to (model(Construct.UPWARD, listOf("..")) to IF_FILLED_REF),
            "a starred group itself" to (model(Construct.AGGREGATE, listOf("Lines")) to IF_FILLED_REF),
            "the star as written" to (model(Construct.AGGREGATE, listOf("Lines*/Amount")) to IF_FILLED_REF),
            "a field below a filled group" to (model(Construct.GROUP, listOf("Rider/Code")) to IF_FILLED_REF),
            "the Having group" to (model(Construct.HAVING, listOf("Lines")) to IF_FILLED_REF),
            "a number, read as its text" to (raw(Construct.FIELDS, """{"$IF_FILLED":[{"$PATH":5}]}""") to IF_FILLED_REF),
            "both lists" to (model(Construct.FIELDS, listOf("Nope"), listOf("Nope2")) to IF_FILLED_REF + IF_NOT_FILLED_REF),
            "a thousand entries" to (model(Construct.FIELDS, (1..1000).map { "Premium$it" }) to IF_FILLED_REF),
        )
        assertAgree(rows)
    }

    @Test
    fun aRepeatedEntryIsStatedWhateverElseTheRuleGetsWrong() {
        val dup = model(Construct.FIELDS, listOf("Premium", "Premium"))
        val rows = linkedMapOf(
            "a repeated entry" to (dup to IF_FILLED_DUP),
            "a repeat after the spelling map" to (model(Construct.SEMANTIC_INDEX, listOf("", "./")) to IF_FILLED_DUP),
            "a trailing up-step repeated by its mapped spelling" to
                (model(Construct.FIELDS, listOf("Rider/..", "Rider/../")) to IF_FILLED_REF + IF_FILLED_DUP),
            "a repeated unreferenced entry" to (model(Construct.FIELDS, listOf("Nope", "Nope")) to IF_FILLED_REF + IF_FILLED_DUP),
            "repeated in the second list" to (model(Construct.FIELDS, emptyList(), listOf("Holder", "Holder")) to
                setOf("CUSTOM_REF_ERROR_ENTITY_IF_NOT_FILLED_DUPLICATE")),
            "beside an incomplete condition" to (edited(dup) { flagged(it).put("errorCondition", "[Premium] > ") } to
                IF_FILLED_DUP + "MVK_INCOMPLETE_INPUT"),
            "beside an unreferenced error field" to
                (edited(model(Construct.FIELDS, listOf("Holder", "Holder"))) { flagged(it).put("errorCondition", "FieldFilled(Holder)") } to
                    IF_FILLED_DUP + "MVK_ERROR_FIELD_NOT_REFERENCED"),
            "beside another rule's unresolved reference" to (withBroken(dup) to IF_FILLED_DUP + "MVK_INVALID_ENTITY"),
            "beside an invalid time zone" to (edited(dup, ::invalidZone) to IF_FILLED_DUP + "MVK_INVALID_TIME_ZONE"),
            "beside an unresolved sort field" to (edited(dup, ::ghostSort) to IF_FILLED_DUP + "MVK_INVALID_SORT_FIELD"),
        )
        assertAgree(rows)
    }

    @Test
    fun theReferenceVerdictIsWithheldByTheRulesOwnConditionAndByTheEarlierGates() {
        val nope = model(Construct.FIELDS, listOf("Nope"))
        val rows = linkedMapOf(
            "own unresolved reference" to
                (edited(nope) { flagged(it).put("errorCondition", "[Premium] > 0 And [Ghost] > 0") } to setOf("MVK_INVALID_ENTITY")),
            "own incomplete condition" to (edited(nope) { flagged(it).put("errorCondition", "[Premium] > ") } to setOf("MVK_INCOMPLETE_INPUT")),
            "own unreferenced error field" to (edited(nope) { flagged(it).put("errorCondition", "FieldFilled(Holder)") } to
                setOf("MVK_ERROR_FIELD_NOT_REFERENCED")),
            "an invalid time zone" to (edited(nope, ::invalidZone) to setOf("MVK_INVALID_TIME_ZONE")),
            "an unresolved sort field" to (edited(nope, ::ghostSort) to setOf("MVK_INVALID_SORT_FIELD")),
            // Not withheld: the rule's own message, another rule's condition, a sort field named twice.
            "own message parameter" to (edited(nope) { (flagged(it).path("errorMessage")[0] as ObjectNode).put("text", "See \$Ghost\$.") } to
                IF_FILLED_REF + "INVALID_ENTITY"),
            "another rule's unresolved reference" to (withBroken(nope) to IF_FILLED_REF + "MVK_INVALID_ENTITY"),
            "a sort field named twice" to (edited(nope) { sortFields(it, "Key", "Key") } to IF_FILLED_REF + "MVK_SORT_FIELD_DUPLICATE"),
        )
        assertAgree(rows)
    }

    @Test
    fun aShapeTheReaderRefusesLeavesNoModel() {
        val unbuildable = setOf("CK_MODEL_NOT_BUILDABLE")
        val rows = linkedMapOf(
            "the filter null" to (raw(Construct.FIELDS, "null") to NONE),
            "an unknown key" to (raw(Construct.FIELDS, """{"other":[]}""") to NONE),
            "an entry's extra member" to (raw(Construct.FIELDS, """{"$IF_FILLED":[{"$PATH":"Premium","x":1}]}""") to NONE),
            "a climb to the technical root" to (model(Construct.FIELDS, listOf("../Premium")) to IF_FILLED_REF),
            "a climb to the technical root from a deeper group" to (model(Construct.UPWARD, listOf("../../Premium")) to IF_FILLED_REF),
            "the filter text" to (raw(Construct.FIELDS, "\"x\"") to unbuildable),
            "the filter a list" to (raw(Construct.FIELDS, "[]") to unbuildable),
            "the filter a number" to (raw(Construct.FIELDS, "5") to unbuildable),
            "the filter a boolean" to (raw(Construct.FIELDS, "true") to unbuildable),
            "a list as text" to (raw(Construct.FIELDS, """{"$IF_FILLED":"Premium"}""") to unbuildable),
            "a list null" to (raw(Construct.FIELDS, """{"$IF_FILLED":null}""") to unbuildable),
            "a list an object" to (raw(Construct.FIELDS, """{"$IF_FILLED":{}}""") to unbuildable),
            "an entry as text" to (raw(Construct.FIELDS, """{"$IF_FILLED":["Premium"]}""") to unbuildable),
            "an entry null" to (raw(Construct.FIELDS, """{"$IF_FILLED":[null]}""") to unbuildable),
            "an entry a number" to (raw(Construct.FIELDS, """{"$IF_FILLED":[5]}""") to unbuildable),
            "an entry without a path" to (raw(Construct.FIELDS, """{"$IF_FILLED":[{}]}""") to unbuildable),
            "a path null" to (raw(Construct.FIELDS, """{"$IF_FILLED":[{"$PATH":null}]}""") to unbuildable),
            "a path a list" to (raw(Construct.FIELDS, """{"$IF_FILLED":[{"$PATH":[]}]}""") to unbuildable),
            "a path an object" to (raw(Construct.FIELDS, """{"$IF_FILLED":[{"$PATH":{}}]}""") to unbuildable),
            "an absolute path" to (model(Construct.FIELDS, listOf("/Policy/Premium")) to unbuildable),
            "a climb past the technical root" to (model(Construct.FIELDS, listOf("../..")) to unbuildable),
            "a climb past the technical root from a deeper group" to (model(Construct.UPWARD, listOf("../../..")) to unbuildable),
        )
        assertAgree(rows)
    }

    private fun withBroken(json: String) =
        RuntimeLaws.withRule(json, "/Policy", "Broken", "../Premium", "BROKEN", "[Ghost] > 0")

    private fun invalidZone(tree: ObjectNode) {
        (tree.path("content").path("modelConfig") as ObjectNode).put("timeZone", "Nowhere/Town")
    }

    private fun ghostSort(tree: ObjectNode) = sortFields(tree, "Ghost")

    private fun sortFields(tree: ObjectNode, vararg names: String) {
        val group = tree.findParents("name").single { it.path("name").asText() == "Lines" }.path("Group") as ObjectNode
        group.putArray("sortFields").apply { names.forEach { addObject().put("name", it).put("order", "ASC") } }
    }

    private companion object {
        const val FILTER = "filterForPossiblyCausingEntities"
        const val IF_FILLED = "entitiesEnabledAsPossibleCauseIfFilled"
        const val IF_NOT_FILLED = "entitiesEnabledAsPossibleCauseIfNotFilled"
        const val PATH = "relativePath"
        val NONE = emptySet<String>()
        val IF_FILLED_REF = setOf("MVK_REF_ERROR_ENTITY_IF_FILLED_NOT_REFERENCED")
        val IF_NOT_FILLED_REF = setOf("MVK_REF_ERROR_ENTITY_IF_NOT_FILLED_NOT_REFERENCED")
        val IF_FILLED_DUP = setOf("CUSTOM_REF_ERROR_ENTITY_IF_FILLED_DUPLICATE")
    }
}
