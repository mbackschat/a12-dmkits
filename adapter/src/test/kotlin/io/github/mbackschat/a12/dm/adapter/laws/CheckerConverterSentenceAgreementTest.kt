package io.github.mbackschat.a12.dm.adapter.laws

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.databind.node.ObjectNode
import io.github.mbackschat.a12.dm.consistency.ModelSource
import io.github.mbackschat.a12.dm.consistency.check
import io.github.mbackschat.a12.dm.core.ModelConsistencyException
import io.github.mbackschat.a12.dm.core.ModelInputException
import io.github.mbackschat.a12.dm.core.ModelLoader
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * The checker against A12 on the defects its validation-model converter reports (KERNEL-FINDINGS KF652).
 *
 * Kernel 30.8.1 threw on each of these, so the model was not built. 31.1.1 states a codeless sentence and goes on
 * without the declaration. The rows tell that account apart from three rivals:
 *
 * - "a converter sentence ends the check": a rule naming no field elsewhere is still refused;
 * - "only the named declaration is dropped": a rule inside a dropped group is not judged;
 * - "a missing type definition drops its field": a rule naming that field is admitted, because A12 keeps the
 *   field as a plain String.
 *
 * Of two children with one name, the first is kept and judged and the later one is dropped.
 *
 * A12's column is asserted, not copied: every row reads the Kernel gate and names a codeless sentence through
 * [legibleNotificationClass], so an unnamed one shows its words.
 *
 * Own domain (an insurance policy with a reference, a code, a total and its cover items).
 */
class CheckerConverterSentenceAgreementTest {

    private val mapper = ObjectMapper()

    private val base = DocumentModelDraft.named("policy-converter-sentences").locale("en_US")
        .rootGroup("Policy") { p -> p.stringField("Ref") { }.stringField("Code") { }
            .numberField("Total") { it.fractionDigits(0) }.numberField("Fee") { it.fractionDigits(0) }
            .group("Items") { g -> g.repeatable(3).stringField("Label") { } } }
        .build().toJson().let { RuntimeLaws.withRule(it, "/Policy", "RefCheck", "../Ref", "REF", "FieldFilled(Ref)") }

    @Test
    fun theCheckerStatesWhatA12States() {
        val actual = WITNESSES.mapValues { (_, build) ->
            val json = build(Witness(base))
            val ours = check(ModelSource(json)).findings.filter { it.justifiesRejection }.mapTo(sortedSetOf()) { it.code }
            a12Classes(json).let { it to verdict(it, ours) }
        }
        val expected = A12.mapValues { (_, classes) -> classes.toSortedSet() to verdict(classes, classes.toSortedSet()) }
        assertEquals(expected, actual, "as (A12's classes, the checker's)")
    }

    /**
     * How often each converter sentence is stated, which the class sets above cannot see. The converter reports
     * every dropped declaration, and A12 then keeps one sentence per text and source: a blank name is stated once
     * per holding group, and a shared name once per kind of later child. Each row discriminates against a rival
     * collapse: by text alone the two-groups row would be one, and by path alone the field-group-rule row would be.
     */
    @Test
    fun eachDroppedDeclarationIsStatedOnce() {
        val rows: Map<String, (Witness) -> String> = linkedMapOf(
            "three rules of one name" to { w ->
                w.rule(null, "Twin", "FieldFilled(Ref)", "../Ref").rule(null, "Twin", "FieldFilled(Ref)", "../Ref")
                    .rule(null, "Twin", "FieldFilled(Ref)", "../Ref").json()
            },
            "a field and two rules of its name" to { w ->
                w.rule(null, "Code", "FieldFilled(Ref)", "../Ref").rule(null, "Code", "FieldFilled(Ref)", "../Ref").json()
            },
            "a field, a group and a rule of one name" to { w ->
                w.rename("Items", "Code").rule(null, "Code", "FieldFilled(Ref)", "../Ref").json()
            },
            "two fields named with a space" to { w -> w.rename("Code", " ").rename("Ref", " ").json() },
            "a field and a rule named with a space" to { w ->
                w.rename("Code", " ").rule(null, " ", "FieldFilled(Ref)", "../Ref").json()
            },
            "a field named with a space in each of two groups" to { w ->
                w.rename("Code", " ").rename("Label", " ", w.element("Items")).json()
            },
        )
        val counted = rows.mapValues { (_, build) ->
            val json = build(Witness(base))
            val ours = check(ModelSource(json)).findings.filter { it.code in CONVERTER_CODES }.map { it.code }.sorted()
            a12Sentences(json) to ours
        }
        assertEquals(
            mapOf(
                "three rules of one name" to 1, "a field and two rules of its name" to 1,
                "a field, a group and a rule of one name" to 2,
            ).mapValues { List(it.value) { "RK_CHILD_NAME_DUPLICATE" } }.plus(
                mapOf(
                    "two fields named with a space" to 1, "a field and a rule named with a space" to 1,
                    "a field named with a space in each of two groups" to 2,
                ).mapValues { List(it.value) { "RK_ELEMENT_NAME_MISSING" } },
            ).mapValues { it.value to it.value },
            counted,
            "as (A12's converter sentences, the checker's)",
        )
    }

