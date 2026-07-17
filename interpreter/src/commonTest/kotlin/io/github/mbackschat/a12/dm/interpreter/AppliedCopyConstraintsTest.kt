package io.github.mbackschat.a12.dm.interpreter

import io.github.mbackschat.a12.dm.interpreter.eval.Document
import io.github.mbackschat.a12.dm.interpreter.eval.newDocument
import io.github.mbackschat.a12.dm.interpreter.model.ComputationAlternative
import io.github.mbackschat.a12.dm.interpreter.model.ComputationDef
import io.github.mbackschat.a12.dm.interpreter.model.FieldDef
import io.github.mbackschat.a12.dm.interpreter.model.Kind
import io.github.mbackschat.a12.dm.interpreter.model.ModelDef
import io.github.mbackschat.a12.dm.interpreter.model.RuleDef
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Multiplatform (JVM + JS) lock: the compute-applied copy carries the FULL model configuration — the
 * declared field constraints included. `Document.withComputed` rebuilds through `emptyCopy`, which once
 * dropped the `fieldConstraints` map (exactly the silently-dropped-trailing-map trap `newDocument`'s KDoc
 * warns about), so on the applied document a constraint-violating stored value resolved as VALID on the
 * resolution paths the constraints gate (aggregate cells, IF20/IF84) — `validate(applyComputations(doc))`
 * fired where the kernel suppresses. The kernel differential is
 * `adapter.laws.AppliedCellStateDiffTest.theAppliedCopyKeepsDeclaredConstraints`.
 */
class AppliedCopyConstraintsTest {

    private companion object {
        const val AMOUNT = "/Policy/Items/Amount"
        const val NOTE = "/Policy/Note"
        const val LABEL = "/Policy/Label"
    }

    // Amount carries minFractionalDigits=2 — a stored "5" parses but is constraint-INVALID
    // (zahlFalscheAnzahlNachkommastellen), so an aggregate over it must be non-evaluable (poisoned).
    private val model = ModelDef(
        fields = listOf(
            FieldDef(AMOUNT, Kind.NUMBER, minFractionalDigits = 2),
            FieldDef(NOTE, Kind.STRING),
            FieldDef(LABEL, Kind.STRING),
        ),
        rules = listOf(RuleDef("/Policy/SumR", "/Policy", "Sum(Items*/Amount) == 5", "SUM_R", "../Note")),
        computations = listOf(ComputationDef(LABEL, "/Policy", listOf(ComputationAlternative(null, "\"X\"")))),
        declaredReps = mapOf("/Policy/Items" to 5),
    )

    // The rule's outcome must be IDENTICAL on the original and the compute-applied document when the
    // computation does not touch the referenced cells — the applied copy is the same document plus the
    // computed value, never a differently-configured one.
    @Test fun validateAgreesOnOriginalAndAppliedDocument() {
        val doc = model.newDocument()
            .group("/Policy", 1).group("/Policy/Items", 1, 1)
            .field(AMOUNT, "5", 1, 1, 1).field(NOTE, "keep", 1, 1)
        val interp = DmInterpreter(model)
        fun signatures(d: Document) =
            interp.validate(d).messages.map { "${it.code}|${it.errorPath}|${it.type}" }.sorted()
        val original = signatures(doc)
        val applied = signatures(interp.applyComputations(doc))
        assertEquals(original, applied,
            "the applied copy must keep the declared constraints — Sum over a constraint-invalid cell stays suppressed")
    }
}
