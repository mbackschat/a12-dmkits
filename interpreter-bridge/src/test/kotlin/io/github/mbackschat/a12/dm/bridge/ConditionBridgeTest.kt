package io.github.mbackschat.a12.dm.bridge

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.assertThrows
import java.nio.charset.StandardCharsets
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import io.github.mbackschat.a12.dm.core.ModelIndex
import io.github.mbackschat.a12.dm.core.ReferenceResolvers
import io.github.mbackschat.a12.dm.interpreter.model.ConditionLanguage
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel
import io.github.mbackschat.a12.dm.rulekit.dsl.ComparisonCondition
import io.github.mbackschat.a12.dm.rulekit.dsl.Condition

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
        val json = javaClass.getResourceAsStream("/order.json")!!.readAllBytes().toString(StandardCharsets.UTF_8)
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

    /** MVK_ROOT_GROUP_REFERENCED — the count operators may not be handed a root group. */
    @Test
    fun aRootGroupOperandIsRejected() {
        val e = assertThrows<IllegalArgumentException> { translate("NumberOfFilledFields(/Order) < 1") }
        assertTrue(e.message!!.contains("MVK_ROOT_GROUP_REFERENCED")) { "expected the kernel's own code in: ${e.message}" }
    }

    /** MVK_DUPLICATE_PARAM1 — the same field twice. */
    @Test
    fun aDuplicateFieldOperandIsRejected() {
        val e = assertThrows<IllegalArgumentException> { translate("NumberOfFilledFields(Quantity, Quantity) < 1") }
        assertTrue(e.message!!.contains("MVK_DUPLICATE_PARAM1")) { "expected the kernel's own code in: ${e.message}" }
    }

    /**
     * The complement, and it belongs in the suite rather than in a comment: rulekit gives a group operand no
     * dedup identity on purpose, leaving overlap (MVK_DUPLICATE_PARAM2) to the kernel. A bridge that reported
     * this as caught would be claiming coverage rulekit does not have.
     */
    @Test
    fun aDuplicateGroupOperandIsNotRulekitsToRefuse() {
        assertNotNull(translate("NumberOfFilledGroups(ShippingAddress, ShippingAddress) < 1"))
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
        assertTrue(refused.message!!.contains("MVK_ROOT_GROUP_REFERENCED")) { "unexpected message: ${refused.message}" }
    }

    /**
     * An unmapped construct must be its own outcome. Were it to arrive as a rejection, every gap in this
     * bridge would read as a rulekit finding and the coverage it is built to measure would be inflated.
     */
    @Test
    fun anUnmappedConstructIsNotARejection() {
        assertThrows<UnmappedConstructException> { translate("[CustomerName] PatternMatched [ProductCode]") }
    }

    private companion object {
        private const val BASE = "/Order"
    }
}
