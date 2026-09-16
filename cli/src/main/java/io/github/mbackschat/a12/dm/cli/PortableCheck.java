package io.github.mbackschat.a12.dm.cli;

import io.github.mbackschat.a12.dm.consistency.CheckKt;
import io.github.mbackschat.a12.dm.consistency.ConsistencyReport;
import io.github.mbackschat.a12.dm.consistency.ModelSource;

/**
 * A completed run of the kernel-free portable checker — the witness that the portable gate actually ran.
 *
 * <p><b>Why this exists rather than using the report directly.</b> {@link ConsistencyEvidence} trusts a witness
 * because only a gate can produce its TYPE, so a publicly constructible witness inverts the whole mechanism. A
 * {@code ConsistencyReport} is a public Kotlin data class by design — the checker's consumers read and rebuild
 * reports, and rightly so — which means holding one proves nothing about who produced it. Exactly that hole was
 * shipped three times in one session on types nobody thought of as receipts, and
 * {@code WitnessTypesAreUnforgeableTest} names the remedy it caught this one with: a private constructor plus a
 * factory that runs the gate.
 *
 * <p>So the only way to obtain this class is {@link #of(String, String)}, which performs the check. There is no
 * setter, no constructor a consumer can reach, and no way to wrap a report that came from somewhere else.
 */
public final class PortableCheck {

    private final ConsistencyReport report;

    private PortableCheck(ConsistencyReport report) {
        this.report = report;
    }

    /**
     * Run the portable checker over a model's JSON and keep the proof that it ran.
     *
     * <p>{@code diagnosticLabel} is echoed back on the checker's own diagnostics, so a report says which source
     * it is about. The checker never throws: an unreadable source comes back as a report whose diagnostics state
     * the failure at the source level, which is a gate that ran and is a witness like any other.
     */
    public static PortableCheck of(String modelJson, String diagnosticLabel) {
        return new PortableCheck(CheckKt.check(new ModelSource(modelJson, diagnosticLabel)));
    }

    /** The checker's own report — the payload a verb publishes, in the shape the checker publishes it. */
    public ConsistencyReport report() {
        return report;
    }
}