    /** A12's converter sentences, one entry per notification. */
    private fun a12Sentences(json: String): List<String> = try {
        ModelLoader.loadSelfContained(json)
        emptyList()
    } catch (rejected: ModelConsistencyException) {
        rejected.notifications().map { legibleNotificationClass(it.message().trim()) }.filter { it in CONVERTER_CODES }.sorted()
    }

    /** The checker's codes, or for a model A12 cannot read, whether the checker reports that too. */
    private fun verdict(a12: Set<String>, ours: Set<String>): String =
        if (a12 == setOf(UNBUILT)) "unbuilt, reported: ${UNBUILT in ours}" else ours.toString()

    /** The Kernel gate alone, as [kernelModelClasses] asks it, with an unnamed sentence spelled out. */
    private fun a12Classes(json: String): Set<String> = try {
        ModelLoader.loadSelfContained(json)
        sortedSetOf()
    } catch (unreadable: ModelInputException) {
        sortedSetOf(UNBUILT)
    } catch (rejected: ModelConsistencyException) {
        rejected.notifications().mapTo(sortedSetOf()) { notification ->
            val message = notification.message().trim()
            if (message.startsWith(NOT_BUILT)) UNBUILT else legibleNotificationClass(message)
        }
    }

    private inner class Witness(json: String) {
        private val root = mapper.readTree(json) as ObjectNode
        private var rules = 0

        fun rootGroup(): ObjectNode = root.path("content").path("modelRoot").path("rootGroups").get(0) as ObjectNode
        fun elements(group: ObjectNode = rootGroup()): ArrayNode = group.path("Group").path("elements") as ArrayNode
        fun element(name: String, group: ObjectNode = rootGroup()): ObjectNode =
            elements(group).single { it.path("name").asText() == name } as ObjectNode

        fun rename(name: String, to: String, group: ObjectNode = rootGroup()) = apply { element(name, group).put("name", to) }

        /** A rule appended after every element of [group] (the root group, or its child of that name). */
        fun rule(group: String?, name: String, condition: String, errorRelPath: String) = apply {
            val owner = group?.let { element(it) } ?: rootGroup()
            val ordinal = ++rules
            val rule = elements(owner).addObject().put("type", "Rule").put("id", "rule-$ordinal").put("name", name)
                .putObject("Rule").put("errorEntityRelPath", errorRelPath).put("errorCode", "PROBE$ordinal")
                .put("errorCondition", condition).put("severity", "ERROR")
            rule.putArray("errorMessage").addObject().put("locale", "en_US").put("text", "Check this.")
        }

        fun computation(target: String, operation: String, name: String = "Calc") = apply {
            val body = elements().addObject().put("type", "Computation").put("id", "computation-$name").put("name", name)
                .putObject("Computation")
            body.put("computedFieldRelPath", target)
            body.putArray("computationAlternatives").addObject().put("operation", operation)
            body.putArray("errorMessage").addObject().put("locale", "en_US").put("text", "Check the total.")
        }

        fun typedBy(field: String, typeDefinitionId: String) = apply {
            element(field).withObject("/Field").putObject("fieldType").put("type", "TypeDefType")
                .putObject("TypeDefType").put("typeDefinitionId", typeDefinitionId)
        }

        /** [field]'s Number body, for a defect in one of its members. */
        fun number(field: String): ObjectNode = element(field).withObject("/Field/fieldType/NumberType")

        /** The Number body of the root group's last element, the copy [duplicate] appends. */
        fun lastNumber(): ObjectNode = (elements().last() as ObjectNode).withObject("/Field/fieldType/NumberType")

        /** A second copy of [name] with its own id, appended after every other element of the root group. */
        fun duplicate(name: String) = apply {
            elements().add(element(name).deepCopy().put("id", "${element(name).path("id").asText()}-twin"))
        }

        /** The base without its one rule and with an empty root name, so each row adds back one thing. */
        fun emptyRootWithoutItsRule() = apply {
            elements().remove(elements().indexOfFirst { it.path("name").asText() == "RefCheck" })
            rootGroup().put("name", "")
        }

        /** A second root group holding a field and a rule naming it. */
        fun secondRoot(name: String) = apply {
            val other = (root.path("content").path("modelRoot").path("rootGroups") as ArrayNode).addObject()
                .put("type", "Group").put("id", "root-$name").put("name", name)
            other.putObject("Group").putArray("elements")
            elements(other).addObject().put("type", "Field").put("id", "$name-note").put("name", "Note")
                .putObject("Field").putObject("fieldType").put("type", "StringType")
            val rule = elements(other).addObject().put("type", "Rule").put("id", "$name-rule").put("name", "NoteCheck")
                .putObject("Rule").put("errorEntityRelPath", "../Note").put("errorCode", "NOTE")
                .put("errorCondition", "FieldFilled(Note)").put("severity", "ERROR")
            rule.putArray("errorMessage").addObject().put("locale", "en_US").put("text", "Check this.")
        }

        fun json(): String = root.toString()
    }

