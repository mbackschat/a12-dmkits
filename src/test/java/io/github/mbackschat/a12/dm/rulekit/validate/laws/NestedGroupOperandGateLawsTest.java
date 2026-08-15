package io.github.mbackschat.a12.dm.rulekit.validate.laws;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft;
import io.github.mbackschat.a12.dm.modelkit.FieldKind;
import io.github.mbackschat.a12.dm.rulekit.validate.ValidationResult;

/**
 * KERNEL-FINDINGS §9 — <b>the wildcard gate and the iteration gate, varied together</b> on a NESTED repeatable.
 *
 * <p>Each gate is already locked on its own, on fixtures that hold the other one still:
 *
 * <ul>
 *   <li>the <b>wildcard</b> gate reads the <em>authored</em> slot, so a group operand is checked at its own
 *       level only while its written-out expansion is checked at every level (KF188);</li>
 *   <li>the <b>iteration</b> gate admits a bare repeatable operand only where the error field sits inside it,
 *       and then applies a second, syntactic refusal
 *       ({@link UnstarredRepeatableGroupIterationLawsTest}).</li>
 * </ul>
 *
 * <p>Both were measured on a <b>single-level</b> repeatable, where "the operand's own level" and "the level the
 * error field makes iterable" are the same level and cannot disagree. A nested repeatable separates them: the
 * star on {@code Sections} and the star on {@code Answers} are independent slots, and the error field can sit
 * inside the inner group, inside the outer one, or outside both. This file is that cross-product.
 *
 * <p><b>The composed law the 24 cells state, which neither gate predicts alone.</b> The unstarred levels of an
 * authored chain must form a <b>PREFIX</b> of it, and the error field must sit inside the innermost unstarred
 * level:
 *
 * <ul>
 *   <li>{@code Sections* / Answers} — a star ABOVE an unstarred repeatable — is refused {@code MVK_NO_WILDCARD} in
 *       <b>all six</b> cells, whatever the polarity and wherever the error field sits. <b>A star does not cover
 *       the levels below it</b>, and there is no error-field placement that rescues the spelling. This is the
 *       row a consumer is most likely to get wrong, because each gate on its own would admit it.</li>
 *   <li>{@code Sections / Answers} (no stars) iterates BOTH levels, so it needs the error field inside the inner
 *       group; {@code Sections / Answers*} iterates only {@code Sections}, so the error field may sit anywhere
 *       inside {@code Sections} — in {@code Title} or deeper in {@code Answers}. Both refuse an error field
 *       outside.</li>
 *   <li>{@code Sections* / Answers*} has no unstarred level, hence no iteration, hence no locus requirement and
 *       no polarity gate: admitted in all six cells.</li>
 *   <li>The polarity refusal tracks the <em>iterated</em> level exactly. {@code Sections / Answers*} with the
 *       error field in {@code Title} takes {@code NotAllFieldsFilled} happily — the starred inner set is folded,
 *       not iterated — while the same spelling with the error field inside {@code Answers} draws
 *       {@code MVK_NEG_CONDITION_IN_ITERATION}.</li>
 * </ul>
 */
class NestedGroupOperandGateLawsTest {

    /** A repeatable inside a repeatable, plus a field outside both, so all three error-field loci exist. */
    private static final String MODEL = DocumentModelDraft.named("survey").locale("en_US")
            .rootGroup("Survey", s -> s
                    .field("Note", FieldKind.STRING)
                    .group("Sections", sec -> sec.repeatable(20)
                            .field("Title", FieldKind.STRING)
                            .group("Answers", a -> a.repeatable(10)
                                    .field("Text", FieldKind.STRING))))
            .build().toJson();

    private static final String INSIDE_INNER = "../Sections/Answers/Text";
    private static final String INSIDE_OUTER = "../Sections/Title";
    private static final String OUTSIDE = "../Note";

    @Test
    void theTwoStarsAreIndependentSlotsAndTheErrorFieldLocusGatesTheInnerOne() {
        assertEquals(expected(), actual(),
                "the wildcard gate (authored slot, per level) composed with the iteration gate (error-field "
                        + "locus) on a nested repeatable");
    }

    private static Map<String, String> actual() {
        Map<String, String> rows = new LinkedHashMap<>();
        for (String quantifier : new String[] { "AllFieldsFilled", "NotAllFieldsFilled" }) {
            for (String operand : new String[] {
                    "Sections/Answers", "Sections*/Answers", "Sections/Answers*", "Sections*/Answers*" }) {
                String body = quantifier + "(" + operand + ")";
                rows.put(operand + " | " + quantifier + " | error field inside the INNER group",
                        verdict(INSIDE_INNER, body));
                rows.put(operand + " | " + quantifier + " | error field inside the OUTER group",
                        verdict(INSIDE_OUTER, "FieldFilled(Sections/Title) And " + body));
                rows.put(operand + " | " + quantifier + " | error field OUTSIDE both",
                        verdict(OUTSIDE, "FieldFilled(Note) And " + body));
            }
        }
        return rows;
    }

    private static Map<String, String> expected() {
        Map<String, String> rows = new LinkedHashMap<>();
        String bare = "MVK_NO_WILDCARD";
        String iter = "MVK_NEG_CONDITION_IN_ITERATION";
        //                                            inner        outer      outside
        put(rows, "Sections/Answers", "AllFieldsFilled", "admitted", bare, bare);
        put(rows, "Sections*/Answers", "AllFieldsFilled", bare, bare, bare);
        put(rows, "Sections/Answers*", "AllFieldsFilled", "admitted", "admitted", bare);
        put(rows, "Sections*/Answers*", "AllFieldsFilled", "admitted", "admitted", "admitted");
        put(rows, "Sections/Answers", "NotAllFieldsFilled", iter, bare, bare);
        put(rows, "Sections*/Answers", "NotAllFieldsFilled", bare, bare, bare);
        put(rows, "Sections/Answers*", "NotAllFieldsFilled", iter, "admitted", bare);
        put(rows, "Sections*/Answers*", "NotAllFieldsFilled", "admitted", "admitted", "admitted");
        return rows;
    }

