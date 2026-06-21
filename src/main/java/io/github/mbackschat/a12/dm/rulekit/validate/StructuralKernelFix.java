package io.github.mbackschat.a12.dm.rulekit.validate;

import java.util.Map;
import java.util.Optional;

/**
 * Curated {@code fix}/{@code explain} for <b>structural</b> kernel codes — the placement /
 * iteration-scope family that no operator's {@code invalidExample} documents, so
 * {@link DiagnosticEnricher}'s operator-keyed catalog-join cannot reach them. The enricher consults
 * this as a <em>fallback</em> (after the catalog miss), so operator-level codes keep their
 * N7-locked catalog fix and structural codes still get a corrective hint instead of a bare kernel
 * message.
 *
 * <p>Hand-authored guidance, like the {@code RK_*} meanings in {@link RkCode} — but locked the same
 * way the catalog fixes are: a test triggers the real kernel code and asserts the fix attaches
 * (e.g. {@code RuleValidatorTest.attachesAPlacementFixToTheNotInRuleGroupCode}), so the text can't
 * drift away from the behavior it explains. The placement law itself is locked in
 * {@code validate.laws.IterationLawsTest} (KERNEL-FINDINGS §9).
 */
final class StructuralKernelFix {

    /** A corrective {@code (fix, explain)} pair for one structural kernel code. */
    record Hint(String fix, String explain) {
    }

    private static final Map<String, Hint> HINTS = Map.of(
            "MVK_ERROR_FIELD_NOT_IN_RULEGROUP", new Hint(
                    "Place the error field inside every repeatable group the rule iterates over. The condition "
                            + "references a per-row field, so the rule iterates and its error field must live in that "
                            + "repeatable group — either pick an error field inside it, or make the rule whole-document "
                            + "by aggregating the per-row operand (e.g. Sum(Group*/Field)) so it no longer iterates.",
                    "A rule's iteration scope is the set of repeatable groups among the fields it references "
                            + "(including the error field); the error field must lie in every repeatably-referenced group, "
                            + "otherwise the per-row error has nowhere to attach."));

    private StructuralKernelFix() {
    }

    /** The curated hint for {@code code}, when it is a known structural code. */
    static Optional<Hint> forCode(String code) {
        return Optional.ofNullable(HINTS.get(code));
    }
}
