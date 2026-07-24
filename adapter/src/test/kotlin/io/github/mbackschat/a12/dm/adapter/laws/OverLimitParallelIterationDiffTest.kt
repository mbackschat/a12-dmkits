package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.interpreter.DmInterpreter
import io.github.mbackschat.a12.dm.interpreter.eval.Document
import io.github.mbackschat.a12.dm.interpreter.eval.formatPointer
import io.github.mbackschat.a12.dm.interpreter.eval.newDocument
import io.github.mbackschat.a12.dm.interpreter.load.ModelLoader
import io.github.mbackschat.a12.dm.interpreter.model.ComputeOutcome
import io.github.mbackschat.a12.dm.interpreter.model.MsgType
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft.GroupBuilder
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel
import io.github.mbackschat.a12.dm.rulekit.dsl.Condition
import io.github.mbackschat.a12.dm.rulekit.dsl.DslRenderer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.util.function.Consumer

/**
 * IF220 (graduated from IG103) — an OVER-LIMIT row inside a PARALLEL ITERATION (the index-keyed outer join, KERNEL-SEMANTICS §9).
 * The over-repetition gate keeps an over-limit coordinate out of the kernel's evaluation cache (its
 * `IndexFieldCache`, SPEC-2026-07-23-13), so it is NOT a join key: a reference to it reads UNMATCHED
 * (empty-as-0), never its poisoned cell. The interpreter's `parallelContexts` used to read the join rows
 * unfiltered, so the over-limit row joined and — in a COMPUTATION — its cell read poisoned the reader (a clear
 * where the kernel computes empty-as-0). The fix excludes over-limit rows at the shared `overLimitRows` root the
 * star/quantifier domain uses (no second walker), across the joined-group map, the common-parent rows, and the
 * frame-row expansion. Locked in BOTH validation and compute, plain and framed (`/Plan/X(frame)/A ⋈ B`), across
 * dynamic-Groovy (normative) · static-Java · interpreter. Own e-commerce/insurance domains.
 */
class OverLimitParallelIterationDiffTest {

    // ── plain: sibling groups Demand/Supply/Total (cap 2, shared Category index) under /Ledger ───────────────

    private val base = DocumentModelDraft.named("ledger").locale("en_US")
        .rootGroup("Ledger", Consumer { g: GroupBuilder ->
            for (name in listOf("Demand", "Supply", "Total")) {
                g.group(name, Consumer { s ->
                    s.repeatable(2).indexField("Category")
                        .stringField("Category", Consumer { })
                        .numberField("Amount", Consumer { })
                })
            }
        }).build().toJson()

    private val m = BoundModel.ofJson(base)
    private fun render(c: Condition): String = DslRenderer().render(c)
    private val DEMAND_AMT = "/Ledger/Demand/Amount"
    private val SUPPLY_AMT = "/Ledger/Supply/Amount"

    private fun valSigs(msgs: List<RuntimeLaws.Msg>, code: String) =
        msgs.filter { it.code() == code }.map { "${it.code()}|${it.type().name}|${it.field()}" }.sorted()

    private fun placeGroups(place: (String, String, IntArray) -> Unit, group: (String, IntArray) -> Unit,
            rows: Map<String, List<Pair<String, String>>>) {
        group("/Ledger", intArrayOf(1))
        rows.forEach { (g, list) ->
            list.forEachIndexed { i, (k, v) ->
                group("/Ledger/$g", intArrayOf(1, i + 1))
                place("/Ledger/$g/Category", k, intArrayOf(1, i + 1, 1))
                place("/Ledger/$g/Amount", v, intArrayOf(1, i + 1, 1))
            }
        }
    }

    /** Validation: a per-row unguarded compare `[Demand/Amount] > [Supply/Amount]` across the Demand ⋈ Supply join. */
    private fun validationAgree(msg: String, rows: Map<String, List<Pair<String, String>>>) {
        val cond = render(m.num(DEMAND_AMT).gt(m.num(SUPPLY_AMT)))
        val model = RuntimeLaws.withRule(base, "/Ledger", "RW", "../Demand/Amount", "RW", cond)
        fun kDoc(): RuntimeLaws.Doc {
            val d = RuntimeLaws.doc(); placeGroups({ p, v, r -> d.field(p, v, *r) }, { p, r -> d.group(p, *r) }, rows); return d
        }
        val def = ModelLoader.load(model)
        val iDoc = def.newDocument()
        placeGroups({ p, v, r -> iDoc.field(p, v, *r) }, { p, r -> iDoc.group(p, *r) }, rows)

        val groovy = valSigs(RuntimeLaws.messages(model, kDoc()), "RW")
        val e = RuntimeLaws.kernelJavaEngine(model)
        val java = valSigs(e.messages(e.instance(kDoc())), "RW")
        val interp = DmInterpreter(def).validateFull(iDoc).filter { it.code == "RW" }
            .map { "${it.code}|${if (it.type == MsgType.OMISSION) "OMISSION_ERROR" else "VALUE_ERROR"}|${it.errorPath}" }.sorted()
        assertEquals(groovy, java, "$msg — groovy vs java-static")
        assertEquals(groovy, interp, "$msg — kernel vs interpreter  kernel=$groovy interp=$interp")
    }

