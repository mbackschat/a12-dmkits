package io.github.mbackschat.a12.dm.rulekit.validate;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.mgmtp.a12.model.notification.RankedNotification;
import com.mgmtp.a12.model.notification.Severity;

/**
 * One problem reported by the kernel while checking a rule, with the {@code MVK_*} code extracted
 * (when present) so callers can map it to the actionable fix from {@code KERNEL-GRAMMAR.md}.
 */
public record Problem(Severity severity, String message, Optional<String> mvkCode) {

    private static final Pattern MVK = Pattern.compile("MVK_[A-Z0-9_]+");

    static Problem from(RankedNotification notification) {
        String message = notification.getMessage();
        Matcher matcher = MVK.matcher(message == null ? "" : message);
        Optional<String> code = matcher.find() ? Optional.of(matcher.group()) : Optional.empty();
        return new Problem(notification.getSeverity(), message, code);
    }
}
