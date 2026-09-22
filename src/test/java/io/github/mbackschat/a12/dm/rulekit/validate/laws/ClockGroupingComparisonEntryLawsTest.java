package io.github.mbackschat.a12.dm.rulekit.validate.laws;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft;
import io.github.mbackschat.a12.dm.modelkit.FieldKind;
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel;
import io.github.mbackschat.a12.dm.rulekit.dsl.Condition;
import io.github.mbackschat.a12.dm.rulekit.dsl.DateRef;
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl;
import io.github.mbackschat.a12.dm.rulekit.dsl.FieldRef;
import io.github.mbackschat.a12.dm.rulekit.dsl.NumberRef;

/**
 * How a BARE clock reaches the comparison's entry gates, and what a {@code { … }} group around it does — on
 * BOTH partners, because the group pushes the verdict in opposite directions depending on what the clock is
 * compared against.
 *
 * <p>Measured to discharge a12-kernel-lean's {@code SPEC-2026-09-08-04}, whose acceptance asks for the
 * singly grouped {@code Today}/{@code Now} admissions against bare and doubly grouped refusals, and for the
 * class difference between {@code Today < 1} and {@code 1 < Today}.
 *
 * <p><b>The second half confirms; the first half does not hold as worded, and the reason is the partner.</b>
 * Against a NUMBER constant a group never admits anything — it converts one refusal into another
 * ({@code MVK_INVALID_COMPARE_TO_DATE} to {@code MVK_INVALID_COMPARE_TO_CONST}), and a second group changes
 * nothing further. Against ANOTHER CLOCK a group does admit, and so does a second one (KF445). So "singly
 * grouped admits, doubly grouped refuses" is true on neither partner, while the entry's own delta — braces
 * preserve constantness and remove bare-clock recognition — is exactly what the number-partner rows show.
 *
 * <p><b>Both partners are measured HERE rather than one being inferred from the other.</b> KF445's rows are
 * on the clock-against-clock carrier; repeating them beside the number rows is what makes the opposite
 * directions a single observation instead of two facts a reader has to combine.
 *
 * <p><b>Authoring: this law's subject is concrete SYNTAX, so most rows cannot be an AST.</b> A brace group is
 * not a node — the typed kit builds an expression tree and the renderer decides parenthesisation, so
 * {@code Today} and {@code {Today}} have the same typed spelling and the variable under test would vanish.
 * The rows that are ordinary comparisons are authored through the kit, and the rest carry the text this law
 * exists to vary.
 */
class ClockGroupingComparisonEntryLawsTest {

    private static final String GROUP = "/Order";

    private static final String ORDER_JSON = DocumentModelDraft.named("order").locale("en_US")
            .rootGroup("Order", g -> {
                g.stringField("Reference", f -> { });
                g.numberField("Quantity", n -> n.fractionDigits(0));
                g.field("PlacedOn", FieldKind.DATE);
            }).build().toJson();

    private static final BoundModel M = BoundModel.ofJson(ORDER_JSON);

    // Each path resolved once into a typed handle, then reused by every row that needs it.
    private static final FieldRef REFERENCE = M.field(GROUP + "/Reference");
    private static final NumberRef QUANTITY = M.num(GROUP + "/Quantity");
    private static final DateRef PLACED_ON = M.date(GROUP + "/PlacedOn");

    /** The typed path, for the rows whose shape the kit can express. */
    private static String verdict(Condition comparison) {
        return KernelLaws.verdictModel(ORDER_JSON, GROUP, "../Reference",
                Dsl.fieldFilled(REFERENCE).and(comparison));
    }

    /** The text path, for a brace depth or a wrong-kind partner that has no typed spelling. */
    private static String verdict(String comparison) {
        return KernelLaws.verdictModel(ORDER_JSON, GROUP, "../Reference",
                "FieldFilled(Reference) And " + comparison);
    }