    /** Demand[3]=Extra is over cap 2. Sales 5>5 false; Eng 3>0 OMISSION at Demand[2]; the over-limit Extra row is
     *  outside the join, so it fires nothing (its cell reads unavailable either way — the interpreter agrees). */
    @Test fun validationOverLimitJoinedGroupRow() = validationAgree(
        "over-limit Demand[3]=Extra is outside the join",
        mapOf("Demand" to listOf("Sales" to "5", "Eng" to "3", "Extra" to "9"), "Supply" to listOf("Sales" to "5")),
    )

    // ── plain compute: Total/Amount = [../Demand/Amount] + [../Supply/Amount], join-by-Category ──────────────

    private fun kSig(c: RuntimeLaws.Computed): String =
        if (c.cleared()) "CLEARED" else if (c.errored()) "ERRORED" else "VALUE|${c.value().orElse("")}"

    private fun iSig(c: io.github.mbackschat.a12.dm.interpreter.model.Computed): String = when (c.outcome) {
        ComputeOutcome.CLEARED -> "CLEARED"
        ComputeOutcome.ERRORED -> "ERRORED"
        ComputeOutcome.VALUE -> "VALUE|${c.value}"
    }

    private fun computeAgree(msg: String, expect: Map<String, String>, rows: Map<String, List<Pair<String, String>>>, totalKeys: List<String>) {
        val model = RuntimeLaws.withComputation(base, "/Ledger/Total", "TotalComp", "../Amount",
            "[../Demand/Amount] + [../Supply/Amount]")
        fun place(place: (String, String, IntArray) -> Unit, group: (String, IntArray) -> Unit) {
            placeGroups(place, group, rows)
            totalKeys.forEachIndexed { i, k ->
                group("/Ledger/Total", intArrayOf(1, i + 1)); place("/Ledger/Total/Category", k, intArrayOf(1, i + 1, 1))
                place("/Ledger/Total/Amount", "99", intArrayOf(1, i + 1, 1))   // sentinel seed → any computed value reports
            }
        }
        val kDoc = RuntimeLaws.doc(); place({ p, v, r -> kDoc.field(p, v, *r) }, { p, r -> kDoc.group(p, *r) })
        val kDoc2 = RuntimeLaws.doc(); place({ p, v, r -> kDoc2.field(p, v, *r) }, { p, r -> kDoc2.group(p, *r) })
        val def = ModelLoader.load(model)
        val iDoc = def.newDocument(); place({ p, v, r -> iDoc.field(p, v, *r) }, { p, r -> iDoc.group(p, *r) })

        fun total(map: Map<String, String>) = map.filterKeys { it.contains("/Total") }
        val groovy = total(RuntimeLaws.compute(model, kDoc).associate { "${it.path()}@${it.pointer()}" to kSig(it) })
        val e = RuntimeLaws.kernelJavaEngine(model)
        val java = total(e.compute(e.instance(kDoc2)).associate { "${it.path()}@${it.pointer()}" to kSig(it) })
        val interp = DmInterpreter(def).compute(iDoc).filter { it.path == "/Ledger/Total/Amount" }
            .associate { "${it.path}@${formatPointer(it.path, it.indices)}" to iSig(it) }
        assertEquals(expect, groovy, "$msg — kernel groovy-dynamic")
        assertEquals(groovy, java, "$msg — groovy vs java-static")
        assertEquals(groovy, interp, "$msg — kernel vs interpreter  kernel=$groovy interp=$interp")
    }

    /** Total[Extra] joins Demand's Extra key — but Demand's Extra row is OVER-LIMIT (row 3, cap 2), so it is
     *  outside the join: the side reads empty-as-0 and Total[Extra] computes 0, never poisoned/cleared. */
    @Test fun computeOverLimitJoinedSideRowReadsEmptyAsZero() = computeAgree(
        "over-limit Demand[3]=Extra excluded from the join → Total[Extra] empty-as-0 (not poisoned)",
        mapOf(
            "/Ledger/Total/Amount@/Ledger[1]/Total[1]/Amount" to "VALUE|10",
            "/Ledger/Total/Amount@/Ledger[1]/Total[2]/Amount" to "VALUE|0",
        ),
        rows = mapOf("Demand" to listOf("Sales" to "5", "Eng" to "3", "Extra" to "9"), "Supply" to listOf("Sales" to "5")),
        totalKeys = listOf("Sales", "Extra"),
    )