    private static void put(Map<String, String> rows, String operand, String quantifier,
            String inner, String outer, String outside) {
        rows.put(operand + " | " + quantifier + " | error field inside the INNER group", inner);
        rows.put(operand + " | " + quantifier + " | error field inside the OUTER group", outer);
        rows.put(operand + " | " + quantifier + " | error field OUTSIDE both", outside);
    }

    /**
     * The {@code Having} axis, which the two gates above never varied — and it does not compose at all.
     *
     * <p>Attaching a filter to a <b>group</b> operand is refused outright with {@code MVK_NO_GROUPS_ALLOWED},
     * in every cell: both spellings, both polarities, both error-field loci. The unfiltered controls are what
     * make that attributable — the identical operand without its filter is admitted wherever the gates above
     * say it should be, so the refusal is the filter's and not the group's.
     *
     * <p>The consequence for the two gates is that they have <b>no filtered domain to compose over</b> on this
     * carrier: a filter belongs to a field operand ({@code Sum(Lines* / Amount Having …)}), and the question
     * "does a filter move the wildcard or iteration gate" is not merely unanswered but unaskable here.
     */
    @Test
    void aHavingFilterCannotRideAGroupOperandAtAll() {
        String filter = " Having [Sections/Title] == \"x\"";
        Map<String, String> actual = new LinkedHashMap<>();
        for (String quantifier : new String[] { "AllFieldsFilled", "NotAllFieldsFilled" }) {
            for (String operand : new String[] { "Sections", "Sections*" }) {
                actual.put(operand + " + Having | " + quantifier + " | error field inside",
                        verdict(INSIDE_OUTER, quantifier + "(" + operand + filter + ")"));
                actual.put(operand + " UNFILTERED control | " + quantifier + " | error field inside",
                        verdict(INSIDE_OUTER, quantifier + "(" + operand + ")"));
            }
        }

        Map<String, String> expected = new LinkedHashMap<>();
        String groups = "MVK_NO_GROUPS_ALLOWED";
        expected.put("Sections + Having | AllFieldsFilled | error field inside", groups);
        expected.put("Sections UNFILTERED control | AllFieldsFilled | error field inside", "admitted");
        expected.put("Sections* + Having | AllFieldsFilled | error field inside", groups);
        expected.put("Sections* UNFILTERED control | AllFieldsFilled | error field inside", "admitted");
        expected.put("Sections + Having | NotAllFieldsFilled | error field inside", groups);
        expected.put("Sections UNFILTERED control | NotAllFieldsFilled | error field inside",
                "MVK_NEG_CONDITION_IN_ITERATION");
        expected.put("Sections* + Having | NotAllFieldsFilled | error field inside", groups);
        expected.put("Sections* UNFILTERED control | NotAllFieldsFilled | error field inside", "admitted");
        assertEquals(expected, actual,
                "a Having filter is refused on a group operand, while the same operand unfiltered is admitted");
    }

    /**
     * The <b>authored-path</b> wildcard gate (KF188), re-locked here because its original lock was deleted.
     *
     * <p>These three rows were measured on this exact fixture shape and handed to a12-kernel-lean, whose
     * `spec/07` clause rests on them — but `adapter.MixedScopeStarPreservationTest` was removed by the refactor
     * that merged our two entity-list records, so the rows had no live lock and the peer correctly marked the
     * clause unconfirmed. The neighbouring cases in this file corroborate the phenomenon; they do not assert
     * these rows, so this method does.
     *
     * <p>The law: a <b>group</b> operand is wildcard-checked at its own level only, while its <b>written-out
     * expansion</b> is checked at every level it names. Row 3 is the control that attributes row 2's refusal to
     * the missing inner star rather than to the explicit spelling — without it, row 2 is equally consistent with
     * "the kernel dislikes this operand list for some other reason".
     */
    @Test
    void aGroupOperandAndItsWrittenOutExpansionAreNotInterchangeableSpellings() {
        Map<String, String> actual = new LinkedHashMap<>();
        actual.put("the GROUP operand, starred at its own level", verdict(OUTSIDE,
                "FieldsNotCollectivelyFilled(Sections*, Note)"));
        actual.put("the same expansion written out, inner level unstarred", verdict(OUTSIDE,
                "FieldsNotCollectivelyFilled(Sections*/Title, Sections*/Answers/Text, Note)"));
        actual.put("the same expansion, both levels starred — the control", verdict(OUTSIDE,
                "FieldsNotCollectivelyFilled(Sections*/Title, Sections*/Answers*/Text, Note)"));

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("the GROUP operand, starred at its own level", "admitted");
        expected.put("the same expansion written out, inner level unstarred", "MVK_NO_WILDCARD");
        expected.put("the same expansion, both levels starred — the control", "admitted");
        assertEquals(expected, actual,
                "what the kernel waves through as a group it refuses as a written-out path with the inner "
                        + "level unstarred");
    }

    /** {@code admitted}, or the COMPLETE emitted code list — a partial list would hide a co-firing gate. */
    private static String verdict(String errorField, String condition) {
        ValidationResult r = KernelLaws.checkModel(MODEL, "/Survey", errorField, condition);
        return r.ok() ? "admitted" : String.join(" + ", r.diagnostics().stream()
                .map(d -> d.code().orElse("(no code)")).toList());
    }
}
