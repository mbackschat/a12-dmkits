package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.interpreter.DmInterpreter
import io.github.mbackschat.a12.dm.interpreter.eval.Document
import io.github.mbackschat.a12.dm.interpreter.model.ComputationDef
import io.github.mbackschat.a12.dm.interpreter.model.FieldDef
import io.github.mbackschat.a12.dm.interpreter.model.Kind
import io.github.mbackschat.a12.dm.interpreter.model.ModelDef
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft.GroupBuilder
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.util.function.Consumer

/**
 * Differential + tri-check (groovy-dynamic == java-static == interpreter) for the IG47 **iteration-scope**
 * atom (`kernel=VALUE,interp=absent` — the kernel computes an instance the interpreter does not), root-caused
 * by the raw-outcome isolator ([RawAtomShrinkTest]): a computation DECLARED in a separate, uninstantiated
 * container group (a `*_Rules` group) TARGETING a field in another subtree with an **all-non-repeatable** scope
 * and a **constant** operation (no operand refs to derive a scope from).
 *
 * A computation iterates where it **writes** (the target's scope), not where it is **declared**. The
 * interpreter's `scopeFromRefs` filters to REPEATABLE ancestors and, finding none for an all-non-repeatable
 * target, fell back to `comp.group` — here the empty `/Rules` container, which has no instantiated row, so
 * `contextsFor` yielded nothing and the field was never computed. The kernel computes the single instance at
 * the target (non-repeatable groups are implicitly present). IF89 changes the fallback to the TARGET's parent
 * group, so the interpreter computes it at the target's (non-repeatable) context — matching both strategies.
 *
 * Own-domain re-expression (a stock ledger with a rules container); nothing from the corpus is reproduced.
 */
class CrossGroupConstantComputeScopeDiffTest {

    private companion object {
        const val STOCK = "/Store/StockLevel"
    }

    private val json = DocumentModelDraft.named("inv").locale("en_US")
        .rootGroup("Store", Consumer { g: GroupBuilder -> g.numberField("StockLevel", Consumer { }) })
        .rootGroup("Rules", Consumer { g: GroupBuilder -> g.numberField("Marker", Consumer { }) })
        .build().toJson()

    // A constant (-1) computation declared in the uninstantiated /Rules container, targeting /Store/StockLevel
    // (all-non-repeatable). `../../Store/StockLevel` climbs from the computation's slot in /Rules up to the root
    // level, then descends into the /Store subtree.
    private val kModel = RuntimeLaws.withComputation(json, "/Rules", "StockComp", "../../Store/StockLevel", "-1")
    private val def = ModelDef(
        listOf(FieldDef(STOCK, Kind.NUMBER), FieldDef("/Rules/Marker", Kind.NUMBER)),
        emptyList(),
        computations = listOf(ComputationDef(STOCK, "/Rules", "-1")),
    )

    private fun jValue(): String? {
        val engine = RuntimeLaws.kernelJavaEngine(kModel)
        val doc = RuntimeLaws.doc().group("/Store", 1).field(STOCK, "100", 1, 1)
        return engine.computeOutcomesSorted(engine.instance(doc)).firstOrNull { it.startsWith("$STOCK|") }
            ?.substringAfter("$STOCK|")
    }

    @Test fun crossGroupConstantComputesLikeKernel() {
        val kdoc = RuntimeLaws.doc().group("/Store", 1).field(STOCK, "100", 1, 1)
        val kValue = RuntimeLaws.computedValue(RuntimeLaws.compute(kModel, kdoc), STOCK).orElse(null)
        val idoc = Document(def.kinds, def.declaredReps, def.unsignedFields, def.rangeConfig, def.dateFormats, def.scales)
            .group("/Store", 1).field(STOCK, "100", 1, 1)
        val iValue = DmInterpreter(def).compute(idoc).firstOrNull { it.path == STOCK }?.value

        assertEquals("-1", kValue, "the kernel computes the constant into the cross-group, all-non-repeatable target")
        assertEquals("-1", jValue(), "java-static codegen agrees (tri-check)")
        assertEquals(kValue, iValue, "interpreter matches the kernel — was absent before IF89 (scope fell back to /Rules)")
    }

    /** The stress case for the fallback: NOTHING placed (an empty document). The kernel still computes the
     *  constant (non-repeatable groups are implicitly present); the interpreter must agree — whatever the kernel
     *  does here, the tri-check pins it (this is what tells us whether the parentGroup fallback is sufficient or
     *  an implicit-context fix is needed). */
    @Test fun crossGroupConstantOnEmptyDocMatchesKernel() {
        val kValue = RuntimeLaws.computedValue(RuntimeLaws.compute(kModel, RuntimeLaws.doc()), STOCK).orElse(null)
        val idoc = Document(def.kinds, def.declaredReps, def.unsignedFields, def.rangeConfig, def.dateFormats, def.scales)
        val iValue = DmInterpreter(def).compute(idoc).firstOrNull { it.path == STOCK }?.value
        assertEquals(kValue, iValue, "empty doc — kernel=$kValue interp=$iValue")
    }
}
