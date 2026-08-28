package io.github.mbackschat.a12.dm.bridge

import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.assertThrows
import java.nio.charset.StandardCharsets
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import io.github.mbackschat.a12.dm.core.ModelIndex
import io.github.mbackschat.a12.dm.core.ReferenceResolvers
import io.github.mbackschat.a12.dm.interpreter.model.ConditionLanguage
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel

/**
 * `FieldFilled`/`FieldNotFilled` admit exactly one FIELD — unlike `AllFieldsFilled`/
 * `NoFieldFilled`, which legitimately take a starred member, a repetition list is not a single field
 * and the kernel rejects it with `MVK_NO_WILDCARDS_ALLOWED`. rulekit states that by typing the slot to
 * `FieldRef` alone —
 * compiler-enforced for a human author, unreachable for this bridge, which builds from an already-parsed
 * AST rather than writing Java against the DSL.
 *
 * Lives here rather than in rulekit's own suite because that suite compiles against `:adapter` and
 * so needs the kernel artifacts; this source set reaches the same builders without them.
 */
class FieldFilledWildcardTest {

    private lateinit var bridge: ConditionBridge

    @BeforeEach
    fun bind() {
        val json = javaClass.getResourceAsStream("/order.json")!!.readAllBytes().toString(StandardCharsets.UTF_8)
        bridge = ConditionBridge(BoundModel.ofJson(json), ModelIndex.of(json, ReferenceResolvers.selfContained()))
    }

    /**
     * [Assertions.assertThrows] already carries the classification: it fails
     * with "unexpected exception type" for anything not assignable to [IllegalArgumentException] —
     * which both [UnmappedConstructException] and [NoTypedOperationException] are not (a
     * separate lineage under [RuntimeException], so a value cannot be both; javac rejects the
     * `instanceof` between them as inconvertible). Catching the rejection AS an
     * `IllegalArgumentException` is therefore the proof it is rulekit's own verdict, not the
     * bridge's shrug.
     */
    @Test
    fun aStarredFieldOperandIsRejectedByRulekitNotTheBridge() {
        val e = assertThrows<IllegalArgumentException> {
            bridge.translate("FieldFilled(Items*/Count)", BASE, ConditionLanguage.EN)
        }
        assertTrue(e.message!!.contains("MVK_NO_WILDCARDS_ALLOWED")) { "expected the kernel's own code in: ${e.message}" }
    }

    @Test
    fun theSameRejectionAppliesToFieldNotFilled() {
        val e = assertThrows<IllegalArgumentException> {
            bridge.translate("FieldNotFilled(Items*/Count)", BASE, ConditionLanguage.EN)
        }
        assertTrue(e.message!!.contains("MVK_NO_WILDCARDS_ALLOWED")) { "expected the kernel's own code in: ${e.message}" }
    }

    /** The change is additive: a plain, unstarred field still converts exactly as before. */
    @Test
    fun aPlainFieldStillConverts() {
        assertDoesNotThrow { bridge.translate("FieldFilled(CustomerName)", BASE, ConditionLanguage.EN) }
        assertDoesNotThrow { bridge.translate("FieldNotFilled(CustomerName)", BASE, ConditionLanguage.EN) }
    }

    /** A starred GROUP predicate is untouched — `GroupRef` carries no scalar/list split to guard. */
    @Test
    fun aStarredGroupPredicateIsUnaffected() {
        assertDoesNotThrow { bridge.translate("GroupFilled(Items*)", BASE, ConditionLanguage.EN) }
    }

    private companion object {
        private const val BASE = "/Order"
    }
}
