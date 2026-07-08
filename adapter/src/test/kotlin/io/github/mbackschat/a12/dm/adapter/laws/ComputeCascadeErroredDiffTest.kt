package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.interpreter.DmInterpreter
import io.github.mbackschat.a12.dm.interpreter.eval.Document
import io.github.mbackschat.a12.dm.interpreter.model.ComputationDef
import io.github.mbackschat.a12.dm.interpreter.model.ComputeOutcome
import io.github.mbackschat.a12.dm.interpreter.model.FieldDef
import io.github.mbackschat.a12.dm.interpreter.model.Kind
import io.github.mbackschat.a12.dm.interpreter.model.ModelDef
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft.GroupBuilder
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.util.function.Consumer

/**
 * Differential — a computation that depends on an **ERRORED** computation is SKIPPED (IG13 residual). The kernel
 * computes `T := A` (errors when `A > 999`, its `maxValue`) and `U := T + 1`. When `T` is errored its value is
 * unusable, so the kernel does NOT compute `U` — it appears in NO bucket (not changed, not cleared, not errored;
 * probe-confirmed). The interpreter overlaid `T`'s errored value and computed `U = T + 1`, a divergence. With `A`
 * in range the cascade works normally (the control). `V := U + 1` proves the skip is transitive.
 */
class ComputeCascadeErroredDiffTest {

    private val json = DocumentModelDraft.named("calc").locale("en_US")
        .rootGroup("Calc", Consumer { g: GroupBuilder ->
            g.numberField("A", Consumer { it.min("0").max("100000") })
                .numberField("T", Consumer { it.min("0").max("999") })
                .numberField("U", Consumer { it.min("0").max("100000") })
                .numberField("V", Consumer { it.min("0").max("100000") })
        })
        .build().toJson()

    private val kModel = RuntimeLaws.withComputation(RuntimeLaws.withComputation(RuntimeLaws.withComputation(
        json, "/Calc", "TComp", "../T", "[A]"),
        "/Calc", "UComp", "../U", "[T] + 1"),
        "/Calc", "VComp", "../V", "[U] + 1")
    private val def = ModelDef(
        listOf(FieldDef(A, Kind.NUMBER, scale = 0), FieldDef(T, Kind.NUMBER, scale = 0, maxValue = "999"),
            FieldDef(U, Kind.NUMBER, scale = 0), FieldDef(V, Kind.NUMBER, scale = 0)),
        emptyList(),
        computations = listOf(
            ComputationDef(T, "/Calc", "[A]"), ComputationDef(U, "/Calc", "[T] + 1"), ComputationDef(V, "/Calc", "[U] + 1")),
    )

    /** Each engine's compute result as a `path|outcome|value` multiset (absent = not computed). */
    private fun kSig(a: String) = RuntimeLaws.compute(kModel, RuntimeLaws.doc().group("/Calc", 1).field(A, a, 1, 1))
        .map { "${it.path()}|${if (it.cleared()) "CLEARED" else if (it.errored()) "ERRORED" else "VALUE"}|${it.value().orElse("")}" }.sorted()
    private fun iSig(a: String) = DmInterpreter(def).compute(Document(def.kinds, def.declaredReps).group("/Calc", 1).field(A, a, 1, 1))
        .map { "${it.path}|${it.outcome}|${if (it.outcome == ComputeOutcome.CLEARED) "" else it.value}" }.sorted()

    /** Control: A in range → the whole cascade computes (T=500, U=501, V=502) on both engines. */
    @Test fun inRangeCascadesLikeKernel() {
        assertEquals(kSig("500"), iSig("500"), "in-range cascade — kernel=${kSig("500")} interp=${iSig("500")}")
    }

    /**
     * IG13/IF40, refined by IF85: A out of range → T errored; U's read of T POISONS U, transitively V. The
     * kernel reports the poisoned dependents only when their input cell was FILLED — here U/V are unseeded, so
     * the kernel reports ONLY the errored T. The interpreter reports EVERY instance (report-all): U and V come
     * back CLEARED, and the kernel-granularity projection drops an unseeded clear before comparison
     * (KERNEL-FINDINGS "compute reporting granularity"; the seeded-dependent case — kernel CLEARED too — is
     * `ComputePoisonReadDiffTest.seededDependentOfAnErroredDependencyIsCleared`).
     */
    @Test fun erroredDependencySkipsDependentsLikeKernel() {
        val k = kSig("5000")
        // The projection inline: no cell but A is seeded, so an interpreter CLEARED here is a kernel non-report.
        val i = iSig("5000").filterNot { it.endsWith("|CLEARED|") }
        assertEquals(1, k.size, "kernel computes ONLY T (errored); U and V are skipped: $k")
        assertEquals(k, i, "errored-cascade — kernel=$k interp(projected)=$i")
    }

    private companion object {
        const val A = "/Calc/A"; const val T = "/Calc/T"; const val U = "/Calc/U"; const val V = "/Calc/V"
    }
}
