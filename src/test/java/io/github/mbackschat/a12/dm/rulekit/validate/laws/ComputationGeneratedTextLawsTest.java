package io.github.mbackschat.a12.dm.rulekit.validate.laws;

import static io.github.mbackschat.a12.dm.rulekit.dsl.Dsl.de;
import static io.github.mbackschat.a12.dm.rulekit.dsl.Dsl.en;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.Fixtures;
import io.github.mbackschat.a12.dm.rulekit.validate.DraftComputation;
import io.github.mbackschat.a12.dm.rulekit.validate.DraftComputation.Alternative;

/**
 * KERNEL-FINDINGS KF471 — A12 parses a computation's members inside the ONE condition it generates from them,
 * so a member that runs out of text meets the {@code )} A12 added after it.
 *
 * <p>{@code ComputationAlternativesJoiner} writes {@code FieldFilled(<target>) And (<common>\n) And
 * (<precondition>\n) And [<target>] != <operation>} for one alternative and wraps each of two or more as
 * {@code (<precondition>\n) And [<target>] != <operation>\n)}, closing the list with a final {@code \n)}. Only a
 * lone alternative's operation ends that text. The rival reading, that each member is parsed as written, is
 * what {@link ComputationAlternativeSlotLawsTest} could not separate: it measured a blank operation on ONE
 * alternative only. Every refused row here is one the rival gets wrong, and the controls are the shapes both
 * readings agree on.
 *
 * <p>The codeless line is A12's own {@code problem.document.basic.computationOperationEmpty}, raised by
 * {@code BasicConsistencyCheck.visitComputation} for an EMPTY or {@code null} operation and not for a
 * whitespace one ({@code StringUtils.isEmpty}). It carries no class, so a checker can only stay silent about
 * it.
 *
 * <p>Own domain (the shared subscription fixture: a scale-2 {@code EffectiveFee} under
 * {@code /Subscription/Billing}).
 */
class ComputationGeneratedTextLawsTest {

    private static final String GUARD = "FieldFilled(../Tier)";

    private static final String WORK = "42.00";

    private static final Alternative GUARDED_WORK = new Alternative(GUARD, WORK);

    private static final String EMPTY_OPERATION = "(no code: Computation with id '/Subscription/Billing/"
            + "AlternativeProbe': One of the computation operations is empty.)";

    /** The two unguarded rows also show the calculation module's guard class withheld behind the token class. */
    @Test
    void aBlankOperationIsTheIncompletenessClassOnlyWhileItIsTheOnlyAlternative() {
        Map<String, String> actual = new LinkedHashMap<>();
        actual.put("two guarded — the control", verdict(null, GUARDED_WORK, GUARDED_WORK));
        actual.put("one guarded, blank", verdict(null, guarded("")));
        actual.put("one unguarded, blank", verdict(null, new Alternative(null, "")));
        actual.put("two guarded, the second blank", verdict(null, GUARDED_WORK, guarded("")));
        actual.put("two guarded, the second whitespace", verdict(null, GUARDED_WORK, guarded("   ")));
        actual.put("two guarded, the second null", verdict(null, GUARDED_WORK, guarded(null)));
        actual.put("two guarded, the FIRST blank", verdict(null, guarded(""), GUARDED_WORK));
        actual.put("three guarded, the MIDDLE blank", verdict(null, GUARDED_WORK, guarded(""), GUARDED_WORK));
        actual.put("two, the blank one unguarded", verdict(null, GUARDED_WORK, new Alternative(null, "")));
        actual.put("two, the blank one unguarded and whitespace",
                verdict(null, GUARDED_WORK, new Alternative(null, "   ")));
        actual.put("two, the OTHER one unguarded", verdict(null, new Alternative(null, WORK), guarded("")));

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("two guarded — the control", KernelLaws.ADMITTED);
        // The generated text ends on the comparison operator, which is the incompleteness class's shape.
        expected.put("one guarded, blank", "MVK_INCOMPLETE_INPUT," + EMPTY_OPERATION);
        expected.put("one unguarded, blank", "MVK_INCOMPLETE_INPUT," + EMPTY_OPERATION);
        // From two on the operator is followed by the alternative's closing parenthesis, wherever it stands.
        expected.put("two guarded, the second blank", "MVK_UNEXPECTED_TOKEN," + EMPTY_OPERATION);
        expected.put("two guarded, the second whitespace", "MVK_UNEXPECTED_TOKEN");
        expected.put("two guarded, the second null", "MVK_UNEXPECTED_TOKEN," + EMPTY_OPERATION);
        expected.put("two guarded, the FIRST blank", "MVK_UNEXPECTED_TOKEN," + EMPTY_OPERATION);
        expected.put("three guarded, the MIDDLE blank", "MVK_UNEXPECTED_TOKEN," + EMPTY_OPERATION);
        // The calculation module never runs on a text that does not parse, so its guard class is not raised.
        expected.put("two, the blank one unguarded", "MVK_UNEXPECTED_TOKEN," + EMPTY_OPERATION);
        expected.put("two, the blank one unguarded and whitespace", "MVK_UNEXPECTED_TOKEN");
        expected.put("two, the OTHER one unguarded", "MVK_UNEXPECTED_TOKEN," + EMPTY_OPERATION);
        assertEquals(expected, actual, "what a blank operation draws beside its siblings");
    }

