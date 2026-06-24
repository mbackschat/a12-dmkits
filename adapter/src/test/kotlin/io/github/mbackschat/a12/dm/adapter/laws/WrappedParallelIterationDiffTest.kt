package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.interpreter.DmInterpreter
import io.github.mbackschat.a12.dm.interpreter.eval.Document
import io.github.mbackschat.a12.dm.interpreter.gen.PlacementSink
import io.github.mbackschat.a12.dm.interpreter.gen.asSink
import io.github.mbackschat.a12.dm.interpreter.model.FieldDef
import io.github.mbackschat.a12.dm.interpreter.model.Kind
import io.github.mbackschat.a12.dm.interpreter.model.ModelDef
import io.github.mbackschat.a12.dm.interpreter.model.MsgType
import io.github.mbackschat.a12.dm.interpreter.model.RuleDef
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import io.github.mbackschat.a12.dm.modelkit.FieldKind
import io.github.mbackschat.a12.dm.modelkit.NumberFieldBuilder
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.function.Consumer

/**
 * Differential — **wrapped / asymmetric-depth parallel iteration**. The joined repeatable groups are NOT direct
 * children of their common ancestor: `A` sits under a non-repeatable wrapper `W1` and `B` under `W2`, both below
 * `/Plan`. The kernel iterates them jointly all the same (the non-repeatable wrappers are transparent, rendered
 * `[1]` — e.g. `/Plan[1]/W1[1]/A[-5]/Va` for the synthetic side, probed). This locks the interpreter's handling
 * of the shape its sibling tests can't reach: the pointer's non-rep wrapper level stays `[1]` while only the
 * repeatable level goes to `-5` (KERNEL-FINDINGS §9 / INTERPRETER-FINDINGS IF19).
 */
class WrappedParallelIterationDiffTest {

    private val A = "/Plan/W1/A"
    private val B = "/Plan/W2/B"
    private val structureJson = DocumentModelDraft.named("plan").locale("en_US")
        .rootGroup("Plan", Consumer { root ->
            root.group("W1", Consumer { w -> w.group("A", Consumer { g ->
                g.repeatable(50).indexField("Dept"); g.field("Dept", FieldKind.STRING)
                g.numberField("Va", Consumer<NumberFieldBuilder> { it.fractionDigits(0) }) }) })
            root.group("W2", Consumer { w -> w.group("B", Consumer { g ->
                g.repeatable(50).indexField("Dept"); g.field("Dept", FieldKind.STRING)
                g.numberField("Vb", Consumer<NumberFieldBuilder> { it.fractionDigits(0) }) }) })
        })
        .build().toJson()
    private val cond = "FieldsNotCollectivelyFilled(W1/A/Va, W2/B/Vb)"
    private val def = ModelDef(
        listOf(
            FieldDef("$A/Dept", Kind.STRING), FieldDef("$A/Va", Kind.NUMBER, scale = 0),
            FieldDef("$B/Dept", Kind.STRING), FieldDef("$B/Vb", Kind.NUMBER, scale = 0),
        ),
        listOf(RuleDef("/Plan/PI", "/Plan", cond, "PI", "../W1/A/Va")),
        repeatableGroups = setOf(A, B),
        declaredReps = mapOf(A to 50, B to 50),
        indexFields = setOf("$A/Dept", "$B/Dept"),
    )

    /** A=[(dept,va)…], B=[(dept,vb)…] — a null value leaves that cell empty. */
    private fun build(sink: PlacementSink, a: List<Pair<String?, String?>>, b: List<Pair<String?, String?>>) {
        sink.group("/Plan", 1)
        sink.group("/Plan/W1", 1, 1)
        a.forEachIndexed { i, (dept, va) ->
            sink.group(A, 1, 1, i + 1)
            dept?.let { sink.field("$A/Dept", it, 1, 1, i + 1, 1) }
            va?.let { sink.field("$A/Va", it, 1, 1, i + 1, 1) }
        }
        sink.group("/Plan/W2", 1, 1)
        b.forEachIndexed { i, (dept, vb) ->
            sink.group(B, 1, 1, i + 1)
            dept?.let { sink.field("$B/Dept", it, 1, 1, i + 1, 1) }
            vb?.let { sink.field("$B/Vb", it, 1, 1, i + 1, 1) }
        }
    }

    private fun assertAgrees(a: List<Pair<String?, String?>>, b: List<Pair<String?, String?>>) {
        val model = RuntimeLaws.withRule(structureJson, "/Plan", "PI", "../W1/A/Va", "PI", cond)
        val kDoc = RuntimeLaws.doc()
        build(object : PlacementSink {
            override fun group(path: String, vararg reps: Int) { kDoc.group(path, *reps) }
            override fun field(path: String, value: String, vararg reps: Int) { kDoc.field(path, value, *reps) }
            override fun emptyField(path: String, vararg reps: Int) { kDoc.emptyField(path, *reps) }
        }, a, b)
        val iDoc = Document(def.kinds, def.declaredReps, def.unsignedFields, def.rangeConfig, def.dateFormats, def.scales)
        build(iDoc.asSink(), a, b)

        val kSigs = RuntimeLaws.messages(model, kDoc).filter { it.code() == "PI" }
            .map { "${it.code()}|${it.type().name}|${it.field()}" }.sorted()
        val iSigs = DmInterpreter(def).validateFull(iDoc).filter { it.code == "PI" }
            .map { "${it.code}|${if (it.type == MsgType.OMISSION) "OMISSION_ERROR" else "VALUE_ERROR"}|${it.errorPath}" }.sorted()
        assertEquals(kSigs, iSigs, "wrapped parallel-iteration signatures must match the kernel (a=$a b=$b)")
    }

    @Test
    fun matchedAndSyntheticAndAOnlyMatchKernel() {
        // Sales matched (both filled → no fire); Eng A-only (A filled, B missing → fires A[1]); Ops B-only
        // (A missing → synthetic A[-5], W1 stays [1]). Exercises the wrapper-transparent pointer on both sides.
        assertAgrees(
            a = listOf("Sales" to "5", "Eng" to "3"),
            b = listOf("Sales" to "5", "Ops" to "2"),
        )
    }

    @Test
    fun invalidIndexInWrappedGroupMatchesKernel() {
        // B has an empty-Dept row (invalid index) → B's unmatched refs read 3VL UNKNOWN; the A-only Eng value is
        // suppressed where B is unknown, exactly as the kernel. Sanity: the kernel really fires.
        val a = listOf("Sales" to "5", "Eng" to "3")
        val b = listOf("Ops" to "2", null to "4")   // second B row: empty Dept
        val model = RuntimeLaws.withRule(structureJson, "/Plan", "PI", "../W1/A/Va", "PI", cond)
        val kDoc = RuntimeLaws.doc()
        build(object : PlacementSink {
            override fun group(path: String, vararg reps: Int) { kDoc.group(path, *reps) }
            override fun field(path: String, value: String, vararg reps: Int) { kDoc.field(path, value, *reps) }
            override fun emptyField(path: String, vararg reps: Int) { kDoc.emptyField(path, *reps) }
        }, a, b)
        assertTrue(RuntimeLaws.messages(model, kDoc).any { it.code() == "PI" }, "precondition: kernel fires PI")
        assertAgrees(a, b)
    }
}
