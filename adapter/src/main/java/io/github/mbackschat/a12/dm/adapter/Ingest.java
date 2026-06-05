package io.github.mbackschat.a12.dm.adapter;

import io.github.mbackschat.a12.dm.rulekit.dsl.Condition;

/**
 * The outcome of ingesting one rule's condition (F10) — converter gaps become <em>data</em>, not
 * exceptions (proposal finding 5; F11 "ingest preserves the unknown"). Pattern-match it:
 *
 * <ul>
 *   <li>{@link Converted} — the kernel condition mapped cleanly to a typed rulekit {@link Condition}
 *       (re-renderable, transformable — the full payoff of the read direction).</li>
 *   <li>{@link Opaque} — a construct the converter doesn't model yet. The rule is <b>not lost and not
 *       reported as corrupt</b>: its kernel-canonical text is preserved verbatim (re-emit it as-is on
 *       write-back) and {@code reason} names the unconverted construct. So a user's whole model
 *       ingests safely — supported rules become typed ASTs, the rest survive opaque with a warning.</li>
 * </ul>
 *
 * <p>This is the tolerant, publishing-safe shape: ingesting a valid rule the kernel accepts never
 * throws. (The strict, fail-fast {@code UnsupportedConstructException} stays the converter's internal
 * unwind signal, surfaced here as {@link Opaque#reason()}.)
 */
public sealed interface Ingest permits Ingest.Converted, Ingest.Opaque {

    /** A rule whose condition converted to a typed rulekit AST. */
    record Converted(Condition condition) implements Ingest {}

    /** A rule preserved verbatim because its condition uses a not-yet-modeled construct. */
    record Opaque(String canonicalText, String reason) implements Ingest {}

    /** True iff this is a {@link Converted} result. */
    default boolean isConverted() {
        return this instanceof Converted;
    }
}
