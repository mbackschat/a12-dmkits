package io.github.mbackschat.a12.dm.cli;

import java.util.Locale;

/**
 * Which implementation a consistency verdict was asked of, and which one produced it (CLI-SPEC §3b).
 *
 * <p><b>A closed set of two, and deliberately NOT {@code RuntimeEvidence.Engine}.</b> That enum names the
 * engines that can produce a semantic <em>observation</em> by evaluating a document; a checker evaluates
 * nothing and answers a question about the model itself. The two axes are kept apart for the same reason
 * {@code RuntimeEvidence} refuses to merge with {@code verification}: a verdict and an observation are
 * different claims, and an enum shared between them would let one be published where the other belongs.
 * {@code A12_KERNEL} appearing in both is two facts about the same product, not one fact stored twice.
 *
 * <p><b>Nothing but the caller ever selects.</b> There is no {@code AUTO}, and none may be added: F1's
 * contract is that availability, discovery order, coverage, completeness and exceptions must never choose an
 * engine, because a fallback turns a portable INCONCLUSIVE into a kernel answer the caller did not ask for.
 */
public enum CheckEngine {

    /** The real A12 kernel — the consistency authority, and the default on every verb that can select. */
    A12_KERNEL,

    /** The kernel-free portable checker ({@code :consistency}), only ever when explicitly requested. */
    PORTABLE_CHECKER;

    /** The spelling on the wire and on the command line — {@code kernel} / {@code portable}. */
    public String wireName() {
        return this == A12_KERNEL ? "kernel" : "portable";
    }

    /**
     * Parse a {@code --engine} value, or fail with the admitted set named.
     *
     * <p>The message lists both spellings rather than saying "invalid value": an agent that guessed
     * {@code --engine=a12} has to learn the vocabulary from the refusal, which is the self-explaining-surface
     * requirement applied to an option's domain.
     */
    public static CheckEngine ofWireName(String value) {
        String normalized = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
        for (CheckEngine engine : values()) {
            if (engine.wireName().equals(normalized)) {
                return engine;
            }
        }
        throw new IllegalArgumentException("unknown checking engine '" + value + "' — it is `kernel` (the real "
                + "A12 kernel, the default) or `portable` (the kernel-free checker)");
    }
}
