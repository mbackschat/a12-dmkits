package io.github.mbackschat.a12.dm.rulekit.lint;

import java.util.ArrayList;
import java.util.List;

import io.github.mbackschat.a12.dm.core.Severity;

import io.github.mbackschat.a12.dm.rulekit.catalog.OperatorCatalog;
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel;
import io.github.mbackschat.a12.dm.rulekit.dsl.Computation;
import io.github.mbackschat.a12.dm.rulekit.validate.Diagnostic;
import io.github.mbackschat.a12.dm.rulekit.validate.KernelVersion;
import io.github.mbackschat.a12.dm.rulekit.validate.RuleValidator;
import io.github.mbackschat.a12.dm.rulekit.validate.ValidationResult;

/**
 * The computation peer of {@link RuleDiagnostics}: one call that runs every origin on a built
 * {@link Computation} and returns a single {@link ValidationResult}. It composes the layers that
 * already exist:
 *
 * <ul>
 *   <li><b>KERNEL</b> — {@link RuleValidator#check(String, io.github.mbackschat.a12.dm.rulekit.validate.DraftComputation)}
 *       ground truth (the kernel's computation-table rules: reference-to-calculated-field, empty
 *       precondition, message-missing, …).</li>
 *   <li><b>LINT</b> — {@link ComputationLinter}: forbidden constructs ({@code CustomCondition}/
 *       {@code RepetitionNotUnique}) and overlapping preconditions, caught before the kernel round-trip.</li>
 *   <li><b>ENVIRONMENT</b> — the F11 kernel-version skew advisory (INFO, never gates {@code ok}).</li>
 * </ul>
 *
 * <p>Mirrors {@code RuleDiagnostics}; the span-resolution and gotcha-advisory steps are rule-shaped
 * (a single condition) and have no computation analogue, so they are omitted here. The model must be a
 * writable binding ({@link BoundModel#ofJson}) since the KERNEL step needs its DM-JSON.
 */
public final class ComputationDiagnostics {

    private final RuleValidator validator = new RuleValidator();
    private final ComputationLinter linter = new ComputationLinter();
    private final KernelVersion kernelVersion;
    private boolean environmentReported = false;

    public ComputationDiagnostics() {
        this(KernelVersion.current(OperatorCatalog.load().verifiedAgainst()));
    }

    /** For tests: inject the {@link KernelVersion} so the F11 skew advisory can be exercised. */
    public ComputationDiagnostics(KernelVersion kernelVersion) {
        this.kernelVersion = kernelVersion;
    }

    /** Every diagnostic for {@code computation} against {@code model}, in one result ({@code ok} iff no ERROR). */
    public ValidationResult diagnose(BoundModel model, Computation computation) {
        List<Diagnostic> out = new ArrayList<>();

        // F11 kernel-version skew: a once-per-instance ENVIRONMENT advisory (INFO, never gates `ok`).
        if (!environmentReported) {
            environmentReported = true;
            kernelVersion.advisory().ifPresent(out::add);
        }

        out.addAll(validator.check(model.toJson(), computation.draft()).diagnostics());
        out.addAll(linter.lint(computation.draft()));

        return ValidationResult.of(out);
    }
}
