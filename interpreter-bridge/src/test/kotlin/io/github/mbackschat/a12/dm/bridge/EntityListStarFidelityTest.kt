package io.github.mbackschat.a12.dm.bridge

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import io.github.mbackschat.a12.dm.core.KernelCode
import io.github.mbackschat.a12.dm.core.KernelCodeRefusal
import io.github.mbackschat.a12.dm.core.ModelIndex
import io.github.mbackschat.a12.dm.core.ReferenceResolvers
import io.github.mbackschat.a12.dm.interpreter.model.ConditionLanguage
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import io.github.mbackschat.a12.dm.modelkit.FieldKind
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel
import io.github.mbackschat.a12.dm.rulekit.dsl.Condition
import io.github.mbackschat.a12.dm.rulekit.dsl.DslRenderer

/**
 * A stored entity list keeps the stars its author wrote. A `GroupRef` carries none, and a factory over one stars
 * a group exactly when it repeats, so the group count reads each operand as its scope instead: read through a
 * `GroupRef`, `NumberOfFilledGroups(Keyed)`, which A12 refuses with `MVK_NO_WILDCARD`, came back as the admitted
 * `Keyed*`. A star written on a group that does not repeat is `MVK_INVALID_WILDCARD` ahead of the rest of that
 * operand's read.
 *
 * A12 reads the operands in list order, and the first that fails answers, so a refusal the bridge meets while
 * BUILDING a later operand keeps its class only when every earlier operand is a plain field that cannot fail.
 * The kernel classes are the rows of `rulekit.validate.laws.EntityListOperandReadLawsTest` and
 * `EntityListReadStageLawsTest`, measured on this model's shapes; the bridge itself never asks the kernel.
 */
class EntityListStarFidelityTest {

    private val json = DocumentModelDraft.named("warehouse-bridge").locale("en_US")
        .rootGroup("Item") { item ->
            item.field("Title", FieldKind.STRING)
                .field("Weight", FieldKind.NUMBER)
                .group("Grading") { it.field("Grade", FieldKind.NUMBER).field("Score", FieldKind.NUMBER) }
                .group("Specs") { it.field("Label", FieldKind.STRING) }
                .group("Hollow") { }
                .group("Lapses") { it.repeatable(3) }
                .group("Batches") { batches ->
                    batches.repeatable(4).field("Lot", FieldKind.STRING)
                        .group("Seal") { it.field("Code", FieldKind.STRING) }
                }
                .group("Keyed") { keyed ->
                    keyed.repeatable(5).indexField("Key").field("Key", FieldKind.STRING)
                        .field("Amount", FieldKind.NUMBER)
                }
        }
        .rootGroup("Lot") { it.repeatable(3).field("Serial", FieldKind.STRING) }
        .build().toJson()

    private val bridge = ConditionBridge(BoundModel.ofJson(json), ModelIndex.of(json, ReferenceResolvers.selfContained()))

    private fun translate(condition: String): Any = bridge.translate(condition, "/Item", ConditionLanguage.EN)

    private fun rendered(condition: String): String = DslRenderer().render(translate(condition) as Condition)

    private fun assertCited(code: KernelCode, condition: String) {
        val refusal = assertThrows(KernelCodeRefusal::class.java) { translate(condition) }
        assertEquals(code, refusal.code()) { "for $condition: ${refusal.message}" }
    }

    private fun assertWithheld(condition: String) {
        val refusal = assertThrows(IllegalArgumentException::class.java) { translate(condition) }
        assertFalse(refusal is KernelCodeRefusal) { "a later operand's class was cited for $condition: ${refusal.message}" }
    }

    @Test
    fun aStarOnAGroupThatDoesNotRepeatIsTheStarsClass() {
        assertCited(KernelCode.MVK_INVALID_WILDCARD, "NumberOfFilledGroups(Grading*) > 0")
        assertCited(KernelCode.MVK_INVALID_WILDCARD, "NumberOfFilledGroups(Hollow*) > 0")
        assertCited(KernelCode.MVK_INVALID_WILDCARD, "NumberOfFilledGroups(/Item*) > 0")
        assertCited(KernelCode.MVK_INVALID_WILDCARD, "NumberOfFilledGroups(Grading*, Specs) > 0")
    }

