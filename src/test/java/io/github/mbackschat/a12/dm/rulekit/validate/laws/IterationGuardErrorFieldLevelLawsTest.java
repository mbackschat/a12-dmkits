package io.github.mbackschat.a12.dm.rulekit.validate.laws;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft;
import io.github.mbackschat.a12.dm.modelkit.FieldKind;

/**
 * KERNEL-FINDINGS §9 — the axis {@code IterationGuardLevelLawsTest} holds CONSTANT.
 *
 * <p>That file varies the guard's level and reads the result as "a guard must reference the negative's own
 * level". Every one of its rows also pins the ERROR FIELD to the negative's level, so "the guard must
 * reference the NEGATIVE's level" and "the guard must reference the level the ERROR FIELD selects" fit all of
 * them equally. Its own javadoc warns about exactly this shape one axis earlier and then repeats it here.
 *
 * <p>Separating them needs a model the sibling fixture cannot express: two groups at one depth leave nowhere
 * to put an error field that is not the negative's own level. These groups NEST, so guard level and error-field
 * level move independently — the 2x2 below, asserted as one table so a kernel shift shows every cell rather
 * than aborting at the first.
 */
class IterationGuardErrorFieldLevelLawsTest {

    private static final String NEG = "MVK_NEG_CONDITION_IN_ITERATION";
    private static final String OUT_OF_GROUP = "MVK_ERROR_FIELD_NOT_IN_RULEGROUP";

    /** {@code Lines} repeats, and {@code Parts} repeats inside it — two ordinary iteration levels. */
    private static final String MODEL = DocumentModelDraft.named("iteration-guard-error-levels")
            .locale("en_US")
            .rootGroup("Order", o -> o
                    .field("Reference", FieldKind.STRING)
                    .group("Lines", l -> l.repeatable(20)
                            .field("Sku", FieldKind.STRING)
                            .field("Count", FieldKind.NUMBER)
                            .group("Parts", p -> p.repeatable(20)
                                    .field("Code", FieldKind.STRING)
                                    .field("Qty", FieldKind.NUMBER))))
            .build().toJson();

    private static final String INNER = "/Order/Lines/Parts";

    /** An error path resolves from the RULE node, which sits inside its group — hence one extra hop. */
    private static final String INNER_ERROR = "../Code";
    private static final String OUTER_ERROR = "../../Sku";

    private static final String OUTER_GUARD = "FieldFilled(/Order/Lines/Count) And FieldNotFilled(Code)";

    /** The negative always sits on the INNER level; only the guard and the error field move. */
    private static String verdict(String errorField, String condition) {
        var result = KernelLaws.checkModel(MODEL, INNER, errorField, condition);
        if (result.ok()) {
            return KernelLaws.ADMITTED;
        }
        if (result.diagnostics().stream().anyMatch(d -> d.code().filter(NEG::equals).isPresent())) {
            return NEG;
        }
        return result.diagnostics().stream()
                .map(d -> d.code().orElse("NO_CODE"))
                .distinct()
                .sorted()
                .collect(java.util.stream.Collectors.joining("+"));
    }

    /**
     * The grid. Rows are the guard's level, columns the error field's; the negative is {@code Code} on the
     * inner level throughout, so exactly one variable moves per cell.
     *
     * <p><b>The error-field axis turns out not to be free.</b> An error field outside the rule group is refused
     * outright — {@code MVK_ERROR_FIELD_NOT_IN_RULEGROUP} — and that gate PRE-EMPTS the negative-condition one:
     * no such row reports {@code MVK_NEG_CONDITION_IN_ITERATION} at all. {@link #verdict} returns that code
     * preferentially whenever it is present, so a row naming any other code also asserts it is absent.
     *
     * <p><b>Not conditioned on iteration</b>, although the kernel's message says "but the condition specifies
     * iteration": the last row takes a condition that iterates at no level and is refused just the same. The
     * message's clause is not the operative rule, which is why it is measured here rather than quoted.
     *
     * <p>So the rival reading of the sibling file — that the error field selects the level the guard must
     * reference — is not refuted but <b>unfalsifiable on any kernel-legal model</b>: the error field must sit in
     * the rule group, so its level is the rule group's and never varies against it. Within the legal column the
     * guard's level does decide, which is the sibling file's claim standing on a grid rather than one column.
     *
     * <p><b>The rule GROUP is constant here</b> (always the inner level), and that is the axis this file in turn
     * does not vary. Varying it needs a negative over a deeper repeatable level from an outer rule group, which
     * wants a wildcard the builder refuses ({@code MVK_NO_WILDCARDS_ALLOWED}) — so the discriminating model may
     * be unrepresentable rather than merely unmeasured. Stated because an unnamed constant reads as an
     * oversight.
     */
    @Test
    void anOutOfGroupErrorFieldPreemptsTheIterationGateWhichTheGuardLevelOtherwiseDecides() {
        var actual = new LinkedHashMap<String, String>();

        // Guard on the INNER level — the same level as the negative.
        actual.put("inner guard, inner error field",
                verdict(INNER_ERROR, "FieldFilled(Qty) And FieldNotFilled(Code)"));
        actual.put("inner guard, outer error field",
                verdict(OUTER_ERROR, "FieldFilled(Qty) And FieldNotFilled(Code)"));

        // Guard on the OUTER level — a level the negative does not reference.
        actual.put("outer guard, inner error field",
                verdict(INNER_ERROR, OUTER_GUARD));
        actual.put("outer guard, outer error field",
                verdict(OUTER_ERROR, OUTER_GUARD));

        // The unguarded baseline on both error-field levels, so a refusal above is not merely the negative's
        // own — and so the error field alone is shown never to rescue it.
        actual.put("unguarded, inner error field", verdict(INNER_ERROR, "FieldNotFilled(Code)"));
        actual.put("unguarded, outer error field", verdict(OUTER_ERROR, "FieldNotFilled(Code)"));

        // THE PRE-EMPTION DISCRIMINATOR. Without it, "an out-of-group error field is ALWAYS refused" and
        // "...is refused once the condition ITERATES" fit every row above equally, and the second is only what
        // the kernel's message says. A condition over a non-repeating field iterates at no level.
        // It must REFERENCE the outer error field (or the not-referenced code masks the cell) while iterating
        // at no level: an ancestor field is singular per row, and there is no negation to bind.
        actual.put("non-iterating condition, outer error field",
                verdict(OUTER_ERROR, "FieldFilled(/Order/Lines/Sku)"));

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("inner guard, inner error field", KernelLaws.ADMITTED);
        expected.put("inner guard, outer error field", OUT_OF_GROUP);
        expected.put("outer guard, inner error field", NEG);
        expected.put("outer guard, outer error field", OUT_OF_GROUP);
        expected.put("unguarded, inner error field", NEG);
        expected.put("unguarded, outer error field", OUT_OF_GROUP);
        expected.put("non-iterating condition, outer error field", OUT_OF_GROUP);

        assertEquals(expected, actual);
    }
}