    private companion object {
        const val UNBUILT = "CK_MODEL_NOT_BUILDABLE"
        const val NO_FIELD = "FieldFilled(NoSuchFieldAnywhere)"

        val CONVERTER_CODES = setOf("RK_ROOT_GROUP_NAME_MISSING", "RK_ELEMENT_NAME_MISSING", "RK_CHILD_NAME_DUPLICATE",
            "RK_COMPUTED_FIELD_NOT_SPECIFIED", "RK_COMPUTED_FIELD_NOT_FOUND", "RK_TYPE_DEFINITION_NOT_FOUND")

        /** How the adapter words a kernel that raised while building, rather than stating a sentence. */
        const val NOT_BUILT = "the kernel could not build this model"

        /** Each witness breaks the base in one way, and the second of a pair adds the defect that discriminates. */
        val WITNESSES: Map<String, (Witness) -> String> = linkedMapOf(
            "the base" to { w -> w.json() },
            "a field named with a space" to { w -> w.rename("Code", " ").json() },
            "a field named with a space, beside a rule naming no field" to { w ->
                w.rename("Code", " ").rule(null, "Broken", NO_FIELD, "../Ref").json()
            },
            "a field with an empty name" to { w -> w.rename("Code", "").json() },
            "a field with no name" to { w -> w.also { it.element("Code").remove("name") }.json() },
            "a field whose name is null" to { w -> w.also { it.element("Code").putNull("name") }.json() },
            "a field whose name is an object" to { w -> w.also { it.element("Code").putObject("name") }.json() },
            "a field whose name is an array" to { w -> w.also { it.element("Code").putArray("name") }.json() },
            "a group with no name, holding a rule naming no field" to { w ->
                w.rule("Items", "Inner", NO_FIELD, "../Label").also { it.element("Items").remove("name") }.json()
            },
            "a rule with no name, naming no field" to { w ->
                w.rule(null, "Broken", NO_FIELD, "../Ref").also { it.element("Broken").remove("name") }.json()
            },
            "a field typed by a type reference with no id" to { w ->
                w.also { it.typedBy("Code", "x"); it.element("Code").withObject("/Field/fieldType/TypeDefType").remove("typeDefinitionId") }.json()
            },
            "a group named with a space, holding a rule naming no field" to { w ->
                w.rule("Items", "Inner", NO_FIELD, "../Label").also { it.rename("Items", " ") }.json()
            },
            "a rule named with a space, naming no field" to { w ->
                w.rule(null, "Broken", NO_FIELD, "../Ref").rename("Broken", " ").json()
            },
            "a root group named with a space, holding a rule naming no field" to { w ->
                w.rule(null, "Broken", NO_FIELD, "../Ref").also { it.rootGroup().put("name", " ") }.json()
            },
            "a root group with an empty name, beside its rule" to { w -> w.also { it.rootGroup().put("name", "") }.json() },
            "a root group with an empty name, alone" to { w -> w.emptyRootWithoutItsRule().json() },
            "a root group with no name, beside its rule" to { w -> w.also { it.rootGroup().remove("name") }.json() },
            "a root group whose name is null, beside its rule" to { w -> w.also { it.rootGroup().putNull("name") }.json() },
            "a root group with an empty name, its rule in a nested group" to { w ->
                w.emptyRootWithoutItsRule().rule("Items", "LabelCheck", "FieldFilled(Label)", "../Label").json()
            },
            "a root group with an empty name, holding a computation" to { w ->
                w.emptyRootWithoutItsRule().computation("../Total", "1").json()
            },
            "a root group with an empty name, its rule with an empty error path" to { w ->
                w.emptyRootWithoutItsRule().rule(null, "Pathless", "FieldFilled(Code)", "").json()
            },
            "a root group with an empty name, its group indexed by a field" to { w ->
                w.emptyRootWithoutItsRule().also { it.element("Items").withObject("/Group").put("indexFieldName", "Label") }.json()
            },
            "a root group with an empty name, its group sorted by a field" to { w ->
                w.emptyRootWithoutItsRule().also {
                    it.element("Items").withObject("/Group").putArray("sortFields").addObject().put("name", "Label")
                }.json()
            },
            "a root group with an empty name, beside a named root holding a rule" to { w ->
                w.emptyRootWithoutItsRule().secondRoot("Claim").json()
            },
            "a field named with a space, its fraction count above the scale limit" to { w ->
                w.also { it.number("Fee").put("maxFractionalDigits", 20) }.rename("Fee", " ").json()
            },
            "a field named with a space, its minimum above its maximum" to { w ->
                w.also { it.number("Fee").put("minValue", 9).put("maxValue", 1) }.rename("Fee", " ").json()
            },
            "a field named with a space, beside a computation cycle" to { w ->
                w.rename("Code", " ").computation("../Total", "[Fee]", "TotalCalc").computation("../Fee", "[Total]", "FeeCalc").json()
            },
            "a computation with an empty target" to { w -> w.computation("", "1").json() },
            "a computation with an unresolvable target, naming no field" to { w ->
                w.computation("../NoSuchTotal", "[NoSuchFieldAnywhere]").json()
            },
            "a computation with an unresolvable target, beside a rule naming no field" to { w ->
                w.computation("../NoSuchTotal", "1").rule(null, "Broken", NO_FIELD, "../Ref").json()
            },
            "a computation whose target is named with a space" to { w ->
                w.rename("Total", " ").computation("../ ", "1").json()
            },
            "a computation whose target lies in a group named with a space" to { w ->
                w.rename("Items", " ").computation("../ /Label", "1").json()
            },
            "a computation whose target is the later of two fields of one name" to { w ->
                w.duplicate("Total").computation("../Total", "1").json()
            },
            "a rule whose error field is named with a space" to { w ->
                w.rename("Code", " ").rule(null, "BlankErrorField", "FieldFilled(Ref)", "../ ").json()
            },
            "a field typed by an undeclared type definition" to { w -> w.typedBy("Code", "Ghost").json() },
            "a field typed by an undeclared type definition, named by a rule" to { w ->
                w.typedBy("Code", "Ghost").rule(null, "CodeCheck", "FieldFilled(Code)", "../Code").json()
            },
            "two fields of one name" to { w -> w.duplicate("Code").json() },
            "two fields of one name, the first's fraction count above the scale limit" to { w ->
                w.also { it.number("Fee").put("maxFractionalDigits", 20) }.also { it.duplicate("Fee") }.also { it.lastNumber().put("maxFractionalDigits", 0) }.json()
            },
            "two fields of one name, the second's fraction count above the scale limit" to { w ->
                w.duplicate("Fee").also { it.lastNumber().put("maxFractionalDigits", 20) }.json()
            },
            "two rules of one name, the first naming no field" to { w ->
                w.rule(null, "Twin", NO_FIELD, "../Ref").rule(null, "Twin", "FieldFilled(Ref)", "../Ref").json()
            },
            "two rules of one name, the second naming no field" to { w ->
                w.rule(null, "Twin", "FieldFilled(Ref)", "../Ref").rule(null, "Twin", NO_FIELD, "../Ref").json()
            },
        )

        /** Measured through the Kernel gate, kernel 31.1.1, 2026-11-02. */
        val A12: Map<String, Set<String>> = linkedMapOf(
            "the base" to setOf(),
            "a field named with a space" to setOf("RK_ELEMENT_NAME_MISSING"),
            "a field named with a space, beside a rule naming no field" to setOf("RK_ELEMENT_NAME_MISSING", "MVK_INVALID_ENTITY"),
            "a field with an empty name" to setOf("RK_ELEMENT_NAME_MISSING", "RK_NAME_EMPTY"),
            "a field with no name" to setOf("RK_ELEMENT_NAME_MISSING", "RK_NAME_EMPTY"),
            "a field whose name is null" to setOf("RK_ELEMENT_NAME_MISSING", "RK_NAME_EMPTY"),
            "a field whose name is an object" to setOf(UNBUILT),
            "a field whose name is an array" to setOf(UNBUILT),
            "a group with no name, holding a rule naming no field" to setOf("RK_ELEMENT_NAME_MISSING", "RK_NAME_EMPTY"),
            "a rule with no name, naming no field" to setOf("RK_ELEMENT_NAME_MISSING", "RK_NAME_EMPTY"),
            "a field typed by a type reference with no id" to setOf("RK_TYPE_DEFINITION_NOT_FOUND", "RK_TYPE_REFERENCE_EMPTY"),
            "a group named with a space, holding a rule naming no field" to setOf("RK_ELEMENT_NAME_MISSING"),
            "a rule named with a space, naming no field" to setOf("RK_ELEMENT_NAME_MISSING"),
            "a root group named with a space, holding a rule naming no field" to setOf("RK_ROOT_GROUP_NAME_MISSING"),
            "a root group with an empty name, beside its rule" to setOf(UNBUILT),
            "a root group with an empty name, alone" to setOf("RK_ROOT_GROUP_NAME_MISSING", "RK_NAME_EMPTY"),
            "a root group with no name, beside its rule" to setOf("RK_ROOT_GROUP_NAME_MISSING", "RK_NAME_EMPTY"),
            "a root group whose name is null, beside its rule" to setOf("RK_ROOT_GROUP_NAME_MISSING", "RK_NAME_EMPTY"),
            "a root group with an empty name, its rule in a nested group" to setOf(UNBUILT),
            "a root group with an empty name, holding a computation" to setOf(UNBUILT),
            "a root group with an empty name, its rule with an empty error path" to
                setOf("RK_ROOT_GROUP_NAME_MISSING", "RK_NAME_EMPTY", "RK_ERROR_ENTITY_EMPTY"),
            "a root group with an empty name, its group indexed by a field" to setOf(UNBUILT),
            "a root group with an empty name, its group sorted by a field" to setOf(UNBUILT),
            "a root group with an empty name, beside a named root holding a rule" to
                setOf("RK_ROOT_GROUP_NAME_MISSING", "RK_NAME_EMPTY"),
            "a field named with a space, its fraction count above the scale limit" to setOf("RK_ELEMENT_NAME_MISSING"),
            "a field named with a space, its minimum above its maximum" to setOf("RK_ELEMENT_NAME_MISSING"),
            "a field named with a space, beside a computation cycle" to setOf("RK_ELEMENT_NAME_MISSING", "MVK_ERROR_CALC_CYCLE"),
            "a computation with an empty target" to setOf("RK_COMPUTED_FIELD_NOT_SPECIFIED", "RK_COMPUTED_FIELD_EMPTY"),
            "a computation with an unresolvable target, naming no field" to setOf("RK_COMPUTED_FIELD_NOT_FOUND"),
            "a computation with an unresolvable target, beside a rule naming no field" to
                setOf("RK_COMPUTED_FIELD_NOT_FOUND", "MVK_INVALID_ENTITY"),
            "a computation whose target is named with a space" to setOf(UNBUILT),
            "a computation whose target lies in a group named with a space" to setOf(UNBUILT),
            "a computation whose target is the later of two fields of one name" to setOf("RK_CHILD_NAME_DUPLICATE"),
            "a rule whose error field is named with a space" to
                setOf("RK_ELEMENT_NAME_MISSING", "MVK_ERROR_FIELD_MISSING", "MVK_MISSING_ERROR_FIELD"),
            "a field typed by an undeclared type definition" to setOf("RK_TYPE_DEFINITION_NOT_FOUND"),
            "a field typed by an undeclared type definition, named by a rule" to setOf("RK_TYPE_DEFINITION_NOT_FOUND"),
            "two fields of one name" to setOf("RK_CHILD_NAME_DUPLICATE"),
            "two fields of one name, the first's fraction count above the scale limit" to
                setOf("RK_CHILD_NAME_DUPLICATE", "RK_MEMBER_ABOVE_SCALE_LIMIT"),
            "two fields of one name, the second's fraction count above the scale limit" to setOf("RK_CHILD_NAME_DUPLICATE"),
            "two rules of one name, the first naming no field" to setOf("RK_CHILD_NAME_DUPLICATE", "MVK_INVALID_ENTITY"),
            "two rules of one name, the second naming no field" to setOf("RK_CHILD_NAME_DUPLICATE"),
        )
    }
}
