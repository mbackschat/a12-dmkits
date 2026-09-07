package io.github.mbackschat.a12.dm.rulekit.validate.laws;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft;
import io.github.mbackschat.a12.dm.modelkit.FieldKind;
import io.github.mbackschat.a12.dm.rulekit.validate.ValidationResult;

/**
 * KERNEL-FINDINGS §11 — <b>a wrong-kind comparison meets TWO gates, and which one answers is decided by the
 * COMPARISON OPERATOR</b>.
 *
 * <p>Ordering ({@code < <= > >=}) runs an ORDERABILITY gate over <em>both</em> operands first: whichever side
 * fails it, the rejection is {@code MVK_INVALID_TYPE_FOR_COMPARISON}, and nothing about the other side or the
 * literal's family changes that. Operands that survive it — and equality's operands, which never meet it —
 * then reach the kind-match gate, whose refusals are the kind-specific {@code MVK_INVALID_COMPARE_TO_*}
 * classes. Orderable kinds are NUMBER, DATE, TIME and DATETIME; STRING, ENUM, BOOLEAN, CONFIRM, DATE_RANGE and
 * CUSTOM are not.
 *
 * <p><b>Why this file exists, and it is a correction.</b> {@link WrongKindComparisonClassInvarianceLawsTest}
 * measures a 10-kind grid across two literal families and finds one class per kind — but every cell in it
 * writes {@code ==}. The comparison operator was never an axis there, so that grid could not see the gate that
 * actually moves the class, and its two columns differ only where the literal family flips a cell to ADMITTED.
 * a12-kernel-lean reported the same kinds collapsing to {@code MVK_INVALID_TYPE_FOR_COMPARISON}; our published
 * answer was that the code "appears in no cell" and the divergence was unexplained. Both estates were right
 * about the column they measured: theirs varied the operator and the literal family together, ours held the
 * operator fixed. Measured 2026-09-07 after they sent their retained operand list.
 *
 * <p><b>The discriminating cell is the last pair below</b>, and no rival account survives it: the SAME field
 * compared against ITSELF is refused under ordering and ADMITTED under equality. Identical operands, identical
 * kinds, one variable. Two rivals die on the rows above it — "ordering always reports
 * {@code MVK_INVALID_TYPE_FOR_COMPARISON} for a wrong-kind comparison" (a DATE ordered against a numeric
 * literal reports {@code MVK_INVALID_COMPARE_TO_DATE}), and "the LEFT operand's orderability decides" (an
 * orderable NUMBER on the left of a CONFIRM still reports the orderability class, as does a bare literal).
 *
 * <p>Own domain (an e-commerce product catalog).
 */
class WrongKindComparisonOperatorGateLawsTest {

    private static final String TYPE = "MVK_INVALID_TYPE_FOR_COMPARISON";
    private static final String DATE = "MVK_INVALID_COMPARE_TO_DATE";
    private static final String YES = "MVK_INVALID_COMPARE_TO_YES";
    private static final String YESNO = "MVK_INVALID_COMPARE_TO_YESNO";
    private static final String DATE_RANGE = "MVK_INVALID_COMPARE_TO_DATE_RANGE";
    private static final String ENUM_OR_STRING = "MVK_INVALID_COMPARE_TO_ENUM_OR_STRING";

    private static final String CATALOG = DocumentModelDraft.named("catalog-operator-gate").locale("en_US")
            .rootGroup("Catalog", c -> c
                    .stringField("Sku", s -> { })
                    .group("Lines", g -> g.repeatable(20)
                            .numberField("LineQty", n -> n.fractionDigits(0))
                            .stringField("LineLabel", s -> { })
                            .enumField("LineGrade", e -> e.value("A").value("B"))
                            .field("LineActive", FieldKind.BOOLEAN)
                            .field("LineApproved", FieldKind.CONFIRM)
                            .field("LineApprovedB", FieldKind.CONFIRM)
                            .field("LineActiveB", FieldKind.BOOLEAN)
                            .dateRangeField("LineWindow", r -> r.format("dd.MM.yyyy").separator("-"))
                            .dateField("LineDate", d -> d.format("yyyy-MM-dd"))
                            .timeField("LineClock", t -> t.format("HH:mm:ss"))
                            .dateTimeField("LineStamp", t -> t.format("yyyy-MM-dd'T'HH:mm:ss"))
                            .customField("LineSerial", t -> t.type("WarehouseSerial"))))
            .build().toJson();