    /**
     * The same holds for text that is not blank: a member that stops short draws the token class wherever A12
     * reads on after it, and the text's own class only where it ends A12's input.
     *
     * <p>The one shape that keeps its class is a text stopped inside a parenthesis that is ready to close: the
     * added {@code )} closes it and A12 reads on, running out later with its own group still open.
     */
    @Test
    void aMemberThatStopsShortMeetsTheParenthesisAddedAfterIt() {
        Map<String, String> actual = new LinkedHashMap<>();
        actual.put("a lone operation ending on `+` — the control", verdict(null, guarded("42.00 +")));
        actual.put("a lone operation with `{` open — the control", verdict(null, guarded("{42.00")));
        actual.put("an operation ending on `+` beside a sibling", verdict(null, GUARDED_WORK, guarded("42.00 +")));
        actual.put("an operation with `{` open beside a sibling", verdict(null, GUARDED_WORK, guarded("{42.00")));
        actual.put("an operation with `[` open beside a sibling", verdict(null, GUARDED_WORK, guarded("[../Tier")));
        actual.put("a precondition ending on `And`", verdict(null, new Alternative(GUARD + " And", WORK)));
        actual.put("a precondition ending on `Or`", verdict(null, new Alternative(GUARD + " Or", WORK)));
        actual.put("a precondition ending on a comma", verdict(null, new Alternative("FieldFilled(../Tier,", WORK)));
        actual.put("a precondition with `[` open", verdict(null, new Alternative("FieldFilled([../Tier", WORK)));
        actual.put("a precondition with `(` open", verdict(null, new Alternative("FieldFilled(../Tier", WORK)));
        actual.put("a second alternative's precondition ending on `And`",
                verdict(null, GUARDED_WORK, new Alternative(GUARD + " And", WORK)));
        actual.put("a common precondition ending on `And`", verdict(GUARD + " And", new Alternative(null, WORK)));
        actual.put("a common precondition with `[` open",
                verdict("FieldFilled([../Tier", new Alternative(null, WORK)));

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("a lone operation ending on `+` — the control", "MVK_INCOMPLETE_INPUT");
        expected.put("a lone operation with `{` open — the control", "MVK_EXPECTED_TOKEN_NOT_FOUND");
        expected.put("an operation ending on `+` beside a sibling", "MVK_UNEXPECTED_TOKEN");
        expected.put("an operation with `{` open beside a sibling", "MVK_UNEXPECTED_TOKEN");
        expected.put("an operation with `[` open beside a sibling", "MVK_UNEXPECTED_TOKEN");
        expected.put("a precondition ending on `And`", "MVK_UNEXPECTED_TOKEN");
        expected.put("a precondition ending on `Or`", "MVK_UNEXPECTED_TOKEN");
        expected.put("a precondition ending on a comma", "MVK_UNEXPECTED_TOKEN");
        expected.put("a precondition with `[` open", "MVK_UNEXPECTED_TOKEN");
        // The added `)` closes the call, A12 reads on, and the precondition's own group is left open.
        expected.put("a precondition with `(` open", "MVK_EXPECTED_TOKEN_NOT_FOUND");
        expected.put("a second alternative's precondition ending on `And`", "MVK_UNEXPECTED_TOKEN");
        expected.put("a common precondition ending on `And`", "MVK_UNEXPECTED_TOKEN");
        expected.put("a common precondition with `[` open", "MVK_UNEXPECTED_TOKEN");
        assertEquals(expected, actual, "what a member that stops short draws where A12 reads on after it");
    }

    private static Alternative guarded(String operation) {
        return new Alternative(GUARD, operation);
    }

    private static String verdict(String commonPrecondition, Alternative... alternatives) {
        DraftComputation.Builder draft = DraftComputation.builder()
                .groupPath("/Subscription/Billing").name("AlternativeProbe").computedFieldRelPath("../EffectiveFee")
                .alternatives(List.of(alternatives))
                .messages(List.of(en("x"), de("y")));
        if (commonPrecondition != null) {
            draft.commonPrecondition(commonPrecondition);
        }
        return KernelLaws.computationVerdict(Fixtures.subscriptionJson(), draft.build());
    }
}
