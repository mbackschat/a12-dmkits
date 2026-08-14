package io.github.mbackschat.a12.dm.rulekit.validate.laws;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft;
import io.github.mbackschat.a12.dm.rulekit.validate.DraftComputation;
import io.github.mbackschat.a12.dm.rulekit.validate.RuleValidator;
import io.github.mbackschat.a12.dm.rulekit.validate.ValidationResult;

/**
 * KERNEL-FINDINGS {@code KF187}, the missing half — the operand-list {@code Min}/{@code Max} group-operand
 * refusal measured on the <b>COMPUTATION</b> carrier rather than the rule carrier.
 *
 * <p><b>Why the rule rows do not settle it.</b> {@code GroupOperandCarrierAdmissionLawsTest} sweeps every carrier
 * through {@code RuleValidator.check(json, DraftRule)}, so its whole table is a statement about conditions in
 * RULES. The interpreter closure it licensed — that {@code minMaxOperandVals} needs no group branch because the
 * kernel refuses the shape — was then used to close a candidate defect, and that closure is only as wide as its
 * basis. Computations carry conditions too (a common precondition, and an alternative's guard), and nothing in
 * the rule measurement says the gate is shared: {@code KF187} itself is the finding that two carriers within one
 * FAMILY can disagree, so "a different carrier applies the same gate" is exactly the inference it forbids.
 *
 * <p>This file therefore measures the same two shapes where the earlier sweep could not reach, and the result is
 * the one the closure needs: <b>{@code MVK_NO_GROUPS_ALLOWED} on the computation carrier too</b>, in both the
 * common-precondition and the alternative-guard position, for the unstarred and the starred group alike.
 *
 * <p><b>What this still does not license</b> — the same limit {@code KF187} states and this file inherits: a
 * kernel refusal bounds what a STORED, VALIDATED model can contain. It does not make the interpreter path
 * unreachable, because {@code Expr} is public API and {@code rule eval --condition} runs with no MVK pre-flight.
 * The degrade contract for that channel is {@code interpreter … KernelRefusedShapeReachabilityTest}, and it is a
 * separate obligation rather than a consequence of these rows.
 */
class MinMaxGroupOperandComputeCarrierLawsTest {

    private static final RuleValidator VALIDATOR = new RuleValidator();

    /**
     * A NUMBER group beside a plain number target. All-number so the kind gate cannot fire first and mask the
     * operand-shape verdict — the same masking that forced the aggregate sweep onto its own fixture.
     */
    private static final String PAYOUT_JSON = DocumentModelDraft.named("payout").locale("en_US")
            .rootGroup("Claim", g -> {
                g.numberField("Reference", f -> f.fractionDigits(0, 0));
                g.numberField("Settlement", f -> f.fractionDigits(0, 2));
                g.group("Assessment", a -> {
                    a.numberField("Deductible", f -> f.fractionDigits(0, 2));
                    a.numberField("Excess", f -> f.fractionDigits(0, 2));
                });
                g.group("Payouts", p -> p.repeatable(10).numberField("Gross", f -> f.fractionDigits(0, 2)));
            }).build().toJson();

    /** One measured row: a condition in a named computation position, and the verdict it must draw. */
    private record Row(String label, String precondition, String operation, String verdict) { }

    private static String verdict(Row row) {
        DraftComputation comp = new DraftComputation("/Claim", "ProbeComp", "../Settlement", row.precondition(),
                List.of(new DraftComputation.Alternative(null, row.operation())), List.of());
        ValidationResult r = VALIDATOR.check(PAYOUT_JSON, comp);
        if (r.ok()) {
            return "ACCEPT";
        }
        return r.diagnostics().stream().flatMap(d -> d.code().stream()).filter(c -> c.startsWith("MVK_"))
                .findFirst().orElseGet(() -> "REJECT/" + r.diagnostics().stream()
                        .flatMap(d -> d.code().stream()).findFirst().orElse("no-code"));
    }

    /**
     * The whole table in one assertion. Row-by-row would abort at the first divergence and hide which position
     * moved, which is the one thing you need to see when a gate shifts.
     */
    @Test
    void theOperandListMinMaxRefusesAGroupOnTheComputationCarrierToo() {
        List<Row> rows = new ArrayList<>();
        // The COMMON PRECONDITION position.
        rows.add(new Row("Min/precondition/non-repeatable-group",
                "Min([/Claim/Assessment], 5) > 0", "[/Claim/Settlement]", "MVK_NO_GROUPS_ALLOWED"));
        rows.add(new Row("Max/precondition/non-repeatable-group",
                "Max([/Claim/Assessment], 5) > 0", "[/Claim/Settlement]", "MVK_NO_GROUPS_ALLOWED"));
        rows.add(new Row("Min/precondition/starred-repeatable-group",
                "Min([/Claim/Payouts*], 5) > 0", "[/Claim/Settlement]", "MVK_NO_GROUPS_ALLOWED"));

        // The OPERATION position — a different slot of the same record, and the one the rule carrier has no
        // counterpart for at all.
        rows.add(new Row("Min/operation/non-repeatable-group",
                "FieldFilled(/Claim/Reference)", "Min([/Claim/Assessment], 5)", "MVK_NO_GROUPS_ALLOWED"));
        rows.add(new Row("Max/operation/starred-repeatable-group",
                "FieldFilled(/Claim/Reference)", "Max([/Claim/Payouts*], 5)", "MVK_NO_GROUPS_ALLOWED"));

        // The CONTROLS. Without them every refusal row above is also satisfied by a fixture that rejects
        // everything, and the starred-FIELD row additionally separates the two gates: it draws a DIFFERENT code,
        // so the starred-GROUP rows above cannot be the wildcard gate firing under a group-shaped label.
        rows.add(new Row("Min/operation/field-operands-control",
                "FieldFilled(/Claim/Reference)",
                "Min([/Claim/Assessment/Deductible], [/Claim/Assessment/Excess])", "ACCEPT"));
        rows.add(new Row("Min/operation/starred-FIELD-separates-the-wildcard-gate",
                "FieldFilled(/Claim/Reference)", "Min([/Claim/Payouts*/Gross], 5)", "MVK_NO_WILDCARDS_ALLOWED"));

        List<String> mismatches = new ArrayList<>();
        for (Row row : rows) {
            String actual = verdict(row);
            if (!row.verdict().equals(actual)) {
                mismatches.add("%s: expected %s, got %s%n    precondition: %s%n    operation:    %s"
                        .formatted(row.label(), row.verdict(), actual, row.precondition(), row.operation()));
            }
        }
        assertEquals(List.of(), mismatches,
                "the compute-carrier group-operand table shifted; each line names one position whose gate moved");
    }
}