    private static String f(String name) {
        return "[/Catalog/Lines/" + name + "]";
    }

    private static String verdict(String condition) {
        ValidationResult result = KernelLaws.checkModel(CATALOG, "/Catalog/Lines", "../LineQty",
                "FieldFilled(/Catalog/Lines/LineQty) And " + condition);
        return result.ok() ? KernelLaws.ADMITTED : result.diagnostics().stream()
                .map(d -> d.code().orElse("(no code: " + d.summary() + ")"))
                .collect(Collectors.joining(","));
    }

    /** The six kinds that are not orderable; each answers equality with a class of its own. */
    private static final List<String[]> NON_ORDERABLE = List.of(
            new String[] {"CONFIRM", "LineApproved", YES},
            new String[] {"BOOLEAN", "LineActive", YESNO},
            new String[] {"DATE_RANGE", "LineWindow", DATE_RANGE},
            new String[] {"STRING", "LineLabel", ENUM_OR_STRING},
            new String[] {"ENUM", "LineGrade", ENUM_OR_STRING},
            new String[] {"CUSTOM", "LineSerial", ENUM_OR_STRING});

    /**
     * The operator, not the literal family, decides which gate answers — and the numeric literal is held fixed
     * across both columns so the operator is the only thing that moves.
     */
    @Test
    void orderingReportsTheOrderabilityClassWhereEqualityReportsTheKindClass() {
        Map<String, String> actual = new LinkedHashMap<>();
        Map<String, String> expected = new LinkedHashMap<>();

        for (String[] kind : NON_ORDERABLE) {
            actual.put(kind[0] + " ordered against a numeric literal", verdict(f(kind[1]) + " > 0"));
            expected.put(kind[0] + " ordered against a numeric literal", TYPE);
            actual.put(kind[0] + " equated with a numeric literal", verdict(f(kind[1]) + " == 0"));
            expected.put(kind[0] + " equated with a numeric literal", kind[2]);
        }

        assertEquals(expected, actual, "the comparison operator selects the gate");
    }

    /**
     * An ORDERABLE kind survives the first gate and reaches the second, so ordering does not collapse every
     * wrong-kind comparison to one class. This is the row that refutes "ordering always reports the
     * orderability class", which the grid above alone is consistent with.
     */
    @Test
    void anOrderableKindPassesTheFirstGateAndIsJudgedByTheSecond() {
        Map<String, String> actual = new LinkedHashMap<>();
        Map<String, String> expected = new LinkedHashMap<>();

        for (String temporal : List.of("LineDate", "LineClock", "LineStamp")) {
            actual.put(temporal + " ordered against a numeric literal", verdict(f(temporal) + " > 0"));
            expected.put(temporal + " ordered against a numeric literal", DATE);
        }
        // Both orderable, kinds match: the pair the two gates exist to admit.
        actual.put("NUMBER ordered against a numeric literal", verdict(f("LineQty") + " > 0"));
        expected.put("NUMBER ordered against a numeric literal", KernelLaws.ADMITTED);
        // Orderable LEFT, non-orderable RIGHT: the first gate still answers, so it reads both operands.
        actual.put("DATE ordered against a String literal", verdict(f("LineDate") + " > \"pending\""));
        expected.put("DATE ordered against a String literal", TYPE);

        assertEquals(expected, actual, "an orderable operand reaches the kind gate");
    }

    /** The orderability gate reads BOTH operands — neither "the left decides" nor "the field decides". */
    @Test
    void theOrderabilityGateReadsBothOperandsRatherThanTheLeftOne() {
        Map<String, String> actual = new LinkedHashMap<>();
        Map<String, String> expected = new LinkedHashMap<>();

        actual.put("orderable NUMBER left, CONFIRM right", verdict(f("LineQty") + " > " + f("LineApproved")));
        expected.put("orderable NUMBER left, CONFIRM right", TYPE);
        actual.put("CONFIRM left, orderable NUMBER right", verdict(f("LineApproved") + " > " + f("LineQty")));
        expected.put("CONFIRM left, orderable NUMBER right", TYPE);
        actual.put("numeric LITERAL left, CONFIRM right", verdict("0 > " + f("LineApproved")));
        expected.put("numeric LITERAL left, CONFIRM right", TYPE);
        // Two orderable fields of different kinds reach the second gate, under either operator.
        actual.put("DATE ordered against a NUMBER field", verdict(f("LineDate") + " > " + f("LineQty")));
        expected.put("DATE ordered against a NUMBER field", DATE);
        actual.put("DATE equated with a NUMBER field", verdict(f("LineDate") + " == " + f("LineQty")));
        expected.put("DATE equated with a NUMBER field", DATE);

        assertEquals(expected, actual, "orderability is required of both sides");
    }