    // ── framed outer-join: /Plan/X(frame, cap 2)/A(cap 2, idx Dept) ⋈ /Plan/B(cap 2, idx Dept) ───────────────

    private val framed = DocumentModelDraft.named("plan").locale("en_US")
        .rootGroup("Plan", Consumer { root ->
            root.group("X", Consumer { x ->
                x.repeatable(2)
                x.group("A", Consumer { g ->
                    g.repeatable(2).indexField("Dept").stringField("Dept", Consumer { }).numberField("Va", Consumer { })
                })
            })
            root.group("B", Consumer { g ->
                g.repeatable(2).indexField("Dept").stringField("Dept", Consumer { }).numberField("Vb", Consumer { })
            })
        }).build().toJson()

    private val fm = BoundModel.ofJson(framed)
    private val VA = "/Plan/X/A/Va"
    private val VB = "/Plan/B/Vb"

    /** Framed validation: positive `[X/A/Va] filled And [B/Vb] filled And [X/A/Va] > [B/Vb]` (a negative operator
     *  over this shape is MVK-invalid). The frame X and its A rows scope per X row; B is shared. */
    private fun framedValidationAgree(msg: String, xs: List<List<Pair<String, String>>>, b: List<Pair<String, String>>) {
        val cond = render(fm.field(VA).filled().and(fm.field(VB).filled()).and(fm.num(VA).gt(fm.num(VB))))
        val model = RuntimeLaws.withRule(framed, "/Plan", "PI", "../X/A/Va", "PI", cond)
        fun place(place: (String, String, IntArray) -> Unit, group: (String, IntArray) -> Unit) {
            group("/Plan", intArrayOf(1))
            xs.forEachIndexed { xi, aRows ->
                group("/Plan/X", intArrayOf(1, xi + 1))
                aRows.forEachIndexed { ai, (dept, va) ->
                    group("/Plan/X/A", intArrayOf(1, xi + 1, ai + 1))
                    place("/Plan/X/A/Dept", dept, intArrayOf(1, xi + 1, ai + 1, 1))
                    place("/Plan/X/A/Va", va, intArrayOf(1, xi + 1, ai + 1, 1))
                }
            }
            b.forEachIndexed { bi, (dept, vb) ->
                group("/Plan/B", intArrayOf(1, bi + 1))
                place("/Plan/B/Dept", dept, intArrayOf(1, bi + 1, 1)); place("/Plan/B/Vb", vb, intArrayOf(1, bi + 1, 1))
            }
        }
        val kDoc = RuntimeLaws.doc(); place({ p, v, r -> kDoc.field(p, v, *r) }, { p, r -> kDoc.group(p, *r) })
        val kDoc2 = RuntimeLaws.doc(); place({ p, v, r -> kDoc2.field(p, v, *r) }, { p, r -> kDoc2.group(p, *r) })
        val def = ModelLoader.load(model)
        val iDoc = def.newDocument(); place({ p, v, r -> iDoc.field(p, v, *r) }, { p, r -> iDoc.group(p, *r) })

        val groovy = valSigs(RuntimeLaws.messages(model, kDoc), "PI")
        val e = RuntimeLaws.kernelJavaEngine(model)
        val java = valSigs(e.messages(e.instance(kDoc2)), "PI")
        val interp = DmInterpreter(def).validateFull(iDoc).filter { it.code == "PI" }
            .map { "${it.code}|${if (it.type == MsgType.OMISSION) "OMISSION_ERROR" else "VALUE_ERROR"}|${it.errorPath}" }.sorted()
        assertEquals(groovy, java, "$msg — groovy vs java-static")
        assertEquals(groovy, interp, "$msg — kernel vs interpreter  kernel=$groovy interp=$interp")
    }

    /** An over-limit FRAME row X[3] (cap 2): the join never runs inside it, so its A rows fire nothing. In cap:
     *  X1/Sales 9>5 fires VALUE; X2/Sales 1>5 false. */
    @Test fun framedOverLimitFrameRow() = framedValidationAgree(
        "over-limit frame X[3] is outside the frame-row expansion",
        xs = listOf(listOf("Sales" to "9"), listOf("Sales" to "1"), listOf("Sales" to "7")),
        b = listOf("Sales" to "5"),
    )

