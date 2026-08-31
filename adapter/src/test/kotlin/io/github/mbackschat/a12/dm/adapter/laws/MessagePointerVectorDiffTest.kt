package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import io.github.mbackschat.a12.dm.modelkit.FieldKind
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel
import io.github.mbackschat.a12.dm.rulekit.dsl.Condition
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl
import io.github.mbackschat.a12.dm.rulekit.dsl.DslRenderer
import java.util.Locale
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * The raw repetition VECTOR behind a kernel message's pointers — the channel our own renderer flattens.
 *
 * **Why this exists.** a12-kernel-lean records two message-address spellings (`SPEC-2026-08-31-16`): an
 * *instance* form (`Order/Lines[2]/Produced`, bare, repetition one elided) and a *declaration* form
 * (`/Order[1]/Lines[0]/Entries[0]/Choice`, slash-led, eliding nothing, `[0]` at each repeatable level the
 * rule's iteration left unbound). Our `formatPointer` is slash-led and emits an index on every group segment,
 * so it destroys the elision difference and we cannot corroborate the spellings themselves.
 *
 * **But a `0` survives that transformation, and the vector survives it entirely.** Their sharper hypothesis is
 * that the two spellings are two pointer KINDS — exact and partially known — with an *unbound repeatable level*
 * being what makes a reference partially known. That predicts a `0` in the vector exactly where the rule's
 * iteration leaves a repeatable level free, and no `0` where it is bound. They cannot read a vector; we can,
 * because `fmtPointer` already reads `repetitionIndexes()` off the kernel's own pointer and only the RENDERING
 * discards it.
 *
 * **The pair is the measurement.** One rule iterates at the ROOT scope and references the flatten of `Lines*`
 * then `Sku`, leaving the repeatable level free; its twin iterates at `Lines` and references the same field
 * from inside the row, so the level is bound. Same model, same field, same document — only the scope differs.
 */
class MessagePointerVectorDiffTest {

    /**
     * An unbound repeatable level contributes `0` to the referenced pointer's vector, and binding the same
     * level replaces it with the concrete row. This is the substantive half of a12-kernel-lean's two-spelling
     * account, reachable here even though the spelling difference is not.
     */
    @Test
    fun anUnboundRepeatableLevelIsAZeroInTheVectorAndABoundOneIsTheRow() {
        assertEquals(
            mapOf(
                "root scope — Lines level unbound" to listOf(listOf(1, 0, 1)),
                "Lines scope — Lines level bound to row 1" to listOf(listOf(1, 1, 1)),
            ),
            referencedVectors(),
            "the free level is 0; the bound one is its row",
        )
    }

    // ---- fixtures --------------------------------------------------------------------------------

    /** Each rule's referenced-field pointers as raw `repetitionIndexes()` vectors, keyed by the rule's scope. */
    private fun referencedVectors(): Map<String, List<List<Int>>> {
        val engine = RuntimeLaws.kernelEngine(model)
        val doc = RuntimeLaws.doc()
        doc.group(ORDER, 1)
        doc.field(REF, "R-1", 1, 1)
        doc.group(LINES, 1, 1)
        doc.field("$LINES/Qty", "2", 1, 1, 1)
        return engine.validationMessages(engine.instance(doc), null, Locale.US)
            .filter { it.errorCode in SCOPE_BY_CODE }
            .associate { message ->
                SCOPE_BY_CODE.getValue(message.errorCode) to
                    // Only the Sku reference, so the two rows compare like for like: the root-scope rule also
                    // references its guard field, which has no repeatable level and would not be comparable.
                    message.referencedFieldsPointers
                        .filter { it.fullName().endsWith("Sku") }
                        .map { it.repetitionIndexes().toList() }
            }
    }

    private companion object {

        const val ORDER = "/Order"
        const val LINES = "/Order/Lines"
        const val REF = "/Order/Reference"

        const val FREE = "FREE_LEVEL"
        const val BOUND = "BOUND_LEVEL"

        val SCOPE_BY_CODE = mapOf(
            FREE to "root scope — Lines level unbound",
            BOUND to "Lines scope — Lines level bound to row 1",
        )

        private val baseModel: String = DocumentModelDraft.named("pointer-vectors").locale("en_US")
            .rootGroup("Order") { order ->
                order.field("Reference", FieldKind.STRING)
                    .group("Lines") { lines ->
                        lines.repeatable(3).field("Sku", FieldKind.STRING).field("Qty", FieldKind.STRING)
                    }
            }.build().toJson()

        /**
         * Two rules over the same referenced field, differing only in iteration scope. Both fire on a document
         * whose one `Lines` row is empty, so the reference is reported in each case.
         */
        val model: String = run {
            val base = BoundModel.ofJson(baseModel)
            fun render(c: Condition) = DslRenderer().render(c)
            val fromRoot = render(
                Dsl.fieldFilled(base.field(REF))
                    .and(Dsl.noFieldFilled(base.flatten("$LINES*/Sku"))),
            )
            // Two kernel gates shape this rule: its error field must live INSIDE its rule group once the
            // condition iterates (MVK_ERROR_FIELD_NOT_IN_RULEGROUP), and a bare negative in an iterating rule is
            // refused because it would fire for unfilled repetitions (MVK_NEG_CONDITION_IN_ITERATION). So it
            // reports on the row's own Sku and is guarded by a sibling the document fills.
            val fromRow = render(
                Dsl.fieldFilled(base.field("$LINES/Qty"))
                    .and(Dsl.fieldNotFilled(base.field("$LINES/Sku"))),
            )
            var json = RuntimeLaws.withRule(baseModel, ORDER, FREE, "../Reference", FREE, fromRoot)
            json = RuntimeLaws.withRule(json, LINES, BOUND, "../Sku", BOUND, fromRow)
            json
        }
    }
}
