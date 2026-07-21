package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.interpreter.DmInterpreter
import io.github.mbackschat.a12.dm.interpreter.eval.newDocument
import io.github.mbackschat.a12.dm.interpreter.load.ModelLoader
import io.github.mbackschat.a12.dm.interpreter.model.MsgType
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl
import io.github.mbackschat.a12.dm.rulekit.dsl.Severity
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Tri-engine lock for group presence when a non-repeatable group's descendant is formally invalid. The kernel
 * admits only successfully converted values to its group-fill cache, while separately marking the group as
 * erroneous. Consequently a malformed-only group is neither filled nor definitely empty: `GroupFilled` is
 * false and `GroupNotFilled` is unknown. A mixed valid/malformed group is filled, but still not definitely empty.
 */
class GroupPresenceFormalInvalidDiffTest {

    private val base = DocumentModelDraft.named("survey").locale("en_US")
        .rootGroup("Survey") { survey ->
            survey.stringField("Witness") { }
            survey.group("Contact") { contact ->
                contact.numberField("Primary") { it.fractionDigits(1, 1) }
                contact.numberField("Backup") { it.fractionDigits(1, 1) }
                contact.stringField("RequiredNote") { it.requiredIfParentPresent() }
            }
            survey.group("Preferences") { it.stringField("Channel") { } }
        }
        .build()
        .toJson()
    private val model = BoundModel.ofJson(base)
    private val witness = model.str(WITNESS)
    private val filledRule = model.rule(model.group(ROOT)).name(FILLED)
        .code(FILLED)
        .errorField(witness)
        .`when`(witness.filled().and(model.group(CONTACT).filled()))
        .severity(Severity.ERROR)
        .message(Dsl.en("Contact has admitted content."))
        .build()
    private val emptyRule = model.rule(model.group(ROOT)).name(NOT_FILLED)
        .code(NOT_FILLED)
        .errorField(witness)
        .`when`(witness.filled().and(model.group(CONTACT).notFilled()))
        .severity(Severity.ERROR)
        .message(Dsl.en("Contact is definitely empty."))
        .build()
    private val allGroupsRule = model.rule(model.group(ROOT)).name(ALL_FILLED)
        .code(ALL_FILLED)
        .errorField(witness)
        .`when`(witness.filled().and(Dsl.allGroupsFilled(model.group(CONTACT), model.group(PREFERENCES))))
        .severity(Severity.ERROR)
        .message(Dsl.en("Contact and preferences both have admitted content."))
        .build()
    private val quantifierRules = listOf(
        quantifierRule(NO_FILLED, Dsl.noGroupFilled(model.group(CONTACT), model.group(PREFERENCES))),
        quantifierRule(AT_LEAST_ONE, Dsl.atLeastOneGroupFilled(model.group(CONTACT), model.group(PREFERENCES))),
        quantifierRule(NOT_ALL, Dsl.notAllGroupsFilled(model.group(CONTACT), model.group(PREFERENCES))),
        quantifierRule(COLLECTIVELY, Dsl.groupsNotCollectivelyFilled(model.group(CONTACT), model.group(PREFERENCES))),
        quantifierRule(COUNT_ZERO, Dsl.numberOfFilledGroups(model.group(CONTACT), model.group(PREFERENCES)).eq(0)),
    )
    private val modelJson = (listOf(filledRule, emptyRule, allGroupsRule) + quantifierRules).fold(base, TypedRules::inject)
    private val definition = ModelLoader.load(modelJson)
    private val javaEngine = RuntimeLaws.kernelJavaEngine(modelJson)

    @Test fun emptyGroupIsDefinitelyEmpty() = agree(emptyMap(), listOf("$NOT_FILLED|OMISSION_ERROR"), "empty")

    @Test fun malformedOnlyGroupIsNeitherFilledNorDefinitelyEmpty() =
        agree(mapOf(PRIMARY to "7"), emptyList(), "malformed only")

    @Test fun validOnlyGroupIsFilled() =
        agree(mapOf(PRIMARY to "7.0"), listOf("$FILLED|VALUE_ERROR"), "valid only")