    /** An over-limit row A[3] inside an in-cap frame X1: that A row is outside the join, so X1/Extra fires nothing;
     *  X1/Sales 9>5 fires VALUE. */
    @Test fun framedOverLimitFramedGroupRow() = framedValidationAgree(
        "over-limit framed-group row A[3] under X[1] is outside the join",
        xs = listOf(listOf("Sales" to "9", "Eng" to "3", "Extra" to "8")),
        b = listOf("Sales" to "5"),
    )

    // ── framed COMPUTE: a target under the frame reads a shared sibling with an over-limit row ────────────────

    private val framedCompute = DocumentModelDraft.named("planc").locale("en_US")
        .rootGroup("Plan", Consumer { root ->
            root.group("X", Consumer { x ->
                x.repeatable(2)
                x.group("A", Consumer { g ->
                    g.repeatable(2).indexField("Dept").stringField("Dept", Consumer { })
                        .numberField("Result", Consumer { })
                })
            })
            root.group("B", Consumer { g ->
                g.repeatable(2).indexField("Dept").stringField("Dept", Consumer { }).numberField("Vb", Consumer { })
            })
        }).build().toJson()

    /** `A/Result = [../../B/Vb]` joins the framed A (under X) with the shared B by Dept. B carries an OVER-LIMIT
     *  row (B[3]=Extra, cap 2); the in-cap A[2]=Extra under X[1] reads it. The over-limit B row is outside the
     *  join, so A/Result@Extra reads empty-as-0 (0), never B's poisoned over-limit cell. */
    @Test fun framedComputeOverLimitSharedSideRow() {
        val model = RuntimeLaws.withComputation(framedCompute, "/Plan/X/A", "R", "../Result", "[../../B/Vb]")
        fun place(place: (String, String, IntArray) -> Unit, group: (String, IntArray) -> Unit) {
            group("/Plan", intArrayOf(1)); group("/Plan/X", intArrayOf(1, 1))
            group("/Plan/X/A", intArrayOf(1, 1, 1)); place("/Plan/X/A/Dept", "Sales", intArrayOf(1, 1, 1, 1)); place("/Plan/X/A/Result", "99", intArrayOf(1, 1, 1, 1))
            group("/Plan/X/A", intArrayOf(1, 1, 2)); place("/Plan/X/A/Dept", "Extra", intArrayOf(1, 1, 2, 1)); place("/Plan/X/A/Result", "99", intArrayOf(1, 1, 2, 1))
            group("/Plan/B", intArrayOf(1, 1)); place("/Plan/B/Dept", "Sales", intArrayOf(1, 1, 1)); place("/Plan/B/Vb", "5", intArrayOf(1, 1, 1))
            group("/Plan/B", intArrayOf(1, 2)); place("/Plan/B/Dept", "Eng", intArrayOf(1, 2, 1)); place("/Plan/B/Vb", "3", intArrayOf(1, 2, 1))
            group("/Plan/B", intArrayOf(1, 3)); place("/Plan/B/Dept", "Extra", intArrayOf(1, 3, 1)); place("/Plan/B/Vb", "9", intArrayOf(1, 3, 1))  // over cap 2
        }
        val kDoc = RuntimeLaws.doc(); place({ p, v, r -> kDoc.field(p, v, *r) }, { p, r -> kDoc.group(p, *r) })
        val kDoc2 = RuntimeLaws.doc(); place({ p, v, r -> kDoc2.field(p, v, *r) }, { p, r -> kDoc2.group(p, *r) })
        val def = ModelLoader.load(model)
        val iDoc = def.newDocument(); place({ p, v, r -> iDoc.field(p, v, *r) }, { p, r -> iDoc.group(p, *r) })

        val groovy = RuntimeLaws.compute(model, kDoc).associate { "${it.path()}@${it.pointer()}" to kSig(it) }
        val e = RuntimeLaws.kernelJavaEngine(model)
        val java = e.compute(e.instance(kDoc2)).associate { "${it.path()}@${it.pointer()}" to kSig(it) }
        val interp = DmInterpreter(def).compute(iDoc).filter { it.path == "/Plan/X/A/Result" }
            .associate { "${it.path}@${formatPointer(it.path, it.indices)}" to iSig(it) }
        val expect = mapOf(
            "/Plan/X/A/Result@/Plan[1]/X[1]/A[1]/Result" to "VALUE|5",   // Sales matches B[1]=5
            "/Plan/X/A/Result@/Plan[1]/X[1]/A[2]/Result" to "VALUE|0",   // Extra: B's Extra is over-limit → empty-as-0
        )
        assertEquals(expect, groovy, "framed compute — kernel groovy-dynamic  groovy=$groovy")
        assertEquals(groovy, java, "framed compute — groovy vs java-static")
        assertEquals(groovy, interp, "framed compute — kernel vs interpreter  kernel=$groovy interp=$interp")
    }
}
