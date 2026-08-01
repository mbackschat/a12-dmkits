package io.github.mbackschat.a12.dm.cli.interp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import java.util.List;
import java.util.Optional;

import io.github.mbackschat.a12.dm.cli.CliTestSupport;
import io.github.mbackschat.a12.dm.core.ModelIndex;
import io.github.mbackschat.a12.dm.core.ReferenceResolvers;
import io.github.mbackschat.a12.dm.core.Severity;
import io.github.mbackschat.a12.dm.rulekit.validate.Diagnostic;
import io.github.mbackschat.a12.dm.rulekit.validate.DiagnosticSource;

import org.junit.jupiter.api.Test;

/**
 * The corrective says <b>why</b> it did not narrow a union fix, so a parser gap is not indistinguishable from
 * "no corrective applies".
 *
 * <p><b>The defect this closes.</b> {@code comparisonsByOperator} wrapped the interpreter parser in
 * {@code catch (RuntimeException) -> Map.of()}, and the caller treated an empty map exactly like "no unique
 * candidate operator". Four different causes — the text would not parse, the model index would not build, the
 * text carried no comparison at all, or there genuinely was no unique candidate — all produced the same output:
 * the union fix, with nothing to distinguish them. The tolerant catch was justified as "never worse than today",
 * which is true of the <em>corrective</em> and false of the <em>visibility</em>: it converts any future parser
 * gap into silence. It already cost a debugging session on 2026-07-31, when a green test was provisionally
 * blamed on a parse failure that {@code FilterConditionParseTest} then disproved.
 *
 * <p><b>Why the outcome is observable but not published.</b> The published envelope is deliberately unchanged:
 * the harm on record was a <em>developer</em> one — being unable to tell which branch ran — and a user who
 * writes an unparseable condition already has the kernel's own rejection in hand and cannot act on our parser's
 * limits. Promoting {@link TextConditionCorrective.Outcome} to an {@code INFO} advisory on the envelope is a
 * published-surface change with its own decision; this makes the fact <em>knowable</em> first.
 */
class TextCorrectiveOutcomeTest {

    private static final String MODEL = CliTestSupport.orderRuledJson();
    private static final String UNION = "UNION FIX naming both remedies";

    private static ModelIndex index() {
        return ModelIndex.ofSelfContained(MODEL);
    }

    /** A kernel diagnostic on a code the catalog documents from more than one construct — corrective-eligible. */
    private static List<Diagnostic> kernelRejection() {
        return List.of(new Diagnostic(Severity.ERROR, DiagnosticSource.KERNEL,
                Optional.of("MVK_INVALID_TYPE_FOR_COMPARISON"), "type mismatch",
                new Diagnostic.Where("/Order/R", Optional.empty()), Optional.of(UNION), Optional.empty()));
    }

    /**
     * The discriminating pair, and the whole point of the change: two inputs that produce the <em>same</em>
     * diagnostics must still be distinguishable by outcome. Before this existed, both answered "union fix" and
     * nothing separated them.
     */
    @Test
    void anUnparseableConditionIsDistinguishableFromOneWithNoUniqueCandidate() {
        TextConditionCorrective corrective = new TextConditionCorrective();

        var unparseable = corrective.enrichWithOutcome(kernelRejection(), "[[[ &&& ???", "/Order", index());
        var noCandidate = corrective.enrichWithOutcome(kernelRejection(), "FieldFilled(/Order/CustomerName)", "/Order",
                index());

        assertEquals(TextConditionCorrective.Outcome.PARSE_FAILED, unparseable.outcome(),
                "the interpreter parser could not read the text — that is OUR limit, and it must be sayable");
        assertNotEquals(TextConditionCorrective.Outcome.PARSE_FAILED, noCandidate.outcome(),
                "a condition that parses fine must never report a parse failure");

        assertEquals(Optional.of(UNION), unparseable.diagnostics().get(0).fix(),
                "never worse than today: an unreadable condition still gets the union fix back unchanged");
    }

    /** A model index that cannot be built is its own outcome, not a parse failure and not a missing candidate. */
    @Test
    void anUnbuildableModelIndexSaysSo() {
        List<Diagnostic> out = TextConditionCorrective.enrich(kernelRejection(),
                "[/Order/OrderDate] < \"2024-12-31\"", "/Order", "{ not a model", ReferenceResolvers.selfContained());

        assertEquals(kernelRejection(), out, "unchanged diagnostics — the union fix stays");
        assertEquals(TextConditionCorrective.Outcome.MODEL_INDEX_FAILED,
                TextConditionCorrective.outcomeOf(kernelRejection(), "[DeliveryDate] == \"abc\"", "/Order",
                        "{ not a model", ReferenceResolvers.selfContained()),
                "an unreadable model is a different failure from an unreadable condition");
    }

    /** Text that parses but carries no comparison at all — a third distinct answer. */
    @Test
    void textWithNoComparisonIsItsOwnOutcome() {
        var result = new TextConditionCorrective()
                .enrichWithOutcome(kernelRejection(), "FieldFilled(/Order/CustomerName)", "/Order", index());

        assertEquals(TextConditionCorrective.Outcome.NO_COMPARISON, result.outcome(),
                "the text parsed and contained no comparison node — not a parse failure");
    }

    /** And the success path still reports success, so the enum cannot be satisfied by always answering a failure. */
    @Test
    void aNarrowedCorrectiveReportsUpgraded() {
        var result = new TextConditionCorrective()
                .enrichWithOutcome(kernelRejection(), "[/Order/OrderDate] < \"2024-12-31\"", "/Order", index());

        assertEquals(TextConditionCorrective.Outcome.UPGRADED, result.outcome(),
                "an ORDERING comparison of a date against an ISO literal is exactly the case the operand-matched "
                        + "corrective exists for (the D1 incident); if this is not UPGRADED the corrective itself "
                        + "regressed. Note the operator matters: MVK_INVALID_TYPE_FOR_COMPARISON is documented by "
                        + "the ordering operators, so an `==` form has no unique candidate and correctly keeps the "
                        + "union — that was my own wrong premise on the first attempt");
    }
}
