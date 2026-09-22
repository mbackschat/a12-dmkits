package io.github.mbackschat.a12.dm.rulekit.validate.laws;

/**
 * A law's condition handed to the kernel as TEXT, together with the reason the typed surface cannot
 * author it. Only two reasons are legitimate (TESTING-SPEC, "A String condition stays correct for exactly
 * two reasons"), and they are the two factories below; there is no third and no unexplained arm.
 *
 * <p>The type exists because those two reasons were advertised in three places and enforced nowhere.
 * Passing a bare {@code String} states nothing, so nothing can tell a row that is raw by necessity from
 * one that is raw by habit — and a source-text classifier cannot tell them apart either, having no
 * access to why the author chose the spelling. Making the reason a constructor argument moves the
 * question to the call site, which is the only place that knows the answer.
 *
 * <p><strong>This is for the ODD ROW OUT.</strong> The reason a law is raw is nearly always a property of
 * the whole family, so a class states it once in its javadoc and needs none of this ceremony; reach for
 * {@code RawCondition} when a single row inside an otherwise-typed family needs its own. Retiring the
 * bare-{@code String} overloads to force every site through here was considered and rejected: three
 * families were sampled and raw was legitimate in most of their rows, so wrapping all 675 call sites on
 * this class's String-condition entry points (453 on the nine names with no typed twin, 222 on the three
 * that overload-resolve against one; measured by signature 2026-09-22) would buy per-row ritual where
 * per-class prose already works (TESTING-SPEC). Three of the 288 law classes (2026-09-22) is a sample, not
 * a census — the decision rests on the cost being certain and the benefit speculative, not on a measured
 * estate. Nothing here makes the estate clean; it makes one row honest.
 */
record RawCondition(String text, Reason reason, String why) {

    enum Reason {
        /** The typed builder cannot construct the shape at all — so the text is the only way to reach it. */
        SHAPE_ERASED,
        /** The builder constructs it but renders a different, correct spelling — and the spelling IS the subject. */
        CONCRETE_SYNTAX,
    }

    RawCondition {
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("a raw condition needs its text");
        }
        if (why == null || why.isBlank()) {
            throw new IllegalArgumentException(
                    "a raw condition needs the reason it is raw — that is the whole point of this type");
        }
    }

    /**
     * The typed surface cannot build this shape. Verify it before claiming it: attempt the typed form and
     * see the builder refuse. {@code why} names what it refuses and, where there is one, the corrective it
     * offers — a refusal that later becomes constructible turns this row back into a migration candidate.
     */
    static RawCondition shapeErased(String text, String why) {
        return new RawCondition(text, Reason.SHAPE_ERASED, why);
    }

    /**
     * The builder can express the semantics but renders a different spelling, and this law's subject is the
     * spelling itself — so authoring it typed would silently test a different string. {@code why} names the
     * spelling the renderer produces instead, which is what makes the claim checkable.
     */
    static RawCondition concreteSyntax(String text, String why) {
        return new RawCondition(text, Reason.CONCRETE_SYNTAX, why);
    }
}
