package io.github.mbackschat.a12.dm.rulekit.validate.laws;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft;
import io.github.mbackschat.a12.dm.modelkit.FieldKind;
import io.github.mbackschat.a12.dm.rulekit.validate.DraftComputation;
import io.github.mbackschat.a12.dm.rulekit.validate.RuleValidator;
import io.github.mbackschat.a12.dm.rulekit.validate.ValidationResult;

/**
 * KERNEL-FINDINGS §11 — <b>which class a wrong-kind CONSTANT assignment reports is an ordered ladder over the
 * (constant family, target kind) PAIR</b>, not a function of either side alone.
 *
 * <p>Our typed builder refuses every cell below before the kernel sees it ({@code Computation.Builder}'s
 * kind/format gate), so none of this is authorable through the public surface and none of it was measured
 * here. That is the same blind spot {@link ConfirmComputationSourceKindLawsTest} was written for, and it uses
 * the same escape: raw operation strings through {@link DraftComputation}, which reach the kernel without
 * passing our pre-check. Without that route an over-broad refusal is invisible, and so is the kernel's own
 * choice of class.
 *
 * <p><b>Why the pair matters and a side-keyed reading survives a careless sample.</b> Most of the grid IS a
 * function of the target alone, so a batch that holds the constant fixed and varies the target produces a
 * clean target-keyed table — and would be wrong in exactly the two positions where it isn't. Those are the
 * temporal-constant column (rung 2) and the Boolean-constant-into-NUMBER cell (rung 3). Both are measured
 * below against same-target controls carrying a different constant, so the pair-dependence is visible rather
 * than assumed.
 *
 * <p><b>The literal spelling is load-bearing and cost a12-kernel-lean a control.</b> A temporal constant must
 * be DOUBLE-QUOTED; a bare {@code 05.03.2024} lexes as a Number and quietly measures a different row. The
 * {@link #aBareDottedLiteralIsNotATemporalConstant} row pins that, because a fixture defect here does not
 * announce itself — it shows up as a neighbouring cell reporting a scale class.
 *
 * <p>Reported by a12-kernel-lean (their {@code LF153} / {@code SPEC-2026-09-06-03}, 2026-09-06); re-derived
 * here on our own fixture rather than cited, since our diagnostic surface is what would carry the corrective.
 *
 * <p>Own domain (an insurance claim intake).
 */
class ConstantAssignmentDiagnosticLadderLawsTest {

    private static final RuleValidator VALIDATOR = new RuleValidator();

    private static final String TO_YESNO = "MVK_INVALID_COMPARE_TO_YESNO";
    private static final String TO_YES = "MVK_INVALID_COMPARE_TO_YES";
    private static final String TO_DATE = "MVK_INVALID_COMPARE_TO_DATE";
    private static final String ENUM_OR_STRING = "MVK_INVALID_COMPARE_TO_ENUM_OR_STRING";
    private static final String INCONSISTENT_TYPES = "MVK_INCONSISTENT_TYPES_COMPARED";

    /** One group carrying a target of every kind the ladder names, so no row changes model. */
    private static final String MODEL = DocumentModelDraft.named("claim-intake").locale("en_US")
            .rootGroup("Claim", claim -> claim
                    .field("Escalated", FieldKind.BOOLEAN)
                    .field("Acknowledged", FieldKind.CONFIRM)
                    .stringField("Handler", s -> { })
                    .numberField("Assessed", n -> n.fractionDigits(0))
                    .dateField("SettledOn", d -> d.format("yyyy-MM-dd"))
                    // Additive, for the filter carrier only: a repeatable group with a STRING leaf, so a
                    // Having filter can compare a field against a literal. No direct-field row reads it.
                    .group("Notes", n -> n.repeatable(20)
                            .stringField("NoteText", t -> { })))
            .build().toJson();

