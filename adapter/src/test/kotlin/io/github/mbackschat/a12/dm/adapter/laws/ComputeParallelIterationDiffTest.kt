package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.interpreter.DmInterpreter
import io.github.mbackschat.a12.dm.interpreter.eval.Document
import io.github.mbackschat.a12.dm.interpreter.eval.formatPointer
import io.github.mbackschat.a12.dm.interpreter.eval.newDocument
import io.github.mbackschat.a12.dm.interpreter.load.ModelLoader
import io.github.mbackschat.a12.dm.interpreter.model.ComputeOutcome
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft.GroupBuilder
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.util.function.Consumer

/**
 * Differential — a computation whose operands reference sibling REPEATABLE groups joined by a shared INDEX
 * field (the A3 round-2 CVV minimal): `Total* / Amount = [../Sales/Amount] + [../Refunds/Amount] +
 * [../Fees/Amount]`, all four groups indexed by `Category`. The kernel runs the **index-field parallel
 * iteration for computations** (KERNEL-SEMANTICS §9's outer join, applied to the calc): the Total row for key
 * K reads each sibling's row with the SAME key — join-by-key, not by physical row position. A referenced side
 * with NO row for the key does not compute that instance (the calc creates no repetitions and treats the
 * unmatched side per the kernel's own guard — pinned here), and a group with no rows at all computes nothing.
 * The interpreter's compute path never engaged its (rule-side) parallel machinery — the common-prefix rule
 * read row 1 of every sibling for EVERY target row and computed. Tri-check groovy-dynamic == java-static ==
 * interpreter; targets seeded so kernel clears report.
 */
class ComputeParallelIterationDiffTest {

    private val TOTAL = "/Ledger/Total/Amount"

    private val base = DocumentModelDraft.named("ledger").locale("en_US")
        .rootGroup("Ledger", Consumer { g: GroupBuilder ->
            for (name in listOf("Sales", "Refunds", "Fees", "Total")) {
                g.group(name, Consumer { s ->
                    s.repeatable(99).indexField("Category")
                        .stringField("Category", Consumer { })
                        .numberField("Amount", Consumer { })
                        .stringField("Note", Consumer { })
                })
            }
        }).build().toJson()

    private fun model(): String = RuntimeLaws.withComputation(base, "/Ledger/Total", "TotalComp", "../Amount",
        "[../Sales/Amount] + [../Refunds/Amount] + [../Fees/Amount]")

    private class Both(val kDoc: RuntimeLaws.Doc, val iDoc: Document) {
        fun group(path: String, vararg reps: Int): Both { kDoc.group(path, *reps); iDoc.group(path, *reps); return this }
        fun field(path: String, v: String, vararg reps: Int): Both { kDoc.field(path, v, *reps); iDoc.field(path, v, *reps); return this }
    }

    private fun agree(msg: String, expect: Map<String, String>, build: (Both) -> Unit) {
        val m = model()
        val def = ModelLoader.load(m)
        val b = Both(RuntimeLaws.doc(), def.newDocument())
        build(b)
        val groovy = RuntimeLaws.compute(m, b.kDoc).associate { "${it.path()}@${it.pointer()}" to kSig(it) }
        val engine = RuntimeLaws.kernelJavaEngine(m)
        val kDoc2 = RuntimeLaws.doc()
        build(Both(kDoc2, def.newDocument()))
        val javaStatic = engine.compute(engine.instance(kDoc2)).associate { "${it.path()}@${it.pointer()}" to kSig(it) }
        val interp = DmInterpreter(def).compute(b.iDoc).associate {
            "${it.path}@${formatPointer(it.path, it.indices)}" to iSig(it)
        }
        assertEquals(expect, groovy, "$msg — the kernel groovy-dynamic outcome")
        assertEquals(groovy, javaStatic, "$msg — groovy vs java-static (tri-check)")
        assertEquals(groovy, interp, "$msg — kernel vs interpreter")
    }

    private fun kSig(c: RuntimeLaws.Computed): String =
        if (c.cleared()) "CLEARED" else if (c.errored()) "ERRORED" else "VALUE|${c.value().orElse("")}"

    private fun iSig(c: io.github.mbackschat.a12.dm.interpreter.model.Computed): String = when (c.outcome) {
        ComputeOutcome.CLEARED -> "CLEARED"
        ComputeOutcome.ERRORED -> "ERRORED"
        ComputeOutcome.VALUE -> "VALUE|${c.value}"
    }

    private fun totalAt(r: Int) = "$TOTAL@/Ledger[1]/Total[$r]/Amount"

