package io.github.mbackschat.a12.dm.bridge

import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.assertThrows
import java.nio.charset.StandardCharsets
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import io.github.mbackschat.a12.dm.core.ModelIndex
import io.github.mbackschat.a12.dm.core.ReferenceResolvers
import io.github.mbackschat.a12.dm.interpreter.model.ConditionLanguage
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel
import io.github.mbackschat.a12.dm.rulekit.dsl.Condition

/**
 * The `$` outer-iteration correlation makes a reference a DIFFERENT operand from the same path read in
 * the current row, so both may sit in one aggregate's slot list — `NumberOfFilledFields($Count, Count)`
 * inside a `Having` filter compares a row against the outer one, which is what the correlation is for.
 * rulekit's entity-list duplicate gate keys on the rendered path, the one part of a `FieldRef` that does
 * not carry `correlated`.
 *
 * Lives here rather than in rulekit's own suite because that suite compiles against `:adapter` and so
 * needs the kernel artifacts; this source set reaches the same builders without them.
 */
class CorrelatedAggregateOperandTest {

    private lateinit var bridge: ConditionBridge

    @BeforeEach
    fun bind() {
        val json = javaClass.getResourceAsStream("/order.json")!!.readAllBytes().toString(StandardCharsets.UTF_8)
        bridge = ConditionBridge(BoundModel.ofJson(json), ModelIndex.of(json, ReferenceResolvers.selfContained()))
    }

    private fun translate(condition: String): Condition = bridge.translate(condition, BASE, ConditionLanguage.EN)

    @Test
    fun aCorrelatedOperandDoesNotDuplicateItsOwnPath() {
        assertDoesNotThrow { translate("NumberOfFilledFields(\$Count, Count) == 2") }
        assertDoesNotThrow { translate("NumberOfFilledFields(Count, \$Count) == 2") }
        assertDoesNotThrow { translate("Sum(\$Count, Count) == 2") }
    }

    /** Two references of the SAME correlation to one path are one operand, and stay a duplicate. */
    @Test
    fun twoReferencesOfOneCorrelationToOnePathStayADuplicate() {
        assertThrows<IllegalArgumentException> { translate("Sum(\$Count, \$Count) == 2") }
        assertThrows<IllegalArgumentException> { translate("Sum(Count, Count) == 2") }
    }

    /** Two distinct paths were never a duplicate, so the split took nothing away. */
    @Test
    fun twoDistinctPathsStayAccepted() {
        assertDoesNotThrow { translate("Sum(Count, StockQty) == 2") }
    }

    private companion object {
        private const val BASE = "/Order/Items"
    }
}
