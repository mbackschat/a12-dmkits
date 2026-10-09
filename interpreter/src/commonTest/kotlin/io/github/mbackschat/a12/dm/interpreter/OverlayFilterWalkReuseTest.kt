package io.github.mbackschat.a12.dm.interpreter

import io.github.mbackschat.a12.dm.interpreter.ast.emptyValue
import io.github.mbackschat.a12.dm.interpreter.eval.CascadeOverlay
import io.github.mbackschat.a12.dm.interpreter.eval.FilterWalkRowProvider
import io.github.mbackschat.a12.dm.interpreter.eval.GroupListEnvironmentProvider
import io.github.mbackschat.a12.dm.interpreter.eval.OverlayContext
import io.github.mbackschat.a12.dm.interpreter.eval.OverlayViews
import io.github.mbackschat.a12.dm.interpreter.eval.newDocument
import io.github.mbackschat.a12.dm.interpreter.model.FieldDef
import io.github.mbackschat.a12.dm.interpreter.model.Kind
import io.github.mbackschat.a12.dm.interpreter.model.ModelDef
import kotlin.test.Test
import kotlin.test.assertSame

/**
 * A computation step's outer rows share one wrapped candidate list for a `Having` filter's walk and for a group-list
 * operand's rows, as they already share the wrapped stored rows. Wrapping the candidates afresh for every outer row made a `$`-correlated filter
 * O(rows²) in allocation alone: the perf gate's compute-overlay correlation scenario grew 8.45× over 4× the rows
 * and breached its anchor (2026-10-09). The list identity is the deterministic witness of that cost.
 *
 * Own domain (an insurance claims register).
 */
class OverlayFilterWalkReuseTest {

    @Test
    fun outerRowsOfOneStepShareTheWrappedFilterCandidates() {
        val model = ModelDef(
            fields = listOf(FieldDef(AMOUNT, Kind.NUMBER, scale = 0)),
            rules = emptyList(),
            repeatableGroups = setOf(CLAIMS),
            declaredReps = mapOf(CLAIMS to 5),
        )
        val rows = model.newDocument().field(AMOUNT, "1", 1, 1, 1).field(AMOUNT, "2", 1, 2, 1).field(AMOUNT, "3", 1, 3, 1)
            .computeContexts(CLAIMS)
        val views = OverlayViews()
        val cascade = CascadeOverlay(repeatableGroups = setOf(CLAIMS))
        val (first, second) = rows.take(2).map { row ->
            OverlayContext(row, cascade, { emptyValue(model.kinds.getValue(it)) }, { 0 }, views, AMOUNT)
        }
        assertSame(first.filterWalkRows(CLAIMS, 0, null), second.filterWalkRows(CLAIMS, 0, null),
            "the second outer row must reuse the step's wrapped filter candidates")
        assertSame(first.groupListEnvironments(CLAIMS, 0), second.groupListEnvironments(CLAIMS, 0),
            "the second outer row must reuse the step's wrapped group-list rows")
    }

    private fun OverlayContext.filterWalkRows(group: String, bindDepth: Int, counts: ((String) -> Boolean)?) =
        (this as FilterWalkRowProvider).filterWalkRows(group, bindDepth, counts)

    private fun OverlayContext.groupListEnvironments(group: String, bindDepth: Int) =
        (this as GroupListEnvironmentProvider).groupListEnvironments(group, bindDepth)

    private companion object {
        const val CLAIMS = "/Register/Claims"
        const val AMOUNT = "/Register/Claims/Amount"
    }
}
