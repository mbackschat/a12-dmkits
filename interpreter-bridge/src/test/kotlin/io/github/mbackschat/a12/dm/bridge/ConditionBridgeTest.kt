package io.github.mbackschat.a12.dm.bridge

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import io.github.mbackschat.a12.dm.core.KernelCodeRefusal
import io.github.mbackschat.a12.dm.core.ModelIndex
import io.github.mbackschat.a12.dm.core.ReferenceResolvers
import io.github.mbackschat.a12.dm.interpreter.model.ConditionLanguage
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import io.github.mbackschat.a12.dm.modelkit.FieldKind
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel
import io.github.mbackschat.a12.dm.rulekit.dsl.ComparisonCondition
import io.github.mbackschat.a12.dm.rulekit.dsl.Condition
import io.github.mbackschat.a12.dm.rulekit.dsl.ConditionReferences

/**
 * The bridge's three outcomes, on a model with no kernel anywhere on the classpath.
 *
 * The rejection cases are the point: they are rulekit's own checks firing on condition TEXT, which nothing
 * could do before — rulekit's builders were reachable only by authoring a rule through its API.
 */
class ConditionBridgeTest {

    private lateinit var bridge: ConditionBridge

    @BeforeEach
    fun bind() {
        val json = BridgeModels.orderJson
        bridge = ConditionBridge(BoundModel.ofJson(json), ModelIndex.of(json, ReferenceResolvers.selfContained()))
    }

    private fun translate(condition: String): Condition = bridge.translate(condition, BASE, ConditionLanguage.EN)

    @Test
    fun aWellFormedComparisonConverts() {
        assertInstanceOf(ComparisonCondition::class.java, translate("[Quantity] == 3"))
    }

    @Test
    fun aWellFormedFieldCountConverts() {
        assertNotNull(translate("NumberOfFilledFields(Quantity, BackorderQuantity) < 2"))
    }

    /** MVK_PARAMSIZE_INVALIDN — one field is not a field list. */
    @Test
    fun aSingleFieldCountIsRejected() {
        val e = assertThrows<IllegalArgumentException> { translate("NumberOfFilledFields(Quantity) < 1") }
        assertTrue(e.message!!.contains("MVK_PARAMSIZE_INVALIDN")) { "expected the kernel's own code in: ${e.message}" }
    }

    /**
     * The count operators may not be handed a root group. The class is withheld: an empty root is
     * MVK_ROOT_GROUP_IS_EMPTY instead, which a group operand does not show
     * (`rulekit.validate.laws.EntityListOperandReadLawsTest`).
     */
    @Test
    fun aRootGroupOperandIsRejected() {
        val e = assertThrows<IllegalArgumentException> { translate("NumberOfFilledFields(/Order) < 1") }
        assertTrue(e.message!!.contains("a root group may not be referenced")) { "unexpected message: ${e.message}" }
        assertFalse(e is KernelCodeRefusal) { "a class the operand cannot settle was cited: ${e.message}" }
    }

    /** MVK_DUPLICATE_PARAM1 — the same field twice. */
    @Test
    fun aDuplicateFieldOperandIsRejected() {
        val e = assertThrows<IllegalArgumentException> { translate("NumberOfFilledFields(Quantity, Quantity) < 1") }
        assertTrue(e.message!!.contains("MVK_DUPLICATE_PARAM1")) { "expected the kernel's own code in: ${e.message}" }
    }

    /**
     * A repeated group operand is rulekit's to refuse, and it surfaces through the bridge with its class
     * withheld, since an empty group answers ahead of the duplicate gate.
     *
     * <p>The complement is the STARRED shape below: a repeatable group renders with a star on this carrier,
     * and the kernel's exact-duplicate arm skips a wildcarded ref, so that pair must survive translation.
     * Kernel rows: `rulekit.validate.laws.RootGroupCountLawsTest`,
     * `rulekit.validate.laws.DuplicateOverlapCarrierSweepLawsTest`,
     * `rulekit.validate.laws.EntityListOperandReadLawsTest`.
     */
    @Test
    fun aDuplicateGroupOperandIsRefused() {
        val e = assertThrows<IllegalArgumentException> {
            translate("NumberOfFilledGroups(ShippingAddress, ShippingAddress) < 1")
        }
        assertTrue(e.message!!.contains("is listed more than once")) { "expected the exact arm in: ${e.message}" }
        assertFalse(e is KernelCodeRefusal) { "a class the operand cannot settle was cited: ${e.message}" }
    }

    /** The exemption, one variable apart: a repeatable group renders starred, and a starred ref may repeat. */
    @Test
    fun aRepeatedStarredGroupOperandSurvivesTranslation() {
        assertNotNull(translate("NumberOfFilledGroups(Items*, Items*) < 1"))
    }

    /**
     * The three ways a translation can fail must stay distinguishable by TYPE, because a coverage
     * measurement built on them is otherwise measuring message text. A rulekit refusal often carries no
     * `MVK_*` code — rulekit types many entry points per kind, so a wrong-kind operand refuses in plain
     * English — and a harness keying on that prefix scores a real finding as nothing.
     */
    @Test
    fun anAbsentPathIsAResolutionFailureAndNotARulekitVerdict() {
        val absent = assertThrows<OperandResolutionException> { translate("FieldFilled(NoSuchField)") }
        assertTrue(absent.message!!.contains("no element at path")) { "unexpected message: ${absent.message}" }

        // The complement: an operand the model DOES have, refused by rulekit. It must not come back as a
        // resolution failure, or every real finding would be filed as a bridge gap.
        val refused = assertThrows<IllegalArgumentException> { translate("NumberOfFilledFields(/Order) < 1") }
        assertFalse(refused is OperandResolutionException) {
            "a rulekit refusal was classified as a resolution failure: ${refused.message}"
        }
        assertTrue(refused.message!!.contains("a root group may not be referenced")) {
            "unexpected message: ${refused.message}"
        }
    }

    /**
     * An unmapped construct must be its own outcome. Were it to arrive as a rejection, every gap in this
     * bridge would read as a rulekit finding and the coverage it is built to measure would be inflated.
     */
    @Test
    fun anUnmappedConstructIsNotARejection() {
        assertThrows<UnmappedConstructException> { translate("[CustomerName] PatternMatched [ProductCode]") }
    }

    @Test
    fun aGroupWithTheSameShortNameDoesNotMakeAUniqueFieldAmbiguous() {
        val json = DocumentModelDraft.named("short-name-collision").locale("en_US")
            .rootGroup("Order") { it.field("Anchor", FieldKind.STRING) }
            .rootGroup("FieldOwner") { it.field("Target", FieldKind.STRING) }
            .rootGroup("GroupOwner") { it.group("Target") { group -> group.field("Other", FieldKind.STRING) } }
            .build().toJson()
        val collisionBridge = ConditionBridge(
            BoundModel.ofJson(json),
            ModelIndex.of(json, ReferenceResolvers.selfContained()),
            true,
        )

        val condition = collisionBridge.translate("FieldFilled(Target)", "/Order", ConditionLanguage.EN)

        assertEquals(setOf("/FieldOwner/Target"), ConditionReferences.of(condition).fieldPaths())
    }

    private companion object {
        private const val BASE = "/Order"
    }
}
