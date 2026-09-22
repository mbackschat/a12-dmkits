package io.github.mbackschat.a12.dm.rulekit.validate.laws;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft;
import io.github.mbackschat.a12.dm.modelkit.FieldKind;
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel;
import io.github.mbackschat.a12.dm.rulekit.dsl.Condition;
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl;
import io.github.mbackschat.a12.dm.rulekit.dsl.FieldListRef;
import io.github.mbackschat.a12.dm.rulekit.dsl.NumberExpr;
import io.github.mbackschat.a12.dm.rulekit.validate.ValidationResult;

/**
 * KERNEL-FINDINGS §9 — what BINDS a {@code CurrentRepetition} operand, measured on one host.
 *
 * <p><b>The claim under test, and it is not the one either estate held.</b> Both this repository's operator
 * catalog and a12-kernel-lean's {@code spec/07} stated ONE binding source: the enclosing iteration must iterate
 * over the named group, else {@code MVK_NO_WILDCARD}. a12-kernel-lean's {@code SPEC-2026-09-04-03} reports two
 * sources selected by the {@code $} marker — an UNMARKED {@code CurrentRepetition(G)} is satisfied by the
 * enclosing iteration <em>or</em> by an operand's own star that reopens {@code G}, while a MARKED
 * {@code CurrentRepetition($G)} reads the captured environment and needs the enclosing iteration. The
 * single-source account predicts row 1 below refused, so that row alone separates the two.
 *
 * <p><b>Why this is measured here and not taken from their table.</b> A peer's measurement is their report, not
 * a pin, and this repository's own shipped surface — the catalog line {@code dmtool operators CurrentRepetition}
 * serves — is what the correction has to change. So the rows are re-derived on our own carrier, in our own
 * domain, through the typed builder, with the kernel as the oracle: accept/reject is terminal at
 * {@code RuleValidator}, which runs the kernel's own {@code checkConsistency}.
 *
 * <p><b>Every condition is rendered from the typed builder</b> rather than hand-written, so each row measures two
 * things at once: whether this project can author the shape, and whether the kernel admits it. A shape our
 * builder refused but the kernel accepted would be an over-strict client-side refusal, a defect on sight here.
 *
 * <p><b>The controls, and what each one rules out.</b> The unfiltered aggregate and the aggregate whose filter
 * carries an ordinary field leaf are both admitted from this host, so neither the star nor the filter is what row
 * 2 refuses. The starless row is the one that shows the STAR rather than the marker's absence does the binding:
 * the same unmarked operand, from the same host, with no wildcard anywhere, is refused. Without it "unmarked is
 * simply tolerated" fits rows 1–4 exactly.
 *
 * <p><b>What stays unmeasured here.</b> This file is the accept/reject tier only. Whether the interpreter
 * EVALUATES the admitted row the way the kernel does is a runtime claim needing a differential, and it is locked
 * separately by {@code adapter.laws.CurrentRepetitionOperandStarBindingDiffTest} — the two tiers are kept apart
 * because a client that admits a shape it cannot then evaluate is a distinct defect from one that refuses it.
 *
 * <p>Own domain: an insurance policy carrying endorsements, each with a premium and a waiver amount.
 */
class CurrentRepetitionBindingSourceLawsTest {

    private static final String GROUP = "/Policy";
    private static final String ERROR_FIELD = "../SettlementTotal";
    private static final String ADMITTED = "admitted";
    private static final String NO_WILDCARD = "MVK_NO_WILDCARD";

    /**
     * A NONREPEATABLE host group carrying the error field, and one repeatable subgroup an operand can star.
     * The host's non-repeatability is what makes it a non-iterating rule: no enclosing iteration exists over
     * {@code Endorsements} for any condition declared here.
     */
    private static final String MODEL = DocumentModelDraft.named("policy-repetition-binding").locale("en_US")
            .rootGroup("Policy", root -> root
                    .field("SettlementTotal", FieldKind.NUMBER)
                    .group("Endorsements", endorsements -> endorsements.repeatable(50)
                            .field("Premium", FieldKind.NUMBER)
                            .field("Waiver", FieldKind.NUMBER)))
            .build().toJson();

    private static final BoundModel M = BoundModel.ofJson(MODEL);

    @Test
    void anOperandsOwnStarBindsAnUnmarkedCurrentRepetitionFromANonIteratingHost() {
        Map<String, String> actual = new LinkedHashMap<>();

        actual.put("filter carries an UNMARKED CurrentRepetition over the starred level",
                verdict(filteredSum(unmarkedIndex().gt(0))));
        actual.put("the same filter MARKED — the only difference from row 1",
                verdict(filteredSum(markedIndex().gt(0))));
        actual.put("the same aggregate UNFILTERED — control", verdict(unfilteredSum()));
        actual.put("the same aggregate with an ordinary field leaf in the filter — control",
                verdict(filteredSum(M.num("/Policy/Endorsements/Waiver").gt(0))));
        actual.put("the UNMARKED operand with no star anywhere — outside any filter",
                verdict(unmarkedIndex().gt(0)
                        .and(Dsl.fieldFilled(M.field("/Policy/SettlementTotal")))));

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("filter carries an UNMARKED CurrentRepetition over the starred level", ADMITTED);
        expected.put("the same filter MARKED — the only difference from row 1", NO_WILDCARD);
        expected.put("the same aggregate UNFILTERED — control", ADMITTED);
        expected.put("the same aggregate with an ordinary field leaf in the filter — control", ADMITTED);
        expected.put("the UNMARKED operand with no star anywhere — outside any filter", NO_WILDCARD);
        assertEquals(expected, actual,
                "an unmarked CurrentRepetition has TWO binding sources — the enclosing iteration or an operand's "
                        + "own star; the marked form reads the captured environment and keeps only the first");
    }

    // ---- fixtures --------------------------------------------------------------------------------

    /** {@code CurrentRepetition(/Policy/Endorsements)} — the form an operand's own star can bind. */
    private static NumberExpr unmarkedIndex() {
        return Dsl.currentRepetition(M.group("/Policy/Endorsements"));
    }

    /** {@code CurrentRepetition($/Policy/Endorsements)} — the captured-environment form. */
    private static NumberExpr markedIndex() {
        return Dsl.currentRepetition(M.group("/Policy/Endorsements"), true);
    }

    /** {@code Sum(the flatten of Endorsements* then Premium Having filter) > 0}, guarded by the error field. */
    private static Condition filteredSum(Condition filter) {
        FieldListRef premiums = M.flatten("/Policy/Endorsements*/Premium");
        return Dsl.sum(premiums.having(filter)).gt(0)
                .and(Dsl.fieldFilled(M.field("/Policy/SettlementTotal")));
    }

    private static Condition unfilteredSum() {
        return Dsl.sum(M.flatten("/Policy/Endorsements*/Premium")).gt(0)
                .and(Dsl.fieldFilled(M.field("/Policy/SettlementTotal")));
    }

    /** {@code admitted}, or the COMPLETE emitted code list — a partial list would hide a co-firing gate. */
    private static String verdict(Condition condition) {
        ValidationResult r = KernelLaws.checkModel(MODEL, GROUP, ERROR_FIELD, KernelLaws.render(condition));
        return r.ok() ? ADMITTED : r.diagnostics().stream()
                .map(d -> d.code().orElse("(no code: " + d.summary() + ")")).collect(Collectors.joining(" + "));
    }
}
