package io.github.mbackschat.a12.dm.cli;

import java.util.Optional;

import io.github.mbackschat.a12.dm.consistency.CheckEditKt;
import io.github.mbackschat.a12.dm.consistency.CheckKt;
import io.github.mbackschat.a12.dm.consistency.ConsistencyReport;
import io.github.mbackschat.a12.dm.consistency.EditReport;
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
 * <p>So the only ways to obtain this class are {@link #of(String, String)} and
 * {@link #ofEdit(String, String, String)}, each of which performs the check it attests. There is no setter, no
 * constructor a consumer can reach, and no way to wrap a report that came from somewhere else.
 */
public final class PortableCheck {

    private final ConsistencyReport report;

    /** The edit attribution, null on the whole-model arm where no baseline was supplied. */
    private final EditReport edit;

    private PortableCheck(ConsistencyReport report, EditReport edit) {
        this.report = report;
        this.edit = edit;
    }

    /**
     * Run the portable checker over a model's JSON and keep the proof that it ran.
     *
     * <p>{@code diagnosticLabel} is echoed back on the checker's own diagnostics, so a report says which source
     * it is about. The checker never throws: an unreadable source comes back as a report whose diagnostics state
     * the failure at the source level, which is a gate that ran and is a witness like any other.
     */
    public static PortableCheck of(String modelJson, String diagnosticLabel) {
        return new PortableCheck(CheckKt.check(new ModelSource(modelJson, diagnosticLabel)), null);
    }

    /**
     * Run the portable checker over a CANDIDATE edit and keep both its attribution and the proof it ran.
     *
     * <p>The candidate verbs need the edit's own verdict, not the edited model's: this checker reports over a
     * whole model, so publishing the edited report as the candidate's answer would call a good candidate
     * invalid whenever the baseline carried an ERROR of its own. {@code checkEdit} is what separates the two,
     * and {@link #report()} still answers with the edited model's report so both arms of this witness mean the
     * same thing.
     */
    public static PortableCheck ofEdit(String baselineJson, String editedJson, String diagnosticLabel) {
        EditReport edit = CheckEditKt.checkEdit(
                new ModelSource(baselineJson, diagnosticLabel), new ModelSource(editedJson, diagnosticLabel));
        return new PortableCheck(edit.getEdited(), edit);
    }

    /** The checker's own report — the payload a verb publishes, in the shape the checker publishes it. */
    public ConsistencyReport report() {
        return report;
    }

    /**
     * The edit attribution, present only on the {@link #ofEdit} arm.
     *
     * <p>Empty for a whole-model check, where there is no baseline to attribute against — not an absent
     * value to be defaulted, but a different question having been asked.
     */
    public Optional<EditReport> edit() {
        return Optional.ofNullable(edit);
    }
}
