package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.interpreter.DmInterpreter
import io.github.mbackschat.a12.dm.interpreter.eval.newDocument
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
 * Differential — an out-of-range COMPUTED value is bucketed as ERRORED (IG13). The kernel's `compute()` returns
 * the value but flags the instance in `getComputedFieldInstancesWithErrors()` when the computed value is not
 * valid for the target field (e.g. a number above its `maxValue`). The interpreter computed only VALUE/CLEARED
 * (`ComputeOutcome.ERRORED` had zero producers). `T` (maxValue 999) ← `[A]*[B]`: in-range stays VALUE, the
 * 10000 overflow is ERRORED on both. The value is returned in both buckets.
 */
class ComputeErrorBucketDiffTest {

    private val json = DocumentModelDraft.named("calc").locale("en_US")
        .rootGroup("Calc", Consumer { g: GroupBuilder ->
            g.numberField("A", Consumer { it.min("0").max("100000") })
                .numberField("B", Consumer { it.min("0").max("100000") })
                .numberField("T", Consumer { it.min("0").max("999") })
        })
        .build().toJson()

    // The term is a trivial arithmetic product, fed verbatim to both engines (the test exercises the ERRORED
    // bucketing of the out-of-range RESULT, not operator authoring).
    private val op = "[A] * [B]"
    private val def = ModelDef(
        listOf(FieldDef(A, Kind.NUMBER, scale = 0), FieldDef(B, Kind.NUMBER, scale = 0),
            FieldDef(T, Kind.NUMBER, scale = 0, maxValue = "999")),
        emptyList(),
        computations = listOf(ComputationDef(T, "/Calc", op)),
    )
    private val kModel = RuntimeLaws.withComputation(json, "/Calc", "TComp", "../T", op)

    private fun agree(a: String, b: String, msg: String) {
        val k = RuntimeLaws.compute(kModel, RuntimeLaws.doc().group("/Calc", 1).field(A, a, 1, 1).field(B, b, 1, 1))
            .first { it.path == T }
        val i = DmInterpreter(def).compute(def.newDocument().group("/Calc", 1)
            .field(A, a, 1, 1).field(B, b, 1, 1)).first { it.path == T }
        assertEquals(k.errored(), i.outcome == ComputeOutcome.ERRORED, "$msg — errored kernel=${k.errored()} interp=${i.outcome}")
        assertEquals(k.value().orElse(null), i.value, "$msg — value")
    }

    @Test fun inRangeComputesValue() = agree("10", "10", "100 ≤ 999 → VALUE")
    @Test fun outOfRangeComputesErrored() = agree("100", "100", "10000 > 999 → ERRORED (value still returned)")

    private companion object {
        const val A = "/Calc/A"; const val B = "/Calc/B"; const val T = "/Calc/T"
    }
}
