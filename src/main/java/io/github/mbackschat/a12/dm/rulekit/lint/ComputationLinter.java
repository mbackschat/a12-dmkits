package io.github.mbackschat.a12.dm.rulekit.lint;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

import io.github.mbackschat.a12.dm.core.Severity;

import io.github.mbackschat.a12.dm.rulekit.validate.Diagnostic;
import io.github.mbackschat.a12.dm.rulekit.validate.DiagnosticSource;
import io.github.mbackschat.a12.dm.rulekit.validate.DraftComputation;
import io.github.mbackschat.a12.dm.rulekit.validate.RkCode;

/**
 * The computation author-time lint layer (FEATURE-GAPS G5) — the computation peer of {@link Linter}. The
 * kernel BA doc mandates two computation laws an agent stumbles over, and both surface today only as a kernel
 * reject after the round-trip; this anticipates them with a corrective {@code RK_*} advisory:
 *
 * <ul>
 *   <li><b>forbidden constructs</b> — {@code CustomCondition} / {@code RepetitionNotUnique} are not allowed in
 *       a computation precondition (the kernel rejects with {@code MVK_ERROR_INVALID_LANGUAGE_FEATURE});</li>
 *   <li><b>non-exclusive preconditions</b> — alternatives are meant to be mutually exclusive; the kernel takes
 *       the first match in declaration order, so two identical preconditions mean the later one can never win.</li>
 * </ul>
 *
 * <p>It works on the {@link DraftComputation}'s precondition <em>texts</em> (no parse needed): the forbidden
 * constructs are matched as operator <em>calls</em> by their EN <em>and</em> DE keywords (a field reference can't
 * be {@code CustomCondition(}…), and overlap is exact-duplicate detection — precise and cheap. True logical
 * disjointness needs a solver and is deliberately out of scope (best-effort early warning, the duplicate case
 * being the one that always means a bug).
 */
public final class ComputationLinter {

    /** {@code CustomCondition(} in either language — matched as a call, not a substring of an identifier. */
    private static final Pattern CUSTOM_CONDITION =
            Pattern.compile("(?<![A-Za-z0-9_])(CustomCondition|ApplikationsBedingung)\\s*\\(");
    /** {@code RepetitionNotUnique(} in either language. */
    private static final Pattern REPETITION_NOT_UNIQUE =
            Pattern.compile("(?<![A-Za-z0-9_])(RepetitionNotUnique|WiederholungNichtEindeutig)\\s*\\(");

    public List<Diagnostic> lint(DraftComputation computation) {
        List<Diagnostic> out = new ArrayList<>();
        String path = computation.groupPath() + "/" + computation.name();

        forbidden(computation.commonPrecondition(), "the common precondition", path, out);
        List<DraftComputation.Alternative> alternatives = computation.alternatives();
        for (int i = 0; i < alternatives.size(); i++) {
            forbidden(alternatives.get(i).precondition(), "alternative #" + (i + 1) + "'s precondition", path, out);
        }
        overlap(alternatives, path, out);
        return List.copyOf(out);
    }

    /** Flag a forbidden construct ({@code CustomCondition}/{@code RepetitionNotUnique}) in a precondition. */
    private void forbidden(String precondition, String where, String path, List<Diagnostic> out) {
        if (precondition == null || precondition.isBlank()) {
            return;
        }
        String found = CUSTOM_CONDITION.matcher(precondition).find() ? "CustomCondition"
                : REPETITION_NOT_UNIQUE.matcher(precondition).find() ? "RepetitionNotUnique"
                : null;
        if (found != null) {
            out.add(new Diagnostic(Severity.WARNING, DiagnosticSource.LINT,
                    Optional.of(RkCode.COMPUTATION_FORBIDDEN_CONSTRUCT.code()),
                    where + " uses " + found + ", which the kernel forbids in a computation — it will be rejected"
                            + " (MVK_ERROR_INVALID_LANGUAGE_FEATURE)",
                    new Diagnostic.Where(path, Optional.empty()),
                    Optional.of("remove " + found + " from the precondition (it is not allowed in a computation)"),
                    Optional.empty()));
        }
    }

    /** Flag two alternatives whose (non-empty) preconditions are identical — they overlap by definition. */
    private void overlap(List<DraftComputation.Alternative> alternatives, String path, List<Diagnostic> out) {
        Map<String, Integer> firstSeen = new LinkedHashMap<>();
        for (int i = 0; i < alternatives.size(); i++) {
            String precondition = alternatives.get(i).precondition();
            if (precondition == null || precondition.isBlank()) {
                continue;
            }
            String normalized = precondition.strip().replaceAll("\\s+", " ");
            Integer earlier = firstSeen.putIfAbsent(normalized, i + 1);
            if (earlier != null) {
                out.add(new Diagnostic(Severity.WARNING, DiagnosticSource.LINT,
                        Optional.of(RkCode.COMPUTATION_OVERLAPPING_PRECONDITIONS.code()),
                        "alternatives #" + earlier + " and #" + (i + 1) + " share an identical precondition, so they"
                                + " overlap — the kernel takes the first match, so #" + (i + 1) + " can never win",
                        new Diagnostic.Where(path, Optional.empty()),
                        Optional.of("make the alternatives' preconditions mutually exclusive"),
                        Optional.empty()));
            }
        }
    }
}