    /**
     * A group written without the star it repeats with reads as written, and never as the admitted `Keyed*`.
     * At this rule A12 refuses each text with `MVK_NO_WILDCARD`, and only `/Item` with the arity class
     * (`rulekit.validate.laws.GroupCountScopeOperandLawsTest`); a sole unstarred repeatable group would meet
     * the arity gate here, so its refusal is withheld rather than cited under a class A12 does not name. Beside
     * another group the text translates, the kernel's verdict depending on whether the rule iterates the group.
     */
    @Test
    fun aRepeatableGroupWrittenWithoutItsStarReadsAsWritten() {
        assertWithheld("NumberOfFilledGroups(Keyed) > 0")
        assertWithheld("NumberOfFilledGroups(/Lot) > 0")
        assertCited(KernelCode.MVK_PARAMSIZE_INVALIDGN, "NumberOfFilledGroups(/Item) > 0")
        assertEquals("NumberOfFilledGroups(/Item/Grading, /Item/Keyed) > 0",
            rendered("NumberOfFilledGroups(Grading, Keyed) > 0"))
    }

    /**
     * A field among the operands is refused, and A12 names it `MVK_NO_FIELDS_ALLOWED`
     * (`rulekit.validate.laws.GroupCountScopeOperandLawsTest`). No typed factory takes a field here, so the
     * record asks the gate and withholds the class: it cannot read whether the group beside the field answers
     * first.
     */
    @Test
    fun aFieldOperandIsRefusedWithItsClassWithheld() {
        assertWithheld("NumberOfFilledGroups(Grading, Weight) > 0")
    }

    /** The controls: a group written as the typed count renders it translates, and reads back unchanged. */
    @Test
    fun aGroupWrittenAsTheCountRendersItTranslates() {
        assertEquals("NumberOfFilledGroups(/Item/Keyed*) > 0", rendered("NumberOfFilledGroups(Keyed*) > 0"))
        assertEquals("NumberOfFilledGroups(/Lot*) > 0", rendered("NumberOfFilledGroups(/Lot*) > 0"))
        assertEquals("NumberOfFilledGroups(/Item/Grading, /Item/Keyed*) > 0",
            rendered("NumberOfFilledGroups(Grading, Keyed*) > 0"))
    }

    /**
     * A fixed group below a starred level keeps that star as a scope, alone or beside another group: a typed
     * count over [io.github.mbackschat.a12.dm.rulekit.dsl.GroupRef]s could not spell it (KERNEL-FINDINGS KF462).
     */
    @Test
    fun aGroupBelowAStarredLevelKeepsTheStar() {
        assertEquals("NumberOfFilledGroups(/Item/Batches*/Seal) > 0", rendered("NumberOfFilledGroups(Batches*/Seal) > 0"))
        assertEquals("NumberOfFilledGroups(/Item/Batches*/Seal, /Item/Grading) > 0",
            rendered("NumberOfFilledGroups(Batches*/Seal, Grading) > 0"))
        // the scope form reads the model too, so the list gate after the read keeps its class
        // (rulekit.validate.laws.GroupCountScopeOperandLawsTest)
        assertCited(KernelCode.MVK_ROOT_GROUP_WITH_OTHER_PARAMETERS, "NumberOfFilledGroups(Batches*/Seal, /Lot*) > 0")
    }

    /**
     * The single-group count reads the group through the model, which alone shows it is empty; the record
     * would withhold that class.
     */
    @Test
    fun aLoneEmptyGroupIsCitedThroughTheModel() {
        assertCited(KernelCode.MVK_GROUP_IS_EMPTY, "NumberOfFilledGroups(Hollow) > 0")
    }

    /** The field count keeps an unstarred repeatable group as written rather than starring it. */
    @Test
    fun theFieldCountKeepsARepeatableGroupAsWritten() {
        assertEquals("NumberOfFilledFields(/Item/Lapses) > 0", rendered("NumberOfFilledFields(Lapses) > 0"))
        assertEquals("NumberOfFilledFields(/Item/Grading, /Item/Keyed) > 0",
            rendered("NumberOfFilledFields(Grading, Keyed) > 0"))
        assertEquals("NumberOfFilledFields(/Item/Grading, /Item/Specs) > 0",
            rendered("NumberOfFilledFields(Grading, Specs) > 0"))
    }

    @Test
    fun aLaterOperandsRefusalKeepsItsClassOnlyBehindPlainFields() {
        // an earlier group may be empty, and A12 reads it first
        assertWithheld("Sum(Hollow, Grading*) > 0")
        assertWithheld("NumberOfFilledGroups(Hollow, Grading*) > 0")
        // an earlier field A12 cannot fail to read: the later operand's class is the answer
        assertCited(KernelCode.MVK_INVALID_WILDCARD, "Sum(Weight, Grading*) > 0")
        assertCited(KernelCode.MVK_INVALID_WILDCARD, "Sum(Grading*, Weight) > 0")
        assertCited(KernelCode.MVK_GROUP_IS_EMPTY, "FirstFilledValue(Weight, Hollow) > 0")
        // a field under a repeatable level the rule does not iterate may fail first
        assertWithheld("FirstFilledValue(Keyed/Amount, Hollow) > 0")
    }
}
