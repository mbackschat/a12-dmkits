package io.github.mbackschat.a12.dm.interpreter

import io.github.mbackschat.a12.dm.interpreter.eval.newDocument
import io.github.mbackschat.a12.dm.interpreter.model.ComputationDef
import io.github.mbackschat.a12.dm.interpreter.model.ComputeOutcome
import io.github.mbackschat.a12.dm.interpreter.model.FieldDef
import io.github.mbackschat.a12.dm.interpreter.model.Kind
import io.github.mbackschat.a12.dm.interpreter.model.ModelDef
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Multiplatform (JVM + JS) lock for IF89 — a computation iterates where it WRITES (the target's scope), not
 * where it is DECLARED. A constant computation declared in a separate container group (`/Rules`) targeting an
 * **all-non-repeatable** field in another subtree (`/Store/StockLevel`) computes its single instance regardless
 * of what is instantiated (non-repeatable groups are implicitly present in the kernel). Before IF89 the scope
 * fell back to the declaring group `/Rules`, whose uninstantiated `contextsFor` was empty → the field was never
 * computed. The kernel differential (both strategies) is `adapter.laws.CrossGroupConstantComputeScopeDiffTest`.
 */
class CrossGroupConstantComputeScopeTest {

    private val model = ModelDef(
        fields = listOf(FieldDef("/Store/StockLevel", Kind.NUMBER), FieldDef("/Rules/Marker", Kind.NUMBER)),
        rules = emptyList(),
        computations = listOf(ComputationDef("/Store/StockLevel", "/Rules", "-1")),
    )

    private fun outcome(instantiate: Boolean): Pair<ComputeOutcome?, String?> {
        val doc = model.newDocument()
        if (instantiate) doc.group("/Store", 1).field("/Store/StockLevel", "100", 1, 1)
        val c = DmInterpreter(model).compute(doc).firstOrNull { it.path == "/Store/StockLevel" }
        return c?.outcome to c?.value
    }

    @Test fun computesAtTheInstantiatedTarget() =
        assertEquals(ComputeOutcome.VALUE to "-1", outcome(instantiate = true), "target seeded → constant computed")

    @Test fun computesOnAnUninstantiatedTarget() =
        assertEquals(ComputeOutcome.VALUE to "-1", outcome(instantiate = false),
            "empty doc → still computes (non-repeatable groups are implicitly present)")
}