    /**
     * Both are DOUBLE-QUOTED, and that is the point: the kernel lexer admits one string-literal syntax
     * ({@code StringLiteral : '"' ( '""' | ~["] )* '"'}), so a "temporal literal" is not a separate spelling
     * — it is a string literal whose CONTENT parses as a date. Single quotes are not a literal at all and
     * draw {@code MVK_UNEXPECTED_TOKEN}, which is how a mis-spelled row silently leaves the grid.
     */
    private static final String TEMPORAL = "\"05.03.2024\"";
    private static final String STRING = "\"pending\"";
    private static final String NUMBER = "42";
    private static final String TRUE = "True";

    private static String verdict(String targetRelPath, String operation) {
        ValidationResult r = VALIDATOR.check(MODEL, new DraftComputation("/Claim", "LadderProbe",
                targetRelPath, null, List.of(new DraftComputation.Alternative(null, operation))));
        return r.ok() ? KernelLaws.ADMITTED
                : String.join(",", r.diagnostics().stream().flatMap(d -> d.code().stream()).toList());
    }

    /**
     * Rung 1 — the two flag kinds answer for their target whatever the constant is, and they do NOT share a
     * class. The temporal row is the one that shows this rung outranks rung 2.
     */
    @Test
    void aFlagTargetReportsItsOwnClassWhateverTheConstantIs() {
        Map<String, String> actual = new LinkedHashMap<>();
        actual.put("string into BOOLEAN", verdict("../Escalated", STRING));
        actual.put("number into BOOLEAN", verdict("../Escalated", NUMBER));
        actual.put("temporal into BOOLEAN", verdict("../Escalated", TEMPORAL));
        actual.put("string into CONFIRM", verdict("../Acknowledged", STRING));
        actual.put("number into CONFIRM", verdict("../Acknowledged", NUMBER));
        actual.put("temporal into CONFIRM", verdict("../Acknowledged", TEMPORAL));

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("string into BOOLEAN", TO_YESNO);
        expected.put("number into BOOLEAN", TO_YESNO);
        expected.put("temporal into BOOLEAN", TO_YESNO);
        expected.put("string into CONFIRM", TO_YES);
        expected.put("number into CONFIRM", TO_YES);
        expected.put("temporal into CONFIRM", TO_YES);
        assertEquals(expected, actual, "BOOLEAN and CONFIRM answer for the target and carry different classes");
    }

    /**
     * Rung 2 — the pair-dependent one, and the reason a corrective keyed on the target would mislead: a
     * temporal constant reports the DATE class at a target that is not temporal at all.
     *
     * <p>Each row is paired with a same-target control carrying a non-temporal constant, so the cell is
     * attributable to the CONSTANT rather than to the target. Without those controls this table is equally
     * well explained by "String and Number targets happen to report TO_DATE", which is false.
     */
    @Test
    void aTemporalConstantReportsTheDateClassAtANonTemporalTarget() {
        Map<String, String> actual = new LinkedHashMap<>();
        actual.put("temporal into STRING", verdict("../Handler", TEMPORAL));
        actual.put("number into STRING — control", verdict("../Handler", NUMBER));
        actual.put("temporal into NUMBER", verdict("../Assessed", TEMPORAL));
        actual.put("string into NUMBER — control", verdict("../Assessed", STRING));

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("temporal into STRING", TO_DATE);
        expected.put("number into STRING — control", ENUM_OR_STRING);
        expected.put("temporal into NUMBER", TO_DATE);
        expected.put("string into NUMBER — control", ENUM_OR_STRING);
        assertEquals(expected, actual, "the temporal constant carries its own class onto a non-temporal target");
    }