    @Test fun mixedValidAndMalformedGroupIsFilledButNotDefinitelyEmpty() =
        agree(mapOf(PRIMARY to "7.0", BACKUP to "8"), listOf("$FILLED|VALUE_ERROR"), "valid plus malformed")

    /** Predicted sibling: group-list tallies must leave a malformed-only group in neither the true nor false bucket. */
    @Test fun allGroupsFilledDoesNotCountMalformedOnlyAsFilled() =
        agree(mapOf(PRIMARY to "7", CHANNEL to "email"), emptyList(), "malformed contact plus valid preferences")

    @Test fun allGroupsFilledCountsAdmittedContentEvenWhenTheGroupIsAlsoErroneous() =
        agree(
            mapOf(PRIMARY to "7.0", BACKUP to "8", CHANNEL to "email"),
            listOf("$ALL_FILLED|VALUE_ERROR", "$FILLED|VALUE_ERROR"),
            "mixed contact plus valid preferences",
        )

    /** The same admitted-content cache gates `relativeToParent`: rejected malformed data must not activate a
     * required empty sibling, while an admitted value must. */
    @Test fun malformedOnlyParentDoesNotActivateRelativeRequiredness() = mandatoryAgree(mapOf(PRIMARY to "7"), false)

    @Test fun admittedParentContentActivatesRelativeRequiredness() = mandatoryAgree(mapOf(PRIMARY to "7.0"), true)

    /** All five group-list quantifiers plus NumberOfFilledGroups must consume the same admitted/error tally. */
    @Test fun groupQuantifiersAndCountShareTheFormalAwareTally() {
        quantifierAgree(emptyMap(), setOf(NO_FILLED, NOT_ALL, COUNT_ZERO), "both groups clean empty")
        quantifierAgree(mapOf(PRIMARY to "7"), setOf(NOT_ALL), "malformed contact plus empty preferences")
        quantifierAgree(
            mapOf(PRIMARY to "7.0"),
            setOf(AT_LEAST_ONE, NOT_ALL, COLLECTIVELY),
            "valid contact plus empty preferences",
        )
        quantifierAgree(mapOf(PRIMARY to "7", CHANNEL to "email"), setOf(AT_LEAST_ONE), "malformed contact plus filled preferences")
        quantifierAgree(
            mapOf(PRIMARY to "7.0", BACKUP to "8", CHANNEL to "email"),
            setOf(AT_LEAST_ONE),
            "erroneous-filled contact plus filled preferences",
        )
    }

    private fun quantifierRule(code: String, condition: io.github.mbackschat.a12.dm.rulekit.dsl.Condition) =
        model.rule(model.group(ROOT)).name(code).code(code).errorField(witness)
            .`when`(witness.filled().and(condition)).severity(Severity.ERROR)
            .message(Dsl.en("Group tally outcome.")).build()

    private fun quantifierAgree(values: Map<String, String>, expectedCodes: Set<String>, scenario: String) {
        val kernelDocument = RuntimeLaws.doc().group(ROOT, 1).group(CONTACT, 1, 1).field(WITNESS, "yes", 1, 1)
        values.forEach { (path, value) -> kernelDocument.field(path, value, 1, 1, 1) }
        val dynamic = RuntimeLaws.messages(modelJson, kernelDocument).filter { it.code() in QUANTIFIER_CODES }
            .map { "${it.code()}|${it.type().name}" }.sorted()
        val generated = javaEngine.messages(javaEngine.instance(kernelDocument)).filter { it.code() in QUANTIFIER_CODES }
            .map { "${it.code()}|${it.type().name}" }.sorted()

        val interpreterDocument = definition.newDocument().group(ROOT, 1).group(CONTACT, 1, 1).field(WITNESS, "yes", 1, 1)
        values.forEach { (path, value) -> interpreterDocument.field(path, value, 1, 1, 1) }
        val interpreted = DmInterpreter(definition).validateFull(interpreterDocument).filter { it.code in QUANTIFIER_CODES }
            .map { "${it.code}|${if (it.type == MsgType.VALUE) "VALUE_ERROR" else "OMISSION_ERROR"}" }.sorted()

        assertEquals(expectedCodes, dynamic.mapTo(sortedSetOf()) { it.substringBefore('|') }, "$scenario — normative firings")
        assertEquals(dynamic, generated, "$scenario — generated Java")
        assertEquals(dynamic, interpreted, "$scenario — interpreter")
    }

