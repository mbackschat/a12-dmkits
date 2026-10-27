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
 * A `name` written as something other than text is the reader's to judge (KERNEL-FINDINGS KF644): an element's
 * object or array name behaves as the empty one, and an absent element name or a type definition's `null`, object
 * or array name leaves no model.
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

        fun computation(name: String = "Calc", target: String = "../Total", operation: String = "1"): ObjectNode =
            computation(name, target) { it.addObject().put("operation", operation) }

        fun computation(name: String = "Calc", target: String = "../Total", alternatives: (ArrayNode) -> Unit): ObjectNode {
            val body = elements().addObject().put("type", "Computation").put("id", "/Policy/Calc").put("name", name)
                .putObject("Computation")
            body.put("computedFieldRelPath", target)
            alternatives(body.putArray("computationAlternatives"))
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
            "a field with a space for an id" to { m, f -> m.element("Code").put("id", " ").path("Field").let { (it as ObjectNode).deleting(f) } },
            "a rule with an empty name" to { m, f -> m.rule().deleting(f); m.elements().single { it.path("type").asText() == "Rule" }.let { (it as ObjectNode).put("name", "") } },
            "a rule with an empty condition" to { m, f -> m.rule().put("errorCondition", "").deleting(f) },
            "a rule with a space for a condition" to { m, f -> m.rule().put("errorCondition", " ").deleting(f) },
            "a rule with an empty error target" to { m, f -> m.rule().put("errorEntityRelPath", "").deleting(f) },
            "a rule with a space for an error target" to { m, f -> m.rule().put("errorEntityRelPath", " ").deleting(f) },
            "a computation with an empty name" to { m, f -> m.computation(name = "").deleting(f) },
            "a computation with an empty target" to { m, f -> m.computation(target = "").deleting(f) },
            "a computation with an empty operation" to { m, f -> m.computation(operation = "").deleting(f) },
            "a computation whose second alternative has a null operation" to { m, f ->
                m.computation { a ->
                    a.addObject().put("precondition", "FieldFilled(Ref)").put("operation", "1")
                    a.addObject().put("precondition", "FieldFilled(Code)").putNull("operation")
                }.deleting(f)
            },
            "a computation with a null operation" to { m, f -> m.computation { it.addObject().putNull("operation") }.deleting(f) },
            "a computation with no operation" to { m, f -> m.computation { it.addObject() }.deleting(f) },
            "a computation whose target climbs past the root" to { m, f -> m.computation(target = "../../../Total").deleting(f) },
            "a computation with a slash-led target" to { m, f -> m.computation(target = "/Policy/Total").deleting(f) },
            "a computation with an unresolvable target" to { m, f -> m.computation(target = "../NoSuchField").deleting(f) },
            "a computation with a group target" to { m, f -> m.computation(target = "../Items").deleting(f) },
            "a rule with no condition" to { m, f -> m.rule().apply { remove("errorCondition") }.deleting(f) },
            "a rule with a slash-led error target" to { m, f -> m.rule().put("errorEntityRelPath", "/Policy/Ref").deleting(f) },
            "an empty hint-list entry beside a filled one for one locale" to { m, f ->
                m.field("Code").apply {
                    val hints = withObject("/fieldType/StringType").putArray("hintList")
                    hints.addObject().put("locale", "en_US").putArray("values")
                    hints.addObject().put("locale", "en_US").putArray("values").add("AB")
                }.deleting(f)
            },
            "hint-list entries for one locale in two spellings" to { m, f ->
                m.field("Code").apply {
                    val hints = withObject("/fieldType/StringType").putArray("hintList")
                    hints.addObject().put("locale", "en_US").putArray("values").add("AB")
                    hints.addObject().put("locale", "en-US").putArray("values").add("CD")
                }.deleting(f)
            },
            "two categories of one name, disjoint values" to { m, f ->
                m.field("Status").apply {
                    val categories = enumBody().path("categories") as ArrayNode
                    categories.add(categories.get(0).deepCopy<ObjectNode>().also { (it.path("values") as ArrayNode).removeAll().add("B") })
                    (categories.get(0).path("values") as ArrayNode).removeAll().add("A")
                }.deleting(f)
            },
            // A type definition carries no variant filter, so its filtered row deletes nothing and answers alike.
            "a type definition with an empty id" to { m, _ -> m.typeDefinition("", "CodeType") },
            "a type definition with a space for an id" to { m, _ -> m.typeDefinition(" ", "CodeType") },
            "a type definition with an empty name" to { m, _ -> m.typeDefinition("CodeType", "") },
            "a field whose name is an object" to { m, f -> m.element("Code").apply { (path("Field") as ObjectNode).deleting(f) }.putObject("name") },
            "a field whose name is an array" to { m, f -> m.element("Code").apply { (path("Field") as ObjectNode).deleting(f) }.putArray("name") },
            "a field with no name" to { m, f -> m.element("Code").apply { (path("Field") as ObjectNode).deleting(f) }.remove("name") },
            "a rule whose name is an object" to { m, f -> m.rule().deleting(f); (m.elements().single { it.path("type").asText() == "Rule" } as ObjectNode).putObject("name") },
            "a type definition whose name is an object" to { m, _ -> m.typeDefinition("CodeType", "x"); ((m.root.path("content").path("typeDefinitions").get(0)) as ObjectNode).putObject("name") },
            "a rule whose name is an array" to { m, f -> m.rule().deleting(f); (m.elements().single { it.path("type").asText() == "Rule" } as ObjectNode).putArray("name") },
            "a rule with no name" to { m, f -> m.rule().deleting(f); (m.elements().single { it.path("type").asText() == "Rule" } as ObjectNode).remove("name") },
            "a computation whose name is an object" to { m, f -> m.computation().deleting(f); (m.elements().last() as ObjectNode).putObject("name") },
            "a type definition whose name is an array" to { m, _ -> m.typeDefinition("CodeType", "x"); ((m.root.path("content").path("typeDefinitions").get(0)) as ObjectNode).putArray("name") },
            "a type definition with no name" to { m, _ -> m.typeDefinition("CodeType", "x"); ((m.root.path("content").path("typeDefinitions").get(0)) as ObjectNode).remove("name") },
            "a type definition whose name is a number" to { m, _ -> m.typeDefinition("CodeType", "x"); ((m.root.path("content").path("typeDefinitions").get(0)) as ObjectNode).put("name", 7) },
            "a type definition whose name is null" to { m, _ -> m.typeDefinition("CodeType", "x"); ((m.root.path("content").path("typeDefinitions").get(0)) as ObjectNode).putNull("name") },
            "a computation with no name" to { m, f -> m.computation().deleting(f); (m.elements().last() as ObjectNode).remove("name") },
            "a group with no name" to { m, f -> (m.element("Items").also { (it.path("Group") as ObjectNode).deleting(f) }).remove("name") },
            "a field whose name is a number" to { m, f -> m.element("Code").apply { (path("Field") as ObjectNode).deleting(f) }.put("name", 7) },
            "a field whose name is a boolean" to { m, f -> m.element("Code").apply { (path("Field") as ObjectNode).deleting(f) }.put("name", true) },
            "a field whose name is null" to { m, f -> m.element("Code").apply { (path("Field") as ObjectNode).deleting(f) }.putNull("name") },
            "an enumeration value that is a number" to { m, f ->
                m.field("Status").apply { (enumBody().path("values").get(1) as ObjectNode).put("value", 7) }.deleting(f)
            },
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
            "a field with a space for an id/kept" to emptySet(),
            "a field with a space for an id/ONLY" to emptySet(),
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
            // `StringUtils.isEmpty` is true of a missing operation too, and the token error is the null operation's
            // own; the sentence survives the filter as the empty one does (KF643).
            "a computation whose second alternative has a null operation/kept" to
                setOf("MVK_UNEXPECTED_TOKEN", "RK_COMPUTATION_OPERATION_EMPTY"),
            "a computation whose second alternative has a null operation/ONLY" to setOf("RK_COMPUTATION_OPERATION_EMPTY"),
            "a computation with a null operation/kept" to setOf("MVK_INCOMPLETE_INPUT", "RK_COMPUTATION_OPERATION_EMPTY"),
            "a computation with a null operation/ONLY" to setOf("RK_COMPUTATION_OPERATION_EMPTY"),
            "a computation with no operation/kept" to setOf("MVK_INCOMPLETE_INPUT", "RK_COMPUTATION_OPERATION_EMPTY"),
            "a computation with no operation/ONLY" to setOf("RK_COMPUTATION_OPERATION_EMPTY"),
            // The reader's path arithmetic refuses these before any filter applies; a target that only names
            // nothing fails at conversion, which the filter lifts (KF643).
            "a computation whose target climbs past the root/kept" to setOf(UNBUILT),
            "a computation whose target climbs past the root/ONLY" to setOf(UNBUILT),
            "a computation with a slash-led target/kept" to setOf(UNBUILT),
            "a computation with a slash-led target/ONLY" to setOf(UNBUILT),
            "a computation with an unresolvable target/kept" to setOf(UNBUILT),
            "a computation with an unresolvable target/ONLY" to emptySet(),
            "a computation with a group target/kept" to setOf(UNBUILT),
            "a computation with a group target/ONLY" to setOf(UNBUILT),
            "a rule with no condition/kept" to setOf(UNBUILT),
            "a rule with no condition/ONLY" to setOf("RK_ERROR_CONDITION_EMPTY"),
            "a rule with a slash-led error target/kept" to setOf(UNBUILT),
            "a rule with a slash-led error target/ONLY" to setOf(UNBUILT),
            // The converter drops an entry without values before keying the list by the PARSED locale (KF643).
            "an empty hint-list entry beside a filled one for one locale/kept" to emptySet(),
            "an empty hint-list entry beside a filled one for one locale/ONLY" to emptySet(),
            "hint-list entries for one locale in two spellings/kept" to setOf(UNBUILT),
            "hint-list entries for one locale in two spellings/ONLY" to emptySet(),
            "two categories of one name, disjoint values/kept" to setOf(UNBUILT),
            "two categories of one name, disjoint values/ONLY" to setOf("RK_ENUM_CATEGORY_DUPLICATE"),
            "a type definition with an empty id/kept" to setOf("RK_ID_EMPTY"),
            "a type definition with an empty id/ONLY" to setOf("RK_ID_EMPTY"),
            "a type definition with a space for an id/kept" to emptySet(),
            "a type definition with a space for an id/ONLY" to emptySet(),
            "a type definition with an empty name/kept" to setOf("RK_NAME_EMPTY"),
            "a type definition with an empty name/ONLY" to setOf("RK_NAME_EMPTY"),
            // A non-scalar name is read as no name: the declaration fails at conversion, which the filter lifts,
            // and the sentence states it empty. An absent field name, and a type definition's, fail the reader.
            "a field whose name is an object/kept" to setOf(UNBUILT),
            "a field whose name is an object/ONLY" to setOf("RK_NAME_EMPTY"),
            "a field whose name is an array/kept" to setOf(UNBUILT),
            "a field whose name is an array/ONLY" to setOf("RK_NAME_EMPTY"),
            "a field with no name/kept" to setOf(UNBUILT),
            "a field with no name/ONLY" to setOf(UNBUILT),
            "a rule whose name is an object/kept" to setOf(UNBUILT),
            "a rule whose name is an object/ONLY" to setOf("RK_NAME_EMPTY"),
            "a type definition whose name is an object/kept" to setOf(UNBUILT),
            "a type definition whose name is an object/ONLY" to setOf(UNBUILT),
            "a rule whose name is an array/kept" to setOf(UNBUILT),
            "a rule whose name is an array/ONLY" to setOf("RK_NAME_EMPTY"),
            "a rule with no name/kept" to setOf(UNBUILT),
            "a rule with no name/ONLY" to setOf(UNBUILT),
            "a computation whose name is an object/kept" to setOf(UNBUILT),
            "a computation whose name is an object/ONLY" to setOf("RK_NAME_EMPTY"),
            "a type definition whose name is an array/kept" to setOf(UNBUILT),
            "a type definition whose name is an array/ONLY" to setOf(UNBUILT),
            "a type definition with no name/kept" to setOf("RK_NAME_EMPTY"),
            "a type definition with no name/ONLY" to setOf("RK_NAME_EMPTY"),
            "a type definition whose name is a number/kept" to emptySet(),
            "a type definition whose name is a number/ONLY" to emptySet(),
            "a type definition whose name is null/kept" to setOf(UNBUILT),
            "a type definition whose name is null/ONLY" to setOf(UNBUILT),
            "a computation with no name/kept" to setOf(UNBUILT),
            "a computation with no name/ONLY" to setOf(UNBUILT),
            "a group with no name/kept" to setOf(UNBUILT),
            "a group with no name/ONLY" to setOf(UNBUILT),
            // A scalar member is read as its text, so a number names an element and a value as a string does;
            // `null` and `true` are read as admitted names too (measured 2026-10-27).
            "a field whose name is a number/kept" to setOf("MVK_NAME_STARTS_WITH_DIGIT_A12_DV", "MVK_PATH_INVALID"),
            "a field whose name is a number/ONLY" to emptySet(),
            "a field whose name is a boolean/kept" to emptySet(),
            "a field whose name is a boolean/ONLY" to emptySet(),
            "a field whose name is null/kept" to emptySet(),
            "a field whose name is null/ONLY" to emptySet(),
            "an enumeration value that is a number/kept" to emptySet(),
            "an enumeration value that is a number/ONLY" to emptySet(),
        )
    }
}