    /**
     * The SECOND pair-dependent cell: at one NUMBER target, a string constant and a Boolean one report
     * different classes, so even rung 3 is not purely target-keyed.
     *
     * <p>Both rows share the target, so the difference is attributable to the constant alone. This also puts a
     * live firing site under {@code MVK_INCONSISTENT_TYPES_COMPARED}, which our MVK ledger classifies
     * {@code erased} — true of the comparison surface the row describes, and worth not confusing with this
     * assignment one.
     */
    @Test
    void aBooleanConstantIntoANumberTargetReportsADifferentClassThanAStringOne() {
        Map<String, String> actual = new LinkedHashMap<>();
        actual.put("boolean into NUMBER", verdict("../Assessed", TRUE));
        actual.put("string into NUMBER — same target, other constant", verdict("../Assessed", STRING));

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("boolean into NUMBER", INCONSISTENT_TYPES);
        expected.put("string into NUMBER — same target, other constant", ENUM_OR_STRING);
        assertEquals(expected, actual, "one target, two constants, two classes — rung 3 is not target-keyed");
    }

    /** Rung 3 — with no temporal constant and no flag target, the target's kind decides. */
    @Test
    void otherwiseTheTargetKindDecides() {
        Map<String, String> actual = new LinkedHashMap<>();
        actual.put("string into DATE", verdict("../SettledOn", STRING));
        actual.put("number into DATE", verdict("../SettledOn", NUMBER));

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("string into DATE", TO_DATE);
        expected.put("number into DATE", TO_DATE);
        assertEquals(expected, actual, "a temporal target reports the date class for any non-temporal constant");
    }

    /**
     * <b>The ladder is the COMPARISON vocabulary, not an assignment table.</b> The same class fires on an
     * ordinary rule condition with no computation and no assignment anywhere — a String field compared to a
     * date-shaped literal — with a non-date literal at the identical site admitted as the control.
     *
     * <p>This is why the {@code MVK_INVALID_COMPARE_TO_DATE} corrective must not speak of a "target": there is
     * no target here. The first version of that hint did, having been written from the assignment carrier
     * alone, which is the same comparison-versus-assignment split that had left three MVK-ledger rows
     * incomplete one level in. a12-kernel-lean reported the filter carrier (2026-09-06); this row measures the
     * plainest case of it here rather than citing theirs.
     */
    @Test
    void theSameClassFiresOnAPlainComparisonWithNoAssignmentInvolved() {
        KernelLaws.rejectsModel(MODEL, "/Claim", "../Handler",
                "FieldFilled(Handler) And [Handler] == " + TEMPORAL, TO_DATE);
        KernelLaws.acceptsModel(MODEL, "/Claim", "../Handler",
                "FieldFilled(Handler) And [Handler] == " + STRING);
    }

    /**
     * The THIRD carrier, and the one our shipped corrective names: a comparison inside a {@code Having}
     * filter. Measured here because that corrective tells users the code fires in a filter, and a peer's run
     * is their report rather than something our own artifact may rest on.
     *
     * <p>The non-date literal at the identical site is the control — without it, a refusal is equally well
     * explained by the filter rejecting a literal comparison outright.
     */
    @Test
    void theSameClassFiresInsideAHavingFilter() {
        String filtered = "FieldFilled(Handler) And NumberOfFilledFields(Notes*/NoteText Having "
                + "[Notes/NoteText] == %s) > 0";
        KernelLaws.rejectsModel(MODEL, "/Claim", "../Handler", String.format(filtered, TEMPORAL), TO_DATE);
        KernelLaws.acceptsModel(MODEL, "/Claim", "../Handler", String.format(filtered, STRING));
    }

    /**
     * The fixture control. A bare dotted literal is NOT a temporal constant — it lexes as a number — so a row
     * written without the quotes silently measures the number column. Pinned so a later edit that drops the
     * quotes fails here rather than quietly moving rung 2.
     */
    @Test
    void aBareDottedLiteralIsNotATemporalConstant() {
        assertEquals(verdict("../Handler", "05.03.2024"), verdict("../Handler", NUMBER),
                "unquoted, the dotted literal must behave as the NUMBER column, not the temporal one");
    }
}
