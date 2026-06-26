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
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel
import io.github.mbackschat.a12.dm.rulekit.dsl.Condition
import io.github.mbackschat.a12.dm.rulekit.dsl.DslRenderer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.function.Consumer

/**
 * Differential — **framed parallel iteration** (a repeatable, non-indexed group *between* the common ancestor
 * and one joined group): `/Plan/X(rep)/A(rep, idx Dept)` joined with `/Plan/B(rep, idx Dept)`. The kernel
 * iterates the frame `X` normally and does the index-keyed join INSIDE each `X` row, with `B` shared across all
 * `X` rows (KERNEL-FINDINGS §9 — the general `/Plan/X/A ⋈ B` case). The condition must be POSITIVE (a negative
 * operator over this shape is `MVK_NEG_CONDITION_IN_ITERATION`; an indexed frame is `MVK_MULTIPLE_INDEX_FIELDS`
 * — both kernel-invalid, so this non-indexed-frame + positive shape is the only valid one). This locks the
 * frame iteration: the framed group's rows/values are scoped per `X` row, `B` is shared, and the pointer
 * carries the real `X` index with `-5` only on the unmatched join level.
 */
class FramedParallelIterationDiffTest {

    private val A = "/Plan/X/A"
    private val B = "/Plan/B"
    private val structureJson = DocumentModelDraft.named("plan").locale("en_US")
        .rootGroup("Plan", Consumer { root ->
            root.group("X", Consumer { x ->
                x.repeatable(50)   // a repeatable FRAME with no index field
                x.group("A", Consumer { g ->
                    g.repeatable(50).indexField("Dept"); g.field("Dept", FieldKind.STRING)
                    g.numberField("Va", Consumer<NumberFieldBuilder> { it.fractionDigits(0) }) })
            })
            root.group("B", Consumer { g ->
                g.repeatable(50).indexField("Dept"); g.field("Dept", FieldKind.STRING)
                g.numberField("Vb", Consumer<NumberFieldBuilder> { it.fractionDigits(0) }) })
        })
        .build().toJson()

    // Conditions are authored with the rulekit typed DSL ([m]) and rendered to the canonical string fed to BOTH
    // engines — no free-text condition literals. Refs use ABSOLUTE paths; the renderer emits absolute and both
    // engines accept it (IF24). The cross-group join (A under frame X ⋈ shared B) is implicit in the field paths.
    private val m = BoundModel.ofJson(structureJson)
    private fun render(c: Condition): String = DslRenderer().render(c)
    private val cond = render(m.field("$A/Va").filled().and(m.field("$B/Vb").filled())
        .and(m.num("$A/Va").gt(m.num("$B/Vb"))))
    private val def = ModelDef(
        listOf(
            FieldDef("$A/Dept", Kind.STRING), FieldDef("$A/Va", Kind.NUMBER, scale = 0),
            FieldDef("$B/Dept", Kind.STRING), FieldDef("$B/Vb", Kind.NUMBER, scale = 0),
        ),
        listOf(RuleDef("/Plan/PI", "/Plan", cond, "PI", "../X/A/Va")),
        repeatableGroups = setOf("/Plan/X", A, B),
        declaredReps = mapOf("/Plan/X" to 50, A to 50, B to 50),
        indexFields = setOf("$A/Dept", "$B/Dept"),
    )

    /** xs[k] = the A rows (dept,va) under X row k+1 ; b = the B rows (dept,vb). */
    private fun build(sink: PlacementSink, xs: List<List<Pair<String?, String?>>>, b: List<Pair<String?, String?>>) {
        sink.group("/Plan", 1)
        xs.forEachIndexed { xi, aRows ->
            sink.group("/Plan/X", 1, xi + 1)
            aRows.forEachIndexed { ai, (dept, va) ->
                sink.group(A, 1, xi + 1, ai + 1)
                dept?.let { sink.field("$A/Dept", it, 1, xi + 1, ai + 1, 1) }
                va?.let { sink.field("$A/Va", it, 1, xi + 1, ai + 1, 1) }
            }
        }
        b.forEachIndexed { bi, (dept, vb) ->
            sink.group(B, 1, bi + 1)
            dept?.let { sink.field("$B/Dept", it, 1, bi + 1, 1) }
            vb?.let { sink.field("$B/Vb", it, 1, bi + 1, 1) }
        }
    }

    private fun kernelSigs(xs: List<List<Pair<String?, String?>>>, b: List<Pair<String?, String?>>): List<String> {
        val model = RuntimeLaws.withRule(structureJson, "/Plan", "PI", "../X/A/Va", "PI", cond)
        val kDoc = RuntimeLaws.doc()
        build(object : PlacementSink {
            override fun group(path: String, vararg reps: Int) { kDoc.group(path, *reps) }
            override fun field(path: String, value: String, vararg reps: Int) { kDoc.field(path, value, *reps) }
            override fun emptyField(path: String, vararg reps: Int) { kDoc.emptyField(path, *reps) }
        }, xs, b)
        return RuntimeLaws.messages(model, kDoc).filter { it.code() == "PI" }
            .map { "${it.code()}|${it.type().name}|${it.field()}" }.sorted()
    }

    private fun assertAgrees(xs: List<List<Pair<String?, String?>>>, b: List<Pair<String?, String?>>) {
        val iDoc = Document(def.kinds, def.declaredReps, def.unsignedFields, def.rangeConfig, def.dateFormats, def.scales)
        build(iDoc.asSink(), xs, b)
        val iSigs = DmInterpreter(def).validateFull(iDoc).filter { it.code == "PI" }
            .map { "${it.code}|${if (it.type == MsgType.OMISSION) "OMISSION_ERROR" else "VALUE_ERROR"}|${it.errorPath}" }.sorted()
        assertEquals(kernelSigs(xs, b), iSigs, "framed parallel-iteration signatures must match the kernel (xs=$xs b=$b)")
    }

    @Test
    fun frameIteratesNormallyAndJoinsInsideEachRow() {
        // X1: A=[Sales:9, Eng:3] ; X2: A=[Sales:1]. B=[Sales:5, Ops:2] (shared across X rows).
        //  X1/Sales: 9>5 → fire (VALUE) at /Plan/X[1]/A[1]/Va ; X1/Eng: B has no Eng → guard false ;
        //  X2/Sales: 1>5 false. So exactly one PI; the frame's X index must be 1 (real), not -5.
        val xs = listOf(
            listOf("Sales" to "9", "Eng" to "3"),
            listOf("Sales" to "1"),
        )
        val b = listOf("Sales" to "5", "Ops" to "2")
        assertTrue(kernelSigs(xs, b).isNotEmpty(), "precondition: kernel fires PI here")
        assertAgrees(xs, b)
    }

    @Test
    fun frameWithUnmatchedAndInvalidIndexMatchesKernel() {
        // X1: A=[Sales:9] ; X2: A=[Ops:7, (empty Dept):2 → invalid index in A under X2]. B=[Sales:5, Ops:1].
        // Exercises: matched (X1/Sales 9>5), B-only / A-only across frames, and the invalid-index 3VL inside a frame row.
        val xs = listOf(
            listOf("Sales" to "9"),
            listOf("Ops" to "7", null to "2"),
        )
        val b = listOf("Sales" to "5", "Ops" to "1")
        assertAgrees(xs, b)
    }
}
