package io.github.mbackschat.a12.rulekit.lint;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import com.mgmtp.a12.model.notification.Severity;

import io.github.mbackschat.a12.rulekit.catalog.OperatorCatalog;
import io.github.mbackschat.a12.rulekit.dsl.BoundModel;
import io.github.mbackschat.a12.rulekit.dsl.Rule;
import io.github.mbackschat.a12.rulekit.validate.Diagnostic;
import io.github.mbackschat.a12.rulekit.validate.KernelVersion;
import io.github.mbackschat.a12.rulekit.validate.RuleValidator;
import io.github.mbackschat.a12.rulekit.validate.ValidationResult;

/**
 * The F4a unified diagnostic surface — one call that runs every origin on a built {@link Rule} and
 * returns a single {@link ValidationResult}. It composes the layers that already exist:
 *
 * <ul>
 *   <li><b>KERNEL</b> — {@link RuleValidator} ground truth, then {@link SpanResolver} pins each
 *       problem's operator/span against the rule's AST.</li>
 *   <li><b>LINT</b> — {@link Linter} (pure-AST + model-context WARNINGs).</li>
 *   <li><b>INFO</b> — {@link Advisor} gotchas for the operators the rule uses.</li>
 * </ul>
 *
 * <p>Hard PRECHECK failures (completeness, structural) surface as {@code PrecheckException} at
 * build time, before there is a rule to diagnose. The <i>deferred</i> kernel-semantic pre-checks
 * (F11, {@link Rule#deferredChecks()}) are included here as advisory PRECHECK WARNINGs — suppressed
 * when the live kernel already ruled on the same code, so the kernel stays the sole authority on
 * validity. The model must be a writable binding ({@link BoundModel#ofJson}) since the KERNEL step
 * needs its DM-JSON.
 */
public final class RuleDiagnostics {

    private final RuleValidator validator = new RuleValidator();
    private final SpanResolver spanResolver = new SpanResolver();
    private final Linter linter = new Linter();
    private final Advisor advisor = new Advisor();
    private final KernelVersion kernelVersion;
    private boolean environmentReported = false;

    public RuleDiagnostics() {
        this(KernelVersion.current(OperatorCatalog.load().verifiedAgainst()));
    }

    /** For tests: inject the {@link KernelVersion} so the F11 skew advisory can be exercised. */
    public RuleDiagnostics(KernelVersion kernelVersion) {
        this.kernelVersion = kernelVersion;
    }

    /** Every diagnostic for {@code rule} against {@code model}, in one result ({@code ok} iff no ERROR). */
    public ValidationResult diagnose(BoundModel model, Rule rule) {
        List<Diagnostic> out = new ArrayList<>();

        // F11 kernel-version skew: a once-per-instance ENVIRONMENT advisory (INFO, never gates `ok`).
        if (!environmentReported) {
            environmentReported = true;
            kernelVersion.advisory().ifPresent(out::add);
        }

        List<Diagnostic> kernel = validator.check(model.toJson(), rule.draft()).diagnostics();
        out.addAll(spanResolver.resolve(kernel, rule.condition(), rule.group().path()));
        out.addAll(linter.lint(model, rule));
        out.addAll(advisor.advise(rule.group().path() + "/" + rule.name(), rule.condition()));

        // F11: kernel-semantic pre-checks are deferred (advisory WARNINGs), not hard build() failures.
        // Suppress one when the live kernel already ruled on the same code — the kernel is the authority,
        // so on the same kernel its ERROR wins and there is no double-report; on a more lenient kernel
        // the advisory is the only signal and never flips `ok`.
        Set<String> kernelCodes = kernel.stream()
                .map(Diagnostic::code).flatMap(Optional::stream).collect(Collectors.toSet());
        for (Diagnostic d : rule.deferredChecks()) {
            if (d.code().map(kernelCodes::contains).orElse(false)) {
                continue;
            }
            out.add(d);
        }

        boolean ok = out.stream().noneMatch(d -> d.severity() == Severity.ERROR);
        return new ValidationResult(ok, out);
    }
}
