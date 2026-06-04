package io.github.mbackschat.a12.rulekit.lint;

import java.util.ArrayList;
import java.util.List;

import com.mgmtp.a12.model.notification.Severity;

import io.github.mbackschat.a12.rulekit.dsl.BoundModel;
import io.github.mbackschat.a12.rulekit.dsl.Rule;
import io.github.mbackschat.a12.rulekit.validate.Diagnostic;
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
 * <p>PRECHECK diagnostics aren't here by construction: a {@link Rule} only exists once
 * {@code build()} passed, so pre-check failures surface (as {@code PrecheckException}) at build
 * time, before there is a rule to diagnose. The model must be a writable binding
 * ({@link BoundModel#ofJson}) since the KERNEL step needs its DM-JSON.
 */
public final class RuleDiagnostics {

    private final RuleValidator validator = new RuleValidator();
    private final SpanResolver spanResolver = new SpanResolver();
    private final Linter linter = new Linter();
    private final Advisor advisor = new Advisor();

    /** Every diagnostic for {@code rule} against {@code model}, in one result ({@code ok} iff no ERROR). */
    public ValidationResult diagnose(BoundModel model, Rule rule) {
        List<Diagnostic> out = new ArrayList<>();

        List<Diagnostic> kernel = validator.check(model.toJson(), rule.draft()).diagnostics();
        out.addAll(spanResolver.resolve(kernel, rule.condition()));
        out.addAll(linter.lint(model, rule));
        out.addAll(advisor.advise(rule.group().path() + "/" + rule.name(), rule.condition()));

        boolean ok = out.stream().noneMatch(d -> d.severity() == Severity.ERROR);
        return new ValidationResult(ok, out);
    }
}