    /**
     * Against a NUMBER constant, a group moves the refusal from the date-kind gate to the constant gate and
     * stops there. The field control is what proves the ladder is live rather than uniformly refusing.
     */
    @Test
    void aGroupAroundAClockComparedToANumberConstantOnlyMovesWhichGateRefuses() {
        Map<String, String> actual = new LinkedHashMap<>();
        actual.put("a field against a constant — the liveness control", verdict(QUANTITY.lt(1)));
        actual.put("bare Today", verdict("Today < 1"));
        actual.put("singly grouped Today", verdict("{Today} < 1"));
        actual.put("doubly grouped Today", verdict("{{Today}} < 1"));
        actual.put("bare Now", verdict("Now < 1"));
        actual.put("singly grouped Now", verdict("{Now} < 1"));
        actual.put("doubly grouped Now", verdict("{{Now}} < 1"));

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("a field against a constant — the liveness control", KernelLaws.ADMITTED);
        // Bare, the clock is RECOGNIZED as a clock, so the date-kind gate answers about its Number partner.
        expected.put("bare Today", "MVK_INVALID_COMPARE_TO_DATE");
        // Grouped, that recognition is gone while constantness survives, so the constant-pair gate answers
        // first — the entry's own "braces preserve constantness, remove bare-clock recognition".
        expected.put("singly grouped Today", "MVK_INVALID_COMPARE_TO_CONST");
        expected.put("doubly grouped Today", "MVK_INVALID_COMPARE_TO_CONST");
        expected.put("bare Now", "MVK_INVALID_COMPARE_TO_DATE");
        expected.put("singly grouped Now", "MVK_INVALID_COMPARE_TO_CONST");
        expected.put("doubly grouped Now", "MVK_INVALID_COMPARE_TO_CONST");
        assertEquals(expected, actual, "a group moves which gate refuses, and never admits, on this partner");
    }

    /**
     * Against ANOTHER CLOCK the same group ADMITS, and a second group admits too — the opposite direction
     * from the grid above, on one model, which is what makes the partner the variable rather than the clock.
     */
    @Test
    void aGroupAroundAClockComparedToAnotherClockAdmitsAtEveryDepth() {
        Map<String, String> actual = new LinkedHashMap<>();
        actual.put("bare on both sides", verdict(Dsl.today().eq(Dsl.today())));
        actual.put("singly grouped on the left", verdict("{Today} == Today"));
        actual.put("doubly grouped on the left", verdict("{{Today}} == Today"));
        actual.put("a date field against a clock — the liveness control", verdict(PLACED_ON.eq(Dsl.today())));

        Map<String, String> expected = new LinkedHashMap<>();
        // Two bare clocks are two constants, and the constant gate is asked first.
        expected.put("bare on both sides", "MVK_INVALID_COMPARE_TO_CONST");
        expected.put("singly grouped on the left", KernelLaws.ADMITTED);
        expected.put("doubly grouped on the left", KernelLaws.ADMITTED);
        expected.put("a date field against a clock — the liveness control", KernelLaws.ADMITTED);
        assertEquals(expected, actual, "on a clock partner a group admits, at either depth");
    }

    /** Swapping the clock and the constant changes which gate answers, and the group settles it either way. */
    @Test
    void theOperandOrderDecidesWhichGateAnswers() {
        Map<String, String> actual = new LinkedHashMap<>();
        actual.put("clock on the left", verdict("Today < 1"));
        actual.put("clock on the right", verdict("1 < Today"));
        actual.put("clock on the left, grouped", verdict("{Today} < 1"));
        actual.put("clock on the right, grouped", verdict("1 < {Today}"));
        actual.put("a date field against a number constant", verdict("[PlacedOn] < 1"));

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("clock on the left", "MVK_INVALID_COMPARE_TO_DATE");
        // The date-kind gate reads the LEFT result, so a clock on the right leaves two constants and the
        // constant gate answers instead — the entry's "swapping can change the refusal class".
        expected.put("clock on the right", "MVK_INVALID_COMPARE_TO_CONST");
        expected.put("clock on the left, grouped", "MVK_INVALID_COMPARE_TO_CONST");
        expected.put("clock on the right, grouped", "MVK_INVALID_COMPARE_TO_CONST");
        // …and a date FIELD on the left reaches the same date-kind gate a bare clock does, so that gate is
        // about the left operand's kind rather than about clock-ness.
        expected.put("a date field against a number constant", "MVK_INVALID_COMPARE_TO_DATE");
        assertEquals(expected, actual, "the operand order decides which gate answers");
    }
}
