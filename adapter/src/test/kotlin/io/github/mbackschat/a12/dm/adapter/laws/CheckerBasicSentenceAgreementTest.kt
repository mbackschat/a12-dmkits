package io.github.mbackschat.a12.dm.adapter.laws

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.databind.node.ObjectNode
import io.github.mbackschat.a12.dm.consistency.ModelSource
import io.github.mbackschat.a12.dm.consistency.check
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * The checker against A12 — the basic sentences and the build failures beside them, each defect once on a
 * declaration the default variant keeps and once under an `ONLY` filter (KERNEL-FINDINGS KF626, a12-kernel-lean
 * SPEC-2026-10-27-01).
 *
 * A12's `BasicConsistencyCheck` is its last consistency rule and runs over the model as read, so its sentences
 * survive the filter: on a deleted field or a suppressed rule or computation the sentence is the only refusal.
 * It tests `StringUtils.isEmpty`, which is the discriminator against "blank": a name, condition, error target or
 * type-definition id of one space draws nothing. A failure while A12 CONVERTS a declaration — two categories of
 * one name, two hint-list entries for one locale, an empty date-fragment format, an empty computed field — is
 * lifted by the filter, because a declaration the default variant removes is never converted; where the
 * declaration is kept the build fails and the sentence is never reached.
 *
 * A12's column is asserted, not copied: every row reads `BoundModel` and maps a codeless sentence through
 * [codelessClass]. Where A12 cannot build the model, the checker only has to say so.
 *
 * Own domain (an insurance policy with a status, a total and a reference check).
 */
class CheckerBasicSentenceAgreementTest {

    private val mapper = ObjectMapper()

    private val base = DocumentModelDraft.named("policy-basic-sentences").locale("en_US")
        .rootGroup("Policy") { p -> p.stringField("Ref") { }.stringField("Code") { }
            .enumField("Status") { it.value("OPEN").value("CLOSED").category("Band", "A", "B") }
            .dateFragmentField("Month") { it.fragmentFormat("yyyy-MM") }
            .numberField("Total") { it.fractionDigits(0) }
            .group("Items") { g -> g.repeatable(3).stringField("Label") { } } }
        .build().toJson().let { RuntimeLaws.withRule(it, "/Policy", "RefCheck", "../Ref", "REF", "FieldFilled(Ref)") }

    @Test
    fun theCheckerStatesWhatA12StatesWithAndWithoutTheFilter() {
        val actual = WITNESSES.flatMap { (name, mutate) ->
            listOf(false, true).map { filtered ->
                val root = mapper.readTree(base) as ObjectNode
                mutate(Model(root), filtered)
                val json = root.toString()
                val a12 = kernelModelClasses(json)
                val ours = check(ModelSource(json)).findings.filter { it.justifiesRejection }.mapTo(sortedSetOf()) { it.code }
                "$name/${if (filtered) "ONLY" else "kept"}" to (a12.toSortedSet() to verdict(a12, ours))
            }
        }.toMap()
        val expected = A12.mapValues { (_, classes) -> classes.toSortedSet() to verdict(classes, classes.toSortedSet()) }
        assertEquals(expected, actual, "as (A12's classes, the checker's answer)")
    }

    @Test
    fun theBaseIsClean() {
        assertEquals(emptySet<String>(), kernelModelClasses(base))
    }

    /** The checker's codes, or for a model A12 cannot build, whether the checker reports that too. */
    private fun verdict(a12: Set<String>, ours: Set<String>): String =
        if (a12 == setOf(UNBUILT)) "unbuilt, reported: ${UNBUILT in ours}" else ours.toString()

    private class Model(val root: ObjectNode) {
        fun elements(): ArrayNode =
            root.path("content").path("modelRoot").path("rootGroups").get(0).path("Group").path("elements") as ArrayNode

        fun element(name: String): ObjectNode = elements().single { it.path("name").asText() == name } as ObjectNode
        fun field(name: String): ObjectNode = element(name).path("Field") as ObjectNode
        fun rule(): ObjectNode = elements().single { it.path("type").asText() == "Rule" }.path("Rule") as ObjectNode

