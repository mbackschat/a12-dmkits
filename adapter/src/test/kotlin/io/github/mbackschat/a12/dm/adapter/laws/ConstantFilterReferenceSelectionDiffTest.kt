package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.interpreter.DmInterpreter
import io.github.mbackschat.a12.dm.interpreter.eval.formatPointer
import io.github.mbackschat.a12.dm.interpreter.eval.newDocument
import io.github.mbackschat.a12.dm.interpreter.load.ModelLoader
import io.github.mbackschat.a12.dm.interpreter.model.ModelDef
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Differential — **a `Having` filter reference the star does not reopen is a CONSTANT, so the filter keeps
 * every candidate or none.** [KF237] measured the ancestor carrier's redundant-marker half; the two things
 * this adds are the carrier whose runtime our own record calls unrun, and the marked spelling neither estate
 * had measured.
 *
 * <p><b>Two carriers, same shape, different reach.</b> An unstarred ancestor ON the operand's path is constant
 * per HOST ROW; a nonrepeatable leaf is constant per DOCUMENT. Both resolve outside the reopened level, so
 * neither can vary across candidates — what differs is only how far the constancy reaches.
 *
 * <p><b>The middle host row carries the argument.</b> The same candidates answer `0` to the above-level filter
 * and `70` to the leaf filter in one run, which refutes a per-candidate re-read and an ignored reference at
 * the same time. Neither row alone does: an ignored reference would also give `70`, and a per-candidate read
 * would also give `0`.
 *
 * <p><b>An emptied domain is the aggregate's zero, not a clear.</b> Falsifying the single root cell moves
 * every leaf-filtered target to `0` with no outcome cleared, which is [KF234]'s empty-as-zero rule reaching a
 * filter that selects nothing at all rather than one whose rows are individually dropped.
 *
 * <p><b>Both arms and both spellings.</b> The `[$…]`-marked filter computes the identical per-host-row values,
 * and a validation rule over the same filter fires on exactly the gated-off host row — so all-or-nothing
 * selection is not a computation-only effect, and the marker does not change selection at a level the star did
 * not reopen. That marked row is what a12-kernel-lean's `SPEC-2026-09-06-07` records as unmeasured on either
 * side.
 *
 * <p><b>Both filters carry an in-scope conjunct, deliberately.</b> Without one the scope gate refuses the
 * filter outright ([KF244]), so the conjunct is what makes the two carriers comparable rather than one legal
 * and one unwritable — and it is true of every instantiated row here, so it drops nothing.
 *
 * <p>Own domain (a freight shipment order).
 */
class ConstantFilterReferenceSelectionDiffTest {

    private companion object {
        const val GROUP = "/Order"
        const val LINES = "/Order/Lines"
        const val ITEMS = "/Order/Lines/Items"
        const val GATE = "/Order/Gate"
        const val REF = "/Order/Ref"
        const val CAP = "/Order/Lines/Cap"
        const val NOTE = "/Order/Lines/Note"
        const val AMOUNT = "/Order/Lines/Items/Amount"
        const val IN_SCOPE = "FieldFilled(/Order/Lines/Items/Amount)"

        /** Three host rows gating 1, 0, 1 — the middle one is the row that carries the argument. */
        val ROWS = listOf(
            Triple(1, "1", listOf("10", "20")),
            Triple(2, "0", listOf("30", "40")),
            Triple(3, "1", listOf("50")),
        )
    }

    private val order: String =
        DocumentModelDraft.named("shipment-order").locale("en_US")
            .rootGroup("Order") { g ->
                g.stringField("Ref") { }
                g.numberField("Gate") { n -> n.fractionDigits(0) }
                g.group("Lines") { l ->
                    l.repeatable(5)
                    l.stringField("Note") { }
                    l.numberField("Cap") { n -> n.fractionDigits(0) }
                    l.numberField("TotalAbove") { n -> n.fractionDigits(0) }
                    l.numberField("TotalMarked") { n -> n.fractionDigits(0) }
                    l.numberField("TotalLeaf") { n -> n.fractionDigits(0) }
                    l.group("Items") { i ->
                        i.repeatable(5)
                        i.numberField("Amount") { n -> n.fractionDigits(0) }
                    }
                }
            }
            .build().toJson()

    /** `Sum` over the star, filtered by a reference the star does not reopen, written three ways. */
    private fun filtered(reference: String): String =
        "Sum(/Order/Lines/Items*/Amount Having $reference > 0 And $IN_SCOPE)"

    private val aboveLevel = filtered("[/Order/Lines/Cap]")
    private val markedAboveLevel = filtered("[\$/Order/Lines/Cap]")
    private val nonrepeatableLeaf = filtered("[/Order/Gate]")

    private fun withTarget(target: String, operation: String): String =
        RuntimeLaws.withComputation(order, LINES, "${target}Comp", "../$target", operation)

