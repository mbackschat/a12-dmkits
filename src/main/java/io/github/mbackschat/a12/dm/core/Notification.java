package io.github.mbackschat.a12.dm.core;

/**
 * One notification the kernel reported while checking a model's consistency — the dmkits' <b>own</b>
 * value object, so this vocabulary never leaks a {@code com.mgmtp.*} type into a kit's public surface
 * (kernel-decoupling stage 1). It carries exactly what every consumer reads off a kernel
 * {@code RankedNotification}: its {@link #severity()} and {@link #message()} (the audit proved those two
 * are the entire consumed surface). The backend maps kernel → ours at the {@code ConsistencyOracle} seam.
 */
public record Notification(Severity severity, String message) {
}
