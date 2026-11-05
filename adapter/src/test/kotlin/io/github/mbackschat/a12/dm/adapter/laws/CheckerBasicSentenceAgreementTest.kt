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
 * The checker against A12 — the basic sentences and the build failures beside them, each defect once
 * (KERNEL-FINDINGS KF626, a12-kernel-lean SPEC-2026-10-27-01).
 *
 * A12's `BasicConsistencyCheck` is its last consistency rule and runs over the model as read. It tests
 * `StringUtils.isEmpty`, which is the discriminator against "blank": a name, condition, error target or
 * type-definition id of one space draws nothing.
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
    fun theCheckerStatesWhatA12States() {
        val actual = WITNESSES.mapValues { (_, mutate) ->
            val root = mapper.readTree(base) as ObjectNode
            mutate(Model(root))
            val json = root.toString()
            val a12 = kernelModelClasses(json)
            val ours = check(ModelSource(json)).findings.filter { it.justifiesRejection }.mapTo(sortedSetOf()) { it.code }
            a12.toSortedSet() to verdict(a12, ours)
        }
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

        fun ObjectNode.enumBody(): ObjectNode = path("fieldType").path("EnumerationType") as ObjectNode

        /** Each witness breaks the base in one way. */
        val WITNESSES: Map<String, (Model) -> Unit> = linkedMapOf(
            "an empty type reference" to { m ->
                m.field("Code").apply {
                    putObject("fieldType").put("type", "TypeDefType").putObject("TypeDefType").put("typeDefinitionId", "")
                }
            },
            "two categories of one name" to { m ->
                m.field("Status").apply { (enumBody().path("categories") as ArrayNode).let { it.add(it.get(0).deepCopy<ObjectNode>()) } }
                    
            },
            "two categories of one name, different values" to { m ->
                m.field("Status").apply {
                    val categories = enumBody().path("categories") as ArrayNode
                    categories.add(categories.get(0).deepCopy<ObjectNode>().also { (it.path("values") as ArrayNode).removeAll().add("B").add("A") })
                }
            },
            "an enumeration value twice" to { m ->
                m.field("Status").apply { (enumBody().path("values") as ArrayNode).let { it.add(it.get(0).deepCopy<ObjectNode>()) } }
                    
            },
            "two hint-list entries for one locale" to { m ->
                m.field("Code").apply {
                    val hints = withObject("/fieldType/StringType").putArray("hintList")
                    hints.addObject().put("locale", "en_US").putArray("values").add("AB")
                    hints.addObject().put("locale", "en_US").putArray("values").add("CD")
                }
            },
            "an empty DateFragment format" to { m ->
                m.field("Month").apply { withObject("/fieldType/DateFragmentType").put("formatOfFragment", "") }
            },
            "a DateFragment body without its format" to { m ->
                m.field("Month").apply { withObject("/fieldType/DateFragmentType").remove("formatOfFragment") }
            },
            "a DateFragment type with no body" to { m ->
                m.field("Month").apply { (path("fieldType") as ObjectNode).remove("DateFragmentType") }
            },
            "a field with an empty name" to { m -> m.element("Code").put("name", "") },
            "a field with a space for a name" to { m -> m.element("Code").put("name", " ") },
            "a field with an empty id" to { m -> m.element("Code").put("id", "") },
            "a field with a space for an id" to { m -> m.element("Code").put("id", " ") },
            "a rule with an empty name" to { m -> m.elements().single { it.path("type").asText() == "Rule" }.let { (it as ObjectNode).put("name", "") } },
            "a rule with an empty condition" to { m -> m.rule().put("errorCondition", "") },
            "a rule with a space for a condition" to { m -> m.rule().put("errorCondition", " ") },
            "a rule with an empty error target" to { m -> m.rule().put("errorEntityRelPath", "") },
            "a rule with a space for an error target" to { m -> m.rule().put("errorEntityRelPath", " ") },
            "a computation with an empty name" to { m -> m.computation(name = "") },
            "a computation with an empty target" to { m -> m.computation(target = "") },
            "a computation with an empty operation" to { m -> m.computation(operation = "") },
            "a computation whose second alternative has a null operation" to { m ->
                m.computation { a ->
                    a.addObject().put("precondition", "FieldFilled(Ref)").put("operation", "1")
                    a.addObject().put("precondition", "FieldFilled(Code)").putNull("operation")
                }
            },
            "a computation with a null operation" to { m -> m.computation { it.addObject().putNull("operation") } },
            "a computation with no operation" to { m -> m.computation { it.addObject() } },
            "a computation whose target climbs past the root" to { m -> m.computation(target = "../../../Total") },
            "a computation with a slash-led target" to { m -> m.computation(target = "/Policy/Total") },
            "a computation with an unresolvable target" to { m -> m.computation(target = "../NoSuchField") },
            "a computation with a group target" to { m -> m.computation(target = "../Items") },
            "a rule with no condition" to { m -> m.rule().apply { remove("errorCondition") } },
            "a rule with a slash-led error target" to { m -> m.rule().put("errorEntityRelPath", "/Policy/Ref") },
            "an empty hint-list entry beside a filled one for one locale" to { m ->
                m.field("Code").apply {
                    val hints = withObject("/fieldType/StringType").putArray("hintList")
                    hints.addObject().put("locale", "en_US").putArray("values")
                    hints.addObject().put("locale", "en_US").putArray("values").add("AB")
                }
            },
            "hint-list entries for one locale in two spellings" to { m ->
                m.field("Code").apply {
                    val hints = withObject("/fieldType/StringType").putArray("hintList")
                    hints.addObject().put("locale", "en_US").putArray("values").add("AB")
                    hints.addObject().put("locale", "en-US").putArray("values").add("CD")
                }
            },
            "two categories of one name, disjoint values" to { m ->
                m.field("Status").apply {
                    val categories = enumBody().path("categories") as ArrayNode
                    categories.add(categories.get(0).deepCopy<ObjectNode>().also { (it.path("values") as ArrayNode).removeAll().add("B") })
                    (categories.get(0).path("values") as ArrayNode).removeAll().add("A")
                }
            },
            "a type definition with an empty id" to { m -> m.typeDefinition("", "CodeType") },
            "a type definition with a space for an id" to { m -> m.typeDefinition(" ", "CodeType") },
            "a type definition with an empty name" to { m -> m.typeDefinition("CodeType", "") },
            "a field whose name is an object" to { m -> m.element("Code").putObject("name") },
            "a field whose name is an array" to { m -> m.element("Code").putArray("name") },
            "a field with no name" to { m -> m.element("Code").remove("name") },
            "a rule whose name is an object" to { m -> (m.elements().single { it.path("type").asText() == "Rule" } as ObjectNode).putObject("name") },
            "a type definition whose name is an object" to { m -> m.typeDefinition("CodeType", "x"); ((m.root.path("content").path("typeDefinitions").get(0)) as ObjectNode).putObject("name") },
            "a rule whose name is an array" to { m -> (m.elements().single { it.path("type").asText() == "Rule" } as ObjectNode).putArray("name") },
            "a rule with no name" to { m -> (m.elements().single { it.path("type").asText() == "Rule" } as ObjectNode).remove("name") },
            "a computation whose name is an object" to { m -> m.computation(); (m.elements().last() as ObjectNode).putObject("name") },
            "a type definition whose name is an array" to { m -> m.typeDefinition("CodeType", "x"); ((m.root.path("content").path("typeDefinitions").get(0)) as ObjectNode).putArray("name") },
            "a type definition with no name" to { m -> m.typeDefinition("CodeType", "x"); ((m.root.path("content").path("typeDefinitions").get(0)) as ObjectNode).remove("name") },
            "a type definition whose name is a number" to { m -> m.typeDefinition("CodeType", "x"); ((m.root.path("content").path("typeDefinitions").get(0)) as ObjectNode).put("name", 7) },
            "a type definition whose name is null" to { m -> m.typeDefinition("CodeType", "x"); ((m.root.path("content").path("typeDefinitions").get(0)) as ObjectNode).putNull("name") },
            "a computation with no name" to { m -> m.computation(); (m.elements().last() as ObjectNode).remove("name") },
            "a group with no name" to { m -> m.element("Items").remove("name") },
            "a field whose name is a number" to { m -> m.element("Code").put("name", 7) },
            "a field whose name is a boolean" to { m -> m.element("Code").put("name", true) },
            "a field whose name is null" to { m -> m.element("Code").putNull("name") },
            "an enumeration value that is a number" to { m ->
                m.field("Status").apply { (enumBody().path("values").get(1) as ObjectNode).put("value", 7) }
            },
        )

        /**
         * Measured through `BoundModel`, kernel 31.1.1, 2026-11-02. The twelve rows KF652 moved were unbuilt on
         * 30.8.1 (a null field name was admitted there); the converter now states each as a sentence.
         */
        val A12: Map<String, Set<String>> = linkedMapOf(
            "an empty type reference" to setOf("RK_TYPE_DEFINITION_NOT_FOUND", "RK_TYPE_REFERENCE_EMPTY"),
            "two categories of one name" to setOf(UNBUILT),
            "two categories of one name, different values" to setOf(UNBUILT),
            "an enumeration value twice" to setOf("MVK_MISSING_CATEGORY_VALUE", "MVK_CATEGORY_VALUE_DUPLICATE",
                "MVK_INTERNAL_VALUES_DUPLICATE", "RK_ENUM_VALUE_DUPLICATE"),
            "two hint-list entries for one locale" to setOf(UNBUILT),
            "an empty DateFragment format" to setOf(UNBUILT),
            // SPEC-2026-11-04-03 (`tb-frag-nobody`): an absent format leaves no model as the empty one does.
            "a DateFragment body without its format" to setOf(UNBUILT),
            "a DateFragment type with no body" to setOf(UNBUILT),
            "a field with an empty name" to setOf("RK_ELEMENT_NAME_MISSING", "RK_NAME_EMPTY"),
            "a field with a space for a name" to setOf("RK_ELEMENT_NAME_MISSING"),
            "a field with an empty id" to setOf("RK_ID_EMPTY"),
            "a field with a space for an id" to emptySet(),
            "a rule with an empty name" to setOf("RK_ELEMENT_NAME_MISSING", "RK_NAME_EMPTY"),
            "a rule with an empty condition" to setOf("MVK_CONDITION_MISSING", "MVK_INCOMPLETE_INPUT", "RK_ERROR_CONDITION_EMPTY"),
            "a rule with a space for a condition" to setOf("MVK_CONDITION_MISSING", "MVK_INCOMPLETE_INPUT"),
            "a rule with an empty error target" to setOf("MVK_ERROR_FIELD_MISSING", "MVK_MISSING_ERROR_FIELD", "RK_ERROR_ENTITY_EMPTY"),
            "a rule with a space for an error target" to setOf("MVK_ERROR_FIELD_MISSING", "MVK_MISSING_ERROR_FIELD"),
            "a computation with an empty name" to setOf("RK_ELEMENT_NAME_MISSING", "RK_NAME_EMPTY"),
            "a computation with an empty target" to setOf("RK_COMPUTED_FIELD_NOT_SPECIFIED", "RK_COMPUTED_FIELD_EMPTY"),
            "a computation with an empty operation" to setOf("MVK_INCOMPLETE_INPUT", "RK_COMPUTATION_OPERATION_EMPTY"),
            // `StringUtils.isEmpty` is true of a missing operation too, and the token error is the null operation's
            // own (KF643).
            "a computation whose second alternative has a null operation" to
                setOf("MVK_UNEXPECTED_TOKEN", "RK_COMPUTATION_OPERATION_EMPTY"),
            "a computation with a null operation" to setOf("MVK_INCOMPLETE_INPUT", "RK_COMPUTATION_OPERATION_EMPTY"),
            "a computation with no operation" to setOf("MVK_INCOMPLETE_INPUT", "RK_COMPUTATION_OPERATION_EMPTY"),
            // The reader's path arithmetic refuses these; a target that only names nothing fails at conversion (KF643).
            "a computation whose target climbs past the root" to setOf(UNBUILT),
            "a computation with a slash-led target" to setOf(UNBUILT),
            "a computation with an unresolvable target" to setOf("RK_COMPUTED_FIELD_NOT_FOUND"),
            "a computation with a group target" to setOf(UNBUILT),
            "a rule with no condition" to setOf(UNBUILT),
            "a rule with a slash-led error target" to setOf(UNBUILT),
            // The converter drops an entry without values before keying the list by the PARSED locale (KF643).
            "an empty hint-list entry beside a filled one for one locale" to emptySet(),
            "hint-list entries for one locale in two spellings" to setOf(UNBUILT),
            "two categories of one name, disjoint values" to setOf(UNBUILT),
            "a type definition with an empty id" to setOf("RK_ID_EMPTY"),
            "a type definition with a space for an id" to emptySet(),
            "a type definition with an empty name" to setOf("RK_NAME_EMPTY"),
            // A non-scalar name is read as no name: the declaration fails at conversion, and the sentence states it
            // empty. An absent field name, and a type definition's, fail the reader.
            "a field whose name is an object" to setOf(UNBUILT),
            "a field whose name is an array" to setOf(UNBUILT),
            "a field with no name" to setOf("RK_ELEMENT_NAME_MISSING", "RK_NAME_EMPTY"),
            "a rule whose name is an object" to setOf(UNBUILT),
            "a type definition whose name is an object" to setOf(UNBUILT),
            "a rule whose name is an array" to setOf(UNBUILT),
            "a rule with no name" to setOf("RK_ELEMENT_NAME_MISSING", "RK_NAME_EMPTY"),
            "a computation whose name is an object" to setOf(UNBUILT),
            "a type definition whose name is an array" to setOf(UNBUILT),
            "a type definition with no name" to setOf("RK_NAME_EMPTY"),
            "a type definition whose name is a number" to emptySet(),
            "a type definition whose name is null" to setOf(UNBUILT),
            "a computation with no name" to setOf("RK_ELEMENT_NAME_MISSING", "RK_NAME_EMPTY"),
            "a group with no name" to setOf("RK_ELEMENT_NAME_MISSING", "RK_NAME_EMPTY"),
            // A scalar member is read as its text, so a number names an element and a value as a string does;
            // `null` and `true` are read as admitted names too (measured 2026-10-27).
            "a field whose name is a number" to setOf("MVK_NAME_STARTS_WITH_DIGIT_A12_DV", "MVK_PATH_INVALID"),
            "a field whose name is a boolean" to emptySet(),
            "a field whose name is null" to setOf("RK_ELEMENT_NAME_MISSING", "RK_NAME_EMPTY"),
            "an enumeration value that is a number" to emptySet(),
        )
    }
}