    /** Three host rows gating 1, 0, 1, each with its own items; `gate` is the single root cell. */
    private fun doc(gate: String) = RuntimeLaws.doc().group(GROUP, 1).also { d ->
        d.field(REF, "SO-1", 1, 1)
        d.field(GATE, gate, 1, 1)
        ROWS.forEach { (row, cap, amounts) ->
            d.group(LINES, 1, row)
            d.field(NOTE, "line-$row", 1, row, 1)
            d.field(CAP, cap, 1, row, 1)
            amounts.forEachIndexed { index, amount ->
                d.group(ITEMS, 1, row, index + 1)
                d.field(AMOUNT, amount, 1, row, index + 1, 1)
            }
        }
    }

    private fun interpreterDoc(def: ModelDef, gate: String) =
        def.newDocument().group(GROUP, 1).also { d ->
            d.field(REF, "SO-1", 1, 1)
            d.field(GATE, gate, 1, 1)
            ROWS.forEach { (row, cap, amounts) ->
                d.group(LINES, 1, row)
                d.field(NOTE, "line-$row", 1, row, 1)
                d.field(CAP, cap, 1, row, 1)
                amounts.forEachIndexed { index, amount ->
                    d.group(ITEMS, 1, row, index + 1)
                    d.field(AMOUNT, amount, 1, row, index + 1, 1)
                }
            }
        }

    /**
     * The per-host-row values a filtered `Sum` computes, on both engines, asserted to agree. Keyed on the
     * POINTER channel, not on `path`: the kernel's `path` is the computation's DECLARATION and is identical
     * for all three rows, so keying on it would silently read one row three times.
     */
    private fun bothValues(target: String, operation: String, gate: String): List<String?> {
        val model = withTarget(target, operation)
        val outcomes = RuntimeLaws.compute(model, doc(gate)).associateBy { it.pointer() }
        val kernel = rowPointers(target).map { outcomes[it]?.value()?.orElse(null) }
        val def = ModelLoader.load(model)
        val computed = DmInterpreter(def).compute(interpreterDoc(def, gate))
            .associateBy { formatPointer(it.path, it.indices) }
        val interpreter = rowPointers(target).map { computed[it]?.value }
        assertEquals(kernel, interpreter, "engines disagree on $operation at gate=$gate")
        return kernel
    }

    /** Both engines write this address identically — the kernel's `pointer` channel and `formatPointer`. */
    private fun rowPointers(target: String): List<String> =
        (1..3).map { row -> "/Order[1]/Lines[$row]/$target" }

    /** An unstarred ancestor on the operand's path is constant PER HOST ROW. */
    @Test
    fun anAboveLevelReferenceSelectsAllOrNothingPerHostRow() {
        assertEquals(listOf("30", "0", "50"), bothValues("TotalAbove", aboveLevel, "1"),
            "the gated-off host row keeps no candidate; its neighbours keep every one")
    }

    /** A nonrepeatable leaf is constant PER DOCUMENT — the arm our own record had left unrun. */
    @Test
    fun aNonrepeatableLeafReferenceSelectsAllOrNothingPerDocument() {
        assertEquals(listOf("30", "70", "50"), bothValues("TotalLeaf", nonrepeatableLeaf, "1"),
            "the same candidates the above-level filter drops are kept here")
    }

    /**
     * The separator: one cell changes, and the whole document's selection with it. An emptied domain answers
     * the aggregate's zero and clears nothing.
     */
    @Test
    fun falsifyingTheSingleLeafCellEmptiesEveryDomainAndYieldsZeroNotAClear() {
        assertEquals(listOf("0", "0", "0"), bothValues("TotalLeaf", nonrepeatableLeaf, "0"),
            "an emptied filtered domain is the aggregate's zero")
    }

    /** The marked spelling at a level the star does not reopen selects identically. */
    @Test
    fun theMarkedSpellingSelectsIdenticallyAtANonReopenedLevel() {
        assertEquals(bothValues("TotalAbove", aboveLevel, "1"), bothValues("TotalMarked", markedAboveLevel, "1"),
            "the marker does not change selection at a level the star did not reopen")
    }

    /** The validation arm: a rule over the same filter fires on exactly the gated-off host row. */
    @Test
    fun theValidationArmFiresOnExactlyTheGatedOffHostRow() {
        val model = RuntimeLaws.withRule(order, LINES, "EmptySel", "../Note", "EMPTYSEL",
            "FieldFilled(/Order/Lines/Note) And $aboveLevel == 0")
        val kernel = RuntimeLaws.messages(model, doc("1"))
            .filter { it.code() == "EMPTYSEL" }.map { it.field() }.sorted()
        val def = ModelLoader.load(model)
        val interpreter = DmInterpreter(def).validate(interpreterDoc(def, "1")).messages
            .filter { it.code == "EMPTYSEL" }.map { it.errorPath }.sorted()
        assertEquals(kernel, interpreter, "engines disagree on where the validation arm attaches")
        assertEquals(listOf("/Order[1]/Lines[2]/Note"), kernel,
            "only the host row whose constant filter selects nothing reports")
    }
}