    /**
     * The cell that settles it, and the reason this file can claim a mechanism rather than a correlation: the
     * SAME field on both sides. Kind, declaration, carrier and operand order are all held identical, the
     * comparison operator is the only variable, and the verdict moves with it.
     *
     * <p><b>CONFIRM is the exception, and it is not one to the rule under test.</b> Five kinds go
     * refused-to-ADMITTED; CONFIRM stays refused under equality with {@code MVK_INVALID_COMPARE_TO_YES},
     * because its comparand domain is the {@code True} constant ALONE — it refuses every field on the right,
     * including another CONFIRM ({@link #aConfirmFieldIsComparableOnlyToTheTrueConstant}). So its equality cell
     * is decided before kind-matching is reached and says nothing about the operator gate, while its ordering
     * cell still reports the orderability class like the other five. Written out rather than skipped: a kind
     * quietly dropped from a grid is how the axis this file corrects went unmeasured in the first place.
     */
    @Test
    void oneFieldComparedWithItselfIsRefusedUnderOrderingAndAdmittedUnderEquality() {
        Map<String, String> actual = new LinkedHashMap<>();
        Map<String, String> expected = new LinkedHashMap<>();

        for (String[] kind : NON_ORDERABLE) {
            String self = f(kind[1]) + " %s " + f(kind[1]);
            actual.put(kind[0] + " ordered against itself", verdict(String.format(self, ">")));
            expected.put(kind[0] + " ordered against itself", TYPE);
            actual.put(kind[0] + " equated with itself", verdict(String.format(self, "==")));
            expected.put(kind[0] + " equated with itself",
                    "CONFIRM".equals(kind[0]) ? YES : KernelLaws.ADMITTED);
        }

        assertEquals(expected, actual,
                "identical operands, identical kinds — only the operator moves, and the verdict moves with it");
    }

    /**
     * Why CONFIRM sits out the cell above: it compares to the {@code True} constant and to nothing else.
     *
     * <p>This is a narrower domain than BOOLEAN's, which is the control here — the two kinds render the same
     * way and read as interchangeable, but a BOOLEAN admits a field on the right and a CONFIRM does not, not
     * even a CONFIRM of its own kind. The DSL spelling of the constant is {@code True}; {@code Yes} is not a
     * token, so the code's name is about the CONCEPT and not about text a rule may contain.
     */
    @Test
    void aConfirmFieldIsComparableOnlyToTheTrueConstant() {
        Map<String, String> actual = new LinkedHashMap<>();
        Map<String, String> expected = new LinkedHashMap<>();

        actual.put("CONFIRM against the True constant", verdict(f("LineApproved") + " == True"));
        expected.put("CONFIRM against the True constant", KernelLaws.ADMITTED);
        actual.put("CONFIRM against another CONFIRM field",
                verdict(f("LineApproved") + " == " + f("LineApprovedB")));
        expected.put("CONFIRM against another CONFIRM field", YES);
        actual.put("CONFIRM against a BOOLEAN field", verdict(f("LineApproved") + " == " + f("LineActive")));
        expected.put("CONFIRM against a BOOLEAN field", YES);

        // The control: BOOLEAN, which renders alike and does admit a field on the right.
        actual.put("BOOLEAN against another BOOLEAN field",
                verdict(f("LineActive") + " == " + f("LineActiveB")));
        expected.put("BOOLEAN against another BOOLEAN field", KernelLaws.ADMITTED);
        actual.put("BOOLEAN against the True constant", verdict(f("LineActive") + " == True"));
        expected.put("BOOLEAN against the True constant", KernelLaws.ADMITTED);

        assertEquals(expected, actual, "CONFIRM's comparand domain is the constant alone");
    }
}
