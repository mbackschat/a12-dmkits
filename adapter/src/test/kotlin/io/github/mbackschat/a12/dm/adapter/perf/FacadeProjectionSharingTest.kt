package io.github.mbackschat.a12.dm.adapter.perf

import io.github.mbackschat.a12.dm.adapter.laws.TypedRules
import io.github.mbackschat.a12.dm.edit.EditSession
import io.github.mbackschat.a12.dm.interpreter.ModelWorkspace
import io.github.mbackschat.a12.dm.interpreter.PreparedModel
import io.github.mbackschat.a12.dm.interpreter.WorkspaceModelSource
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft.GroupBuilder
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl
import io.github.mbackschat.a12.dm.rulekit.dsl.Severity
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.function.Consumer

/**
 * **The published facade must not undo the engine's output sharing.**
 *
 * When every row of a repeatable group carries the same `RepetitionNotUnique` key, each of the N rows raises a
 * finding referencing all N rows, and the engine deliberately hands those N findings **one shared pointer-list
 * instance** instead of N private copies (IF191). The public `Interpreter` façade projects each engine message
 * into a `ValidationFinding` and snapshotted the pointer list defensively **per message**, which copies one
 * N-element list N times and cancels that optimization at exactly the boundary a library consumer calls.
 *
 * **The cost is allocation, not arithmetic — which is why this guard asserts identity rather than time.** A
 * timing guard at 1,000→4,000 rows measured 3.80× against a 5.4× trip line and passed with the defect fully
 * present: `arraycopy` is fast, so the quadratic hardly shows up in the clock. It shows up in the heap. At
 * 8,000 duplicate rows the façade produced 64 million pointer references and died with
 * `OutOfMemoryError: Java heap space` on a document the engine itself handles comfortably. A timing proxy for
 * an allocation defect is a guard that reports green while the process is one scale away from dying.
 *
 * So the invariant is checked directly and deterministically: findings the engine gave the same list must come
 * out sharing one list. No clock, no scale tuning, no flakiness — and it runs in the ordinary lane rather than
 * behind `-PrunPerf`, because a consumer-visible memory blow-up should not wait for someone to opt into the
 * perf tier.
 */
class FacadeProjectionSharingTest {

    @Test
    fun denseDuplicateFindingsShareOneReferencedPointerList() {
        val prepared = preparedModel()
        val snapshot = prepared.documents.readDocument(duplicateKeyDocument(ROWS))

        val report = prepared.createInterpreter().validateFull(snapshot)
        val duplicates = report.findings.filter { it.code == RNU }

        // Non-vacuity: the sharing only exists for a DENSE cluster, so every row must both raise a finding and
        // reference every row. Without this the assertion below could pass on an empty or trivial result.
        assertEquals(
            ROWS, duplicates.size,
            "every row of an all-identical key column must raise a duplicate finding — " +
                "findings=${report.findings.size} codes=${report.findings.map { it.code }.distinct()} " +
                "unsupported=${report.unsupported}",
        )
        assertTrue(duplicates.all { it.referenced.size == ROWS }) {
            "each duplicate finding must reference all $ROWS rows, else the shared cluster list is not produced"
        }

        val first = duplicates.first().referenced
        duplicates.forEach {
            assertSame(
                first, it.referenced,
                "every finding of one duplicate cluster must share ONE referenced-pointer list. A defensive " +
                    "copy per finding is O(N²) allocation and exhausted the heap at 8,000 rows; the engine " +
                    "already shares the list (IF191), so the façade only has to stop copying it.",
            )
        }
    }

    private fun preparedModel(): PreparedModel {
        val base = DocumentModelDraft.named("orders").locale("en_US")
            .rootGroup("Order", Consumer { o: GroupBuilder ->
                o.group("Lines", Consumer<GroupBuilder> {
                    it.repeatable(ROWS).stringField("LineKey", Consumer { }).numberField("Amount", Consumer { })
                })
            })
            .build().toJson()

        // Scoped to the PARENT group with a `Lines/LineKey` key, mirroring the proven shape in
        // `laws/RepetitionNotUniqueKeyKindDiffTest`, and authored as a typed Rule: `GroupEditor.field` resolves
        // a NESTED child name, so the grandchild ref needs no path string, and the rule derives both the error
        // path (`../Lines/LineKey`) and the group-relative condition itself.
        val order = EditSession.read(base).group("/Order")
        val key = order.field("Lines/LineKey")
        val rule = order.rule {
            it.name("R_DUP").code(RNU).errorField(key)
                .`when`(Dsl.repetitionNotUnique(key))
                .severity(Severity.ERROR).message(Dsl.en("duplicate line key")).build()
        }
        val json = TypedRules.inject(base, rule)
        return ModelWorkspace
            .fromSources(listOf(WorkspaceModelSource.builder().json(json).build()))
            .prepare("orders")
    }

    /** Every row carries the SAME key, so the duplicate cluster is the whole column — the dense case. */
    private fun duplicateKeyDocument(rows: Int): String =
        (1..rows).joinToString(
            separator = ",",
            prefix = """{"Order":{"Lines":[""",
            postfix = "]}}",
        ) { """{"LineKey":"K","Amount":$it}""" }

    private companion object {
        const val RNU = "DUP"

        /** Small on purpose: identity sharing is scale-free, so the guard needs no large document to see it. */
        const val ROWS = 40
    }
}
