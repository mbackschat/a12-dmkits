package io.github.mbackschat.a12.rulekit.validate;

import java.util.List;

import com.mgmtp.a12.model.notification.Severity;

/**
 * The outcome of a ground-truth check: {@code ok} iff the kernel reported no {@code ERROR}-severity
 * problems. Lower-severity notifications (warnings/info) are retained in {@link #problems()}.
 */
public record ValidationResult(boolean ok, List<Problem> problems) {

    public ValidationResult {
        problems = List.copyOf(problems);
    }

    static ValidationResult of(List<Problem> problems) {
        boolean ok = problems.stream().noneMatch(p -> p.severity() == Severity.ERROR);
        return new ValidationResult(ok, problems);
    }
}