    /** Row r of [group] keyed [key], with an optional Amount (Note marks a set-but-amount-empty row). */
    private fun Both.row(group: String, r: Int, key: String, amount: String?, note: String? = null): Both {
        group("/Ledger/$group", 1, r).field("/Ledger/$group/Category", key, 1, r, 1)
        amount?.let { field("/Ledger/$group/Amount", it, 1, r, 1) }
        note?.let { field("/Ledger/$group/Note", it, 1, r, 1) }
        return this
    }

    @Test fun joinIsByIndexKeyNotRowPosition() =
        agree("Fees' physical row order is SCRAMBLED (K2 first) → Total@K1 still sums the K1 amounts",
            mapOf(totalAt(1) to "VALUE|111", totalAt(2) to "VALUE|222")) { d ->
            d.group("/Ledger", 1)
            d.row("Sales", 1, "K1", "100").row("Sales", 2, "K2", "200")
            d.row("Refunds", 1, "K1", "10").row("Refunds", 2, "K2", "20")
            d.row("Fees", 1, "K2", "2").row("Fees", 2, "K1", "1")
            d.row("Total", 1, "K1", "9").row("Total", 2, "K2", "9")
        }

    @Test fun aMissingRowForTheKeyOnOneSideReadsEmptyAsZero() =
        agree("Fees has no K2 row → the unmatched side reads EMPTY (empty-as-0) and the instance computes",
            mapOf(totalAt(1) to "VALUE|111", totalAt(2) to "VALUE|220")) { d ->
            d.group("/Ledger", 1)
            d.row("Sales", 1, "K1", "100").row("Sales", 2, "K2", "200")
            d.row("Refunds", 1, "K1", "10").row("Refunds", 2, "K2", "20")
            d.row("Fees", 1, "K1", "1")
            d.row("Total", 1, "K1", "9").row("Total", 2, "K2", "9")
        }

    @Test fun anUninstantiatedSideGroupReadsEmptyAsZero() =
        agree("Sales has NO rows → its side reads empty-as-0 for every key; the instances compute",
            mapOf(totalAt(1) to "VALUE|11", totalAt(2) to "VALUE|22")) { d ->
            d.group("/Ledger", 1)
            d.row("Refunds", 1, "K1", "10").row("Refunds", 2, "K2", "20")
            d.row("Fees", 1, "K1", "1").row("Fees", 2, "K2", "2")
            d.row("Total", 1, "K1", "9").row("Total", 2, "K2", "9")
        }

    @Test fun anEmptyAmountInAMatchedRowReadsAsZero() =
        agree("Fees' K2 row exists (Note filled) but its Amount is empty → empty-as-0",
            mapOf(totalAt(1) to "VALUE|111", totalAt(2) to "VALUE|220")) { d ->
            d.group("/Ledger", 1)
            d.row("Sales", 1, "K1", "100").row("Sales", 2, "K2", "200")
            d.row("Refunds", 1, "K1", "10").row("Refunds", 2, "K2", "20")
            d.row("Fees", 1, "K1", "1").row("Fees", 2, "K2", null, note = "waived")
            d.row("Total", 1, "K1", "9").row("Total", 2, "K2", "9")
        }

    @Test fun duplicateTargetKeysClearBothInstances() =
        agree("BOTH Total rows carry the SAME key (the corpus seed's actual state — uniqueIndex violated)",
            mapOf(totalAt(1) to "CLEARED", totalAt(2) to "CLEARED")) { d ->
            d.group("/Ledger", 1)
            d.row("Sales", 1, "K1", "100")
            d.row("Refunds", 1, "K1", "10")
            d.row("Fees", 1, "K1", "1")
            d.row("Total", 1, "K1", "9").row("Total", 2, "K1", "9")
        }

    @Test fun duplicateKeysOnAReferencedSideClearEveryInstance() =
        agree("Sales carries TWO K1 rows → the kernel marks ALL the calc's instances erroneously-calculated (its own \"more than strictly necessary\" guard) — even the clean K2 clears",
            mapOf(totalAt(1) to "CLEARED", totalAt(2) to "CLEARED")) { d ->
            d.group("/Ledger", 1)
            d.row("Sales", 1, "K1", "100").row("Sales", 2, "K1", "150").row("Sales", 3, "K2", "200")
            d.row("Refunds", 1, "K1", "10").row("Refunds", 2, "K2", "20")
            d.row("Fees", 1, "K1", "1").row("Fees", 2, "K2", "2")
            d.row("Total", 1, "K1", "9").row("Total", 2, "K2", "9")
        }
}