        fun computation(name: String = "Calc", target: String = "../Total", operation: String = "1"): ObjectNode {
            val body = elements().addObject().put("type", "Computation").put("id", "/Policy/Calc").put("name", name)
                .putObject("Computation")
            body.put("computedFieldRelPath", target)
            body.putArray("computationAlternatives").addObject().put("operation", operation)
            body.putArray("errorMessage").addObject().put("locale", "en_US").put("text", "Check the total.")
            return body
        }

        fun typeDefinition(id: String, name: String) {
            (root.path("content") as ObjectNode).putArray("typeDefinitions").addObject().put("id", id)
                .put("name", name).put("revision", "1").putObject("fieldType").put("type", "StringType")
                .putObject("StringType")
        }
    }

    private companion object {
        const val UNBUILT = "CK_MODEL_NOT_BUILDABLE"

        fun ObjectNode.deleting(filtered: Boolean) {
            if (filtered) putObject("variantFilter").put("filterType", "ONLY").putArray("variants").add("premium")
        }

        fun ObjectNode.enumBody(): ObjectNode = path("fieldType").path("EnumerationType") as ObjectNode

        /** Each witness breaks the base in one way, and deletes or suppresses the declaration it breaks when filtered. */
        val WITNESSES: Map<String, (Model, Boolean) -> Unit> = linkedMapOf(
            "an empty type reference" to { m, f ->
                m.field("Code").apply {
                    putObject("fieldType").put("type", "TypeDefType").putObject("TypeDefType").put("typeDefinitionId", "")
                }.deleting(f)
            },
            "two categories of one name" to { m, f ->
                m.field("Status").apply { (enumBody().path("categories") as ArrayNode).let { it.add(it.get(0).deepCopy<ObjectNode>()) } }
                    .deleting(f)
            },
            "two categories of one name, different values" to { m, f ->
                m.field("Status").apply {
                    val categories = enumBody().path("categories") as ArrayNode
                    categories.add(categories.get(0).deepCopy<ObjectNode>().also { (it.path("values") as ArrayNode).removeAll().add("B").add("A") })
                }.deleting(f)
            },
            "an enumeration value twice" to { m, f ->
                m.field("Status").apply { (enumBody().path("values") as ArrayNode).let { it.add(it.get(0).deepCopy<ObjectNode>()) } }
                    .deleting(f)
            },
            "two hint-list entries for one locale" to { m, f ->
                m.field("Code").apply {
                    val hints = withObject("/fieldType/StringType").putArray("hintList")
                    hints.addObject().put("locale", "en_US").putArray("values").add("AB")
                    hints.addObject().put("locale", "en_US").putArray("values").add("CD")
                }.deleting(f)
            },
            "an empty DateFragment format" to { m, f ->
                m.field("Month").apply { withObject("/fieldType/DateFragmentType").put("formatOfFragment", "") }.deleting(f)
            },
            "a field with an empty name" to { m, f -> m.element("Code").put("name", ""); m.elements().last { it.path("name").asText() == "" }.path("Field").let { (it as ObjectNode).deleting(f) } },
            "a field with a space for a name" to { m, f -> m.element("Code").put("name", " "); m.elements().last { it.path("name").asText() == " " }.path("Field").let { (it as ObjectNode).deleting(f) } },
            "a field with an empty id" to { m, f -> m.element("Code").put("id", "").path("Field").let { (it as ObjectNode).deleting(f) } },
            "a rule with an empty name" to { m, f -> m.rule().deleting(f); m.elements().single { it.path("type").asText() == "Rule" }.let { (it as ObjectNode).put("name", "") } },
            "a rule with an empty condition" to { m, f -> m.rule().put("errorCondition", "").deleting(f) },
            "a rule with a space for a condition" to { m, f -> m.rule().put("errorCondition", " ").deleting(f) },
            "a rule with an empty error target" to { m, f -> m.rule().put("errorEntityRelPath", "").deleting(f) },
            "a rule with a space for an error target" to { m, f -> m.rule().put("errorEntityRelPath", " ").deleting(f) },
            "a computation with an empty name" to { m, f -> m.computation(name = "").deleting(f) },
            "a computation with an empty target" to { m, f -> m.computation(target = "").deleting(f) },
            "a computation with an empty operation" to { m, f -> m.computation(operation = "").deleting(f) },
            // A type definition carries no variant filter, so its filtered row deletes nothing and answers alike.
            "a type definition with an empty id" to { m, _ -> m.typeDefinition("", "CodeType") },
            "a type definition with a space for an id" to { m, _ -> m.typeDefinition(" ", "CodeType") },
            "a type definition with an empty name" to { m, _ -> m.typeDefinition("CodeType", "") },
        )

        /** Measured through `BoundModel`, kernel 30.8.1, 2026-10-27. */
        val A12: Map<String, Set<String>> = linkedMapOf(
            "an empty type reference/kept" to setOf(UNBUILT),
            "an empty type reference/ONLY" to setOf("RK_TYPE_REFERENCE_EMPTY"),
            "two categories of one name/kept" to setOf(UNBUILT),
            "two categories of one name/ONLY" to setOf("RK_ENUM_CATEGORY_DUPLICATE"),
            "two categories of one name, different values/kept" to setOf(UNBUILT),
            "two categories of one name, different values/ONLY" to setOf("RK_ENUM_CATEGORY_DUPLICATE"),
            "an enumeration value twice/kept" to setOf("MVK_MISSING_CATEGORY_VALUE", "MVK_CATEGORY_VALUE_DUPLICATE",
                "MVK_INTERNAL_VALUES_DUPLICATE", "RK_ENUM_VALUE_DUPLICATE"),
            "an enumeration value twice/ONLY" to setOf("RK_ENUM_VALUE_DUPLICATE"),
            "two hint-list entries for one locale/kept" to setOf(UNBUILT),
            "two hint-list entries for one locale/ONLY" to emptySet(),
            "an empty DateFragment format/kept" to setOf(UNBUILT),
            "an empty DateFragment format/ONLY" to emptySet(),
            "a field with an empty name/kept" to setOf(UNBUILT),
            "a field with an empty name/ONLY" to setOf("RK_NAME_EMPTY"),
            "a field with a space for a name/kept" to setOf(UNBUILT),
            "a field with a space for a name/ONLY" to emptySet(),
            "a field with an empty id/kept" to setOf("RK_ID_EMPTY"),
            "a field with an empty id/ONLY" to setOf("RK_ID_EMPTY"),
            "a rule with an empty name/kept" to setOf(UNBUILT),
            "a rule with an empty name/ONLY" to setOf("RK_NAME_EMPTY"),
            "a rule with an empty condition/kept" to setOf("MVK_CONDITION_MISSING", "MVK_INCOMPLETE_INPUT", "RK_ERROR_CONDITION_EMPTY"),
            "a rule with an empty condition/ONLY" to setOf("RK_ERROR_CONDITION_EMPTY"),
            "a rule with a space for a condition/kept" to setOf("MVK_CONDITION_MISSING", "MVK_INCOMPLETE_INPUT"),
            "a rule with a space for a condition/ONLY" to emptySet(),
            "a rule with an empty error target/kept" to setOf("MVK_ERROR_FIELD_MISSING", "MVK_MISSING_ERROR_FIELD", "RK_ERROR_ENTITY_EMPTY"),
            "a rule with an empty error target/ONLY" to setOf("RK_ERROR_ENTITY_EMPTY"),
            "a rule with a space for an error target/kept" to setOf("MVK_ERROR_FIELD_MISSING", "MVK_MISSING_ERROR_FIELD"),
            "a rule with a space for an error target/ONLY" to emptySet(),
            "a computation with an empty name/kept" to setOf(UNBUILT),
            "a computation with an empty name/ONLY" to setOf("RK_NAME_EMPTY"),
            "a computation with an empty target/kept" to setOf(UNBUILT),
            "a computation with an empty target/ONLY" to setOf("RK_COMPUTED_FIELD_EMPTY"),
            "a computation with an empty operation/kept" to setOf("MVK_INCOMPLETE_INPUT", "RK_COMPUTATION_OPERATION_EMPTY"),
            "a computation with an empty operation/ONLY" to setOf("RK_COMPUTATION_OPERATION_EMPTY"),
            "a type definition with an empty id/kept" to setOf("RK_ID_EMPTY"),
            "a type definition with an empty id/ONLY" to setOf("RK_ID_EMPTY"),
            "a type definition with a space for an id/kept" to emptySet(),
            "a type definition with a space for an id/ONLY" to emptySet(),
            "a type definition with an empty name/kept" to setOf("RK_NAME_EMPTY"),
            "a type definition with an empty name/ONLY" to setOf("RK_NAME_EMPTY"),
        )
    }
}
