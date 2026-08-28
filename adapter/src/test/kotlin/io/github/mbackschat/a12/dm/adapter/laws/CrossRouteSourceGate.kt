package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.interpreter.ComputationOutcome
import io.github.mbackschat.a12.dm.interpreter.PreparedModel
import org.junit.jupiter.api.Assertions.assertEquals

/**
 * The precondition every CROSS-ROUTE comparison needs, in one place.
 *
 * A law that compares a kernel applied document against the standalone interpreter's is feeding the two sides
 * through **different A12 ingress routes**: the kernel arms build from [RuntimeLaws.Doc], A12's V1 String
 * factory, while the interpreter reads canonical Document JSON, the typed route. `applyTo` applies only the
 * CHANGED actions, and the two routes classify a **source-identical** cell differently — on V1 a typed cell
 * holds unconverted text and never compares equal, so every non-STRING kind is changed there and none on the
 * typed route (KERNEL-FINDINGS §11, [SourceIdentityIngressRouteDiffTest]).
 *
 * Such a fixture therefore diverges for a reason that has nothing to do with what the law is about, and the
 * failure reads as a defect in the feature under test. [requireRouteAgnostic] converts that into a refusal that
 * names the cause and the repair. `CrossRouteGateCensusGuardTest` keeps every cross-route site wired to it, and
 * `SeparateDestinationRoutePreconditionTest` proves it fires.
 */
internal object CrossRouteSourceGate {

    fun requireRouteAgnostic(model: String, prepared: PreparedModel, source: RuntimeLaws.Doc) {
        // The VALUE arm and nothing else: the interpreter retains a CLEARED on cell-filledness and an ERRORED
        // unconditionally, neither of which depends on ingestion. Comparing whole retained sets measures two
        // different quantities — the kernel's `withChanges` is the changed SUCCESSFUL subset while `actions`
        // also carries clears and errors — and reports a mismatch on every healthy fixture.
        val v1Changed = RuntimeLaws.resultChannels(model, source).withChanges().size
        val typedChanged = prepared.createInterpreter()
            .compute(prepared.documents.readDocument(RuntimeLaws.canonicalDocumentJson(model, source)))
            .actions.count { it.outcome == ComputationOutcome.VALUE }
        assertEquals(
            v1Changed, typedChanged,
            "this fixture's source is not route-agnostic: A12's V1 String ingress retains $v1Changed changed "
                + "value(s) and its typed ingress $typedChanged, so the engines compared here are not "
                + "answering the same question and a cross-route comparison between them is invalid. The usual "
                + "cause is a computed cell seeded with its own computed value — give it a different value.",
        )
    }
}