    private fun agree(values: Map<String, String>, expected: List<String>, scenario: String) {
        val kernelDocument = RuntimeLaws.doc().group(ROOT, 1).group(CONTACT, 1, 1)
            .field(WITNESS, "yes", 1, 1)
        values.forEach { (path, value) -> kernelDocument.field(path, value, 1, 1, 1) }

        val dynamic = RuntimeLaws.messages(modelJson, kernelDocument).signatures()
        val generated = javaEngine.messages(javaEngine.instance(kernelDocument)).signatures()

        val interpreterDocument = definition.newDocument().group(ROOT, 1).group(CONTACT, 1, 1)
            .field(WITNESS, "yes", 1, 1)
        values.forEach { (path, value) -> interpreterDocument.field(path, value, 1, 1, 1) }
        val interpreted = DmInterpreter(definition).validateFull(interpreterDocument)
            .filter { it.code in CODES }
            .map { "${it.code}|${if (it.type == MsgType.VALUE) "VALUE_ERROR" else "OMISSION_ERROR"}" }
            .sorted()

        assertEquals(expected, dynamic, "$scenario — normative dynamic-Groovy")
        assertEquals(dynamic, generated, "$scenario — generated Java")
        assertEquals(dynamic, interpreted, "$scenario — interpreter")
    }

    private fun mandatoryAgree(values: Map<String, String>, expected: Boolean) {
        val kernelDocument = RuntimeLaws.doc().group(ROOT, 1).group(CONTACT, 1, 1)
            .field(WITNESS, "yes", 1, 1)
        values.forEach { (path, value) -> kernelDocument.field(path, value, 1, 1, 1) }
        val dynamic = RuntimeLaws.messages(modelJson, kernelDocument).any { it.code() == "mandatoryField" && it.field().contains("RequiredNote") }
        val generated = javaEngine.messages(javaEngine.instance(kernelDocument))
            .any { it.code() == "mandatoryField" && it.field().contains("RequiredNote") }

        val interpreterDocument = definition.newDocument().group(ROOT, 1).group(CONTACT, 1, 1)
            .field(WITNESS, "yes", 1, 1)
        values.forEach { (path, value) -> interpreterDocument.field(path, value, 1, 1, 1) }
        val interpreted = DmInterpreter(definition).validateFull(interpreterDocument)
            .any { it.code == "mandatoryField" && it.errorPath.contains("RequiredNote") }

        assertEquals(expected, dynamic, "relative requiredness — normative dynamic-Groovy")
        assertEquals(dynamic, generated, "relative requiredness — generated Java")
        assertEquals(dynamic, interpreted, "relative requiredness — interpreter")
    }

    private fun List<RuntimeLaws.Msg>.signatures(): List<String> =
        filter { it.code() in CODES }.map { "${it.code()}|${it.type().name}" }.sorted()

    private companion object {
        const val ROOT = "/Survey"
        const val CONTACT = "$ROOT/Contact"
        const val WITNESS = "$ROOT/Witness"
        const val PRIMARY = "$CONTACT/Primary"
        const val BACKUP = "$CONTACT/Backup"
        const val PREFERENCES = "$ROOT/Preferences"
        const val CHANNEL = "$PREFERENCES/Channel"
        const val FILLED = "CONTACT_FILLED"
        const val NOT_FILLED = "CONTACT_NOT_FILLED"
        const val ALL_FILLED = "ALL_GROUPS_FILLED"
        const val NO_FILLED = "NO_GROUP_FILLED"
        const val AT_LEAST_ONE = "AT_LEAST_ONE_GROUP_FILLED"
        const val NOT_ALL = "NOT_ALL_GROUPS_FILLED"
        const val COLLECTIVELY = "GROUPS_NOT_COLLECTIVELY_FILLED"
        const val COUNT_ZERO = "ZERO_FILLED_GROUPS"
        val CODES = setOf(FILLED, NOT_FILLED, ALL_FILLED)
        val QUANTIFIER_CODES = setOf(NO_FILLED, AT_LEAST_ONE, NOT_ALL, COLLECTIVELY, COUNT_ZERO)
    }
}
