package io.github.mbackschat.a12.dm.rulekit.read;

import io.github.mbackschat.a12.dm.core.KernelCode;
import io.github.mbackschat.a12.dm.rulekit.dsl.Condition;
import java.util.Optional;

/**
 * The outcome of reading one rule's condition (F10) — converter gaps become <em>data</em>, not
 * exceptions (proposal finding 5; F11 "read preserves the unknown"). Pattern-match it:
 *
 * <ul>
 *   <li>{@link Converted} — the kernel condition mapped cleanly to a typed rulekit {@link Condition}
 *       (re-renderable, transformable — the full payoff of the read direction).</li>
 *   <li>{@link Opaque} — a construct the converter doesn't model yet. The rule is <b>not lost and not
 *       reported as corrupt</b>: its kernel-canonical text is preserved verbatim (re-emit it as-is on
 *       write-back) and {@code reason} names the unconverted construct. So a user's whole model
 *       reads safely — supported rules become typed ASTs, the rest survive opaque with a warning.</li>
 *   <li>{@link Refused} — it mapped, and a rulekit gate with a measured kernel basis says the kernel would
 *       reject it. The text is preserved exactly as for {@link Opaque}, and the difference is what the
 *       consumer should DO: {@code Opaque} says <em>come back when the converter has learned this</em>,
 *       {@code Refused} says <em>this rule is invalid</em>, and it names the code.</li>
 * </ul>
 *
 * <p><b>Why the last two are separate (DEV-API-GAPS DG1001).</b> They were one state, because a provider
 * catching a {@code RuntimeException} per rule could not tell a converter gap from a rulekit verdict.
 * Distinguishing them meant reading the {@code reason} prose. Each gate promoted into the typed surface
 * widened the conflation, so the split lands with the carrier that makes it decidable — {@code
 * KernelCodeRefusal}, which is the exception type every {@code Refusals.of} refusal now throws.
 *
 * <p><b>Only a KERNEL-FREE provider produces {@link Refused}, and that is measured rather than intended
 * (2026-09-20).</b> Where there is no kernel to consult, a rulekit gate's verdict is the only one available,
 * so the interpreter bridge reports it as itself. The kernel-backed provider's own layer answers first — an
 * unparseable stored condition and an unextractable computation both come back before any gate runs — so it
 * never reaches a refusal to report. A consumer must still handle all three: it does not know which provider
 * served it, which is the point of a port.
 *
 * <p>This is the tolerant, publishing-safe shape: reading a valid rule the kernel accepts never
 * throws. (The strict, fail-fast {@code UnsupportedConstructException} stays the converter's internal
 * unwind signal, surfaced here as {@link Opaque#reason()}.)
 *
 * <p>Kernel-decoupling stage 3: this lives in {@code rulekit} (it speaks rulekit's AST) as the return
 * vocabulary of the {@link io.github.mbackschat.a12.dm.rulekit.spi.ConditionReadBack} port, which both
 * backends produce, so a published consumer reads back through the port without a kernel type.
 */
public sealed interface ReadResult permits ReadResult.Converted, ReadResult.Opaque, ReadResult.Refused {

    /**
     * A rule whose condition converted to a typed rulekit AST, and whether it opens with A12's scale-gate waiver.
     * The waiver is the rule's rather than the AST's because A12 keeps it on the root of the parsed condition, not
     * on a comparison (KERNEL-FINDINGS KF75); dropping it here would let a consumer rebuild a rule A12 refuses.
     */
    record Converted(Condition condition, boolean allowsDifferingDecimals) implements ReadResult {

        /** A converted condition without the waiver. */
        public Converted(Condition condition) {
            this(condition, false);
        }
    }

    /** A rule preserved verbatim because its condition uses a not-yet-modeled construct. */
    record Opaque(String canonicalText, String reason) implements ReadResult {}

    /**
     * A rule preserved verbatim because a rulekit gate refuses its condition on a measured kernel basis.
     *
     * @param canonicalText the stored condition, verbatim — a refused rule is never LOST, exactly as an
     *     opaque one is not; what differs is the advice, not the preservation
     * @param reason the refusal's rendered message, code suffix included
     * @param code the kernel code the refusal claims, so a consumer can branch on it without reading prose
     */
    record Refused(String canonicalText, String reason, KernelCode code) implements ReadResult {}

    /** True iff this is a {@link Converted} result. */
    default boolean isConverted() {
        return this instanceof Converted;
    }

    /**
     * The verbatim condition text of a result that did not convert, or empty for a {@link Converted} one.
     *
     * <p>Both non-converted states preserve the stored text, and most consumers want it without caring
     * which one they have — a projection that exists so adding {@link Refused} does not make every such
     * caller grow a second branch it has no opinion about.
     */
    default Optional<String> preservedText() {
        return switch (this) {
            case Converted ignored -> Optional.empty();
            case Opaque opaque -> Optional.of(opaque.canonicalText());
            case Refused refused -> Optional.of(refused.canonicalText());
        };
    }
}
