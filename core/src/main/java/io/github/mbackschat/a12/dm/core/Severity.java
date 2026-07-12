package io.github.mbackschat.a12.dm.core;

/**
 * The severity of a diagnostic {@link Notification} — the dmkits' <b>own</b> vocabulary, so a diagnostic
 * never carries a {@code com.mgmtp.*} type into a kit's public surface (kernel-decoupling stage 1). Same
 * three levels as the kernel's {@code com.mgmtp.a12.model.notification.Severity}, mapped at the backend's
 * {@code ConsistencyOracle} boundary.
 *
 * <p>Deliberately distinct from {@link io.github.mbackschat.a12.dm.rulekit.dsl.Severity}, a rule's
 * <em>declared</em> severity attribute: same values, different concept — unifying them would thread a new
 * edge through the {@code rulekit} package cycle (KERNEL-DECOUPLING-PROPOSAL §5 ruling 4).
 */
public enum Severity {
    INFO,
    WARNING,
    ERROR
}
