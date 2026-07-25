package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.interpreter.DmInterpreter
import io.github.mbackschat.a12.dm.interpreter.eval.Document
import io.github.mbackschat.a12.dm.interpreter.eval.newDocument
import io.github.mbackschat.a12.dm.interpreter.load.ModelLoader
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * On the **parallel-iteration** computation route there is no read-time semantic-index gate: the iteration-values
 * lookup returns empty so the loop body never runs, and `CalculationCache
 * .markFieldsUsingInvalidIndexGroupsAsIncorrectlyCalculated` marks affected instances afterwards. Marking keys on
 * the field instance's indices **truncated to `numSharedLevels`** = commonAncestor(computedField,
 * indexGroupParent) + 1, and `CodeGenCalculatedField.getParallelIterationGroupsCode()` passes BOTH the index
 * group on the computed field's own path and the off-path groups the operands iterate — here
 * `["/Order/Lines/Slots", "/Order/Refs"]`.
 *
 * **So the blast radius depends on WHICH index group holds the malformed key** — the answer a12-kernel-lean's
 * EXP-2026-07-25-03 asked for, and both of its competing accounts hold, on different loci:
 *  - **off-path** (`/Order/Refs`): the shared prefix stops at `/Order`, so every `Total` instance under it is
 *    marked regardless of its own deeper coordinates — **a clean sibling parent row does NOT survive**;
 *  - **on-path** (`/Order/Lines/Slots`): the shared prefix reaches `/Order/Lines`, so sibling rows are
 *    distinguished and **a clean sibling survives**.
 * The kernel's own comment concedes the first case marks "more field instances as invalid than strictly
 * necessary". Both kernel strategies agree on all three cases, so this is kernel truth, not a strategy split.
 *
 * **Authoring precondition (and why no corpus reaches this route):** the iterated group's index field must have
 * the SAME NAME as the index field on the computed field's path, else the kernel rejects with
 * `MVK_ERROR_FIELD_INVALID_INDEX` ("iteration is specified, but the index field on the error field path has
 * another name"). The route also needs a bracketed, NON-starred operand — `[../../Refs/Amount]`; a starred
 * `Refs*` is a wildcard, not an iteration, and takes the ordinary route.
 *
 * **Why this is ONE model carrying BOTH index groups, exercised three times — not three models.** The two loci
 * are not separately reachable: the marker's second stamping pass, the only producer of ON-PATH markers, runs
 * only if the first pass stamped an OFF-PATH one, so an on-path index group can never be parallel-iterated
 * alone. Both loci are present together or neither is. Comparing two structurally different documents would
 * therefore compare shapes, not loci — the malformed key must MOVE within one shape, which is what the three
 * cases below do. Derivation: KERNEL-FINDINGS §11 supplement "the parallel-iteration computation route".
 *
 * **Interpreter parity is asserted for the first two cases only — it diverges on the third (open, IG88).** The
 * interpreter clears the clean sibling that both kernel strategies preserve. This class is that gap's
 * reproducer; when IG88 lands, drop `interpAgrees` and assert unconditionally.
 */
class ParallelIterationIndexMarkingDiffTest {

    private companion object {
        const val ORDER = "/Order"
        const val LINES = "/Order/Lines"
        const val SLOTS = "/Order/Lines/Slots"
        const val REFS = "/Order/Refs"
        const val KEY = "/Order/Lines/Slots/Key"
        const val TOTAL = "/Order/Lines/Slots/Total"
        const val REF_KEY = "/Order/Refs/Key"
        const val REF_AMOUNT = "/Order/Refs/Amount"
    }

    /** `Slots` is the on-path index group holding the computed `Total`; `Refs` is the off-path one the operand
     *  iterates. Both index fields are named `Key` — required, per the authoring precondition above. */
    private val base = DocumentModelDraft.named("parallel-iteration-index").locale("en_US")
        .rootGroup("Order") { o ->
            o.group("Lines") { l ->
                l.repeatable(5)
                    .numberField("LineNo") { it.fractionDigits(0, 0) }
                    .group("Slots") { s ->
                        s.repeatable(5)
                            .numberField("Key") { it.fractionDigits(0, 0) }
                            .numberField("Total") { it.fractionDigits(0, 0) }
                            .indexField("Key")
                    }
            }
            o.group("Refs") { r ->
                r.repeatable(5)
                    .numberField("Key") { it.fractionDigits(0, 0) }
                    .numberField("Amount") { it.fractionDigits(0, 0) }
                    .indexField("Key")
            }
        }.build().toJson()

    private val model = RuntimeLaws.withComputation(base, SLOTS, "C", "../Total", "[../../Refs/Amount]")

    @Test
    fun `distinct keys everywhere compute both sibling rows`() =
        agree(
            refs = listOf("1" to "100", "2" to "200"),
            line1 = "VALUE|100", line2 = "VALUE|200", interpAgrees = true,
            label = "control: no malformed index key",
        )

    @Test
    fun `a malformed key in the OFF-PATH index group clears the CLEAN sibling too`() =
        agree(
            refs = listOf("1" to "100", "1" to "150", "2" to "200"),
            line1 = "CLEARED", line2 = "CLEARED", interpAgrees = true,
            label = "duplicate Refs key: Lines[2]'s slot key 2 still matches a clean Refs row, yet it clears",
        )

    @Test
    fun `a malformed key in the ON-PATH index group spares the sibling row`() =
        agree(
            refs = listOf("1" to "100", "2" to "200"), line1Slot2Key = "1",
            line1 = "CLEARED", line2 = "VALUE|200", interpAgrees = false,
            label = "duplicate within Lines[1]'s own Slots: Lines[2] is a different parent coordinate",
        )

    private fun agree(
        refs: List<Pair<String, String>>,
        line1: String,
        line2: String,
        interpAgrees: Boolean,
        label: String,
        line1Slot2Key: String? = null,
    ) {
        val kDoc = RuntimeLaws.doc().group(ORDER, 1)
        seedKernel(kDoc, refs, line1Slot2Key)
        val groovy = RuntimeLaws.compute(model, kDoc).filter { it.path() == TOTAL }
            .associate { it.pointer() to state(it.cleared(), it.errored(), it.value().orElse("")) }
        assertEquals(line1, groovy["/Order[1]/Lines[1]/Slots[1]/Total"], "$label — groovy, Lines[1]")
        assertEquals(line2, groovy["/Order[1]/Lines[2]/Slots[1]/Total"], "$label — groovy, Lines[2] (the sibling)")

        // The static-Java projection carries no instance pointer, so compare the outcome MULTISET.
        val kDoc2 = RuntimeLaws.doc().group(ORDER, 1)
        seedKernel(kDoc2, refs, line1Slot2Key)
        val engine = RuntimeLaws.kernelJavaEngine(model)
        val javaStatic = engine.computeOutcomesSorted(engine.instance(kDoc2))
            .filter { it.startsWith("$TOTAL|") }
            .map {
                it.substringAfter("$TOTAL|")
                    .let { v -> if (v == "ERR") "ERRORED" else if (v == "CLEARED") "CLEARED" else "VALUE|$v" }
            }
            .sorted()
        assertEquals(groovy.values.sorted(), javaStatic, "$label — dynamic-Groovy vs static-Java")

        val def = ModelLoader.load(model)
        val iDoc = def.newDocument()
        iDoc.group(ORDER, 1)
        seedInterp(iDoc, refs, line1Slot2Key)
        val interp = DmInterpreter(def).compute(iDoc).filter { it.path == TOTAL }
            .associate { c -> c.indices to state(c.outcome.name == "CLEARED", c.outcome.name == "ERRORED", c.value ?: "") }
        if (interpAgrees) {
            assertEquals(line1, interp[listOf(1, 1, 1, 1)], "$label — interpreter, Lines[1]")
            assertEquals(line2, interp[listOf(1, 2, 1, 1)], "$label — interpreter, Lines[2]")
        }
    }

    private fun state(cleared: Boolean, errored: Boolean, value: String) =
        if (cleared) "CLEARED" else if (errored) "ERRORED" else "VALUE|$value"

    private fun seedKernel(d: RuntimeLaws.Doc, refs: List<Pair<String, String>>, line1Slot2Key: String?) {
        d.group(LINES, 1, 1).group(SLOTS, 1, 1, 1)
        d.field(KEY, "1", 1, 1, 1, 1); d.field(TOTAL, "999", 1, 1, 1, 1)
        line1Slot2Key?.let {
            d.group(SLOTS, 1, 1, 2)
            d.field(KEY, it, 1, 1, 2, 1); d.field(TOTAL, "999", 1, 1, 2, 1)
        }
        d.group(LINES, 1, 2).group(SLOTS, 1, 2, 1)
        d.field(KEY, "2", 1, 2, 1, 1); d.field(TOTAL, "999", 1, 2, 1, 1)
        refs.forEachIndexed { i, (k, a) ->
            val r = i + 1
            d.group(REFS, 1, r); d.field(REF_KEY, k, 1, r, 1); d.field(REF_AMOUNT, a, 1, r, 1)
        }
    }

    private fun seedInterp(i: Document, refs: List<Pair<String, String>>, line1Slot2Key: String?) {
        i.group(LINES, 1, 1).group(SLOTS, 1, 1, 1)
        i.field(KEY, "1", 1, 1, 1, 1); i.field(TOTAL, "999", 1, 1, 1, 1)
        line1Slot2Key?.let {
            i.group(SLOTS, 1, 1, 2)
            i.field(KEY, it, 1, 1, 2, 1); i.field(TOTAL, "999", 1, 1, 2, 1)
        }
        i.group(LINES, 1, 2).group(SLOTS, 1, 2, 1)
        i.field(KEY, "2", 1, 2, 1, 1); i.field(TOTAL, "999", 1, 2, 1, 1)
        refs.forEachIndexed { idx, (k, a) ->
            val r = idx + 1
            i.group(REFS, 1, r); i.field(REF_KEY, k, 1, r, 1); i.field(REF_AMOUNT, a, 1, r, 1)
        }
    }
}
