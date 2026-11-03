package io.github.mbackschat.a12.dm.rulekit.validate.laws;

import static io.github.mbackschat.a12.dm.rulekit.dsl.Dsl.de;
import static io.github.mbackschat.a12.dm.rulekit.dsl.Dsl.en;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.Fixtures;
import io.github.mbackschat.a12.dm.rulekit.write.ModelWriter;

/**
 * How many parts a condition may join with {@code And}/{@code Or} at one bracket level, and whether the two
 * may be mixed without parentheses.
 *
 * <p><b>Why this is measured before a parser is written, and not after.</b> The condition grammar's
 * {@code bedingung} production reads as though it allows at most THREE parts per bracket level and forbids
 * mixing {@code And} with {@code Or} unparenthesised. That reading came from the production's shape, not from
 * A12 — and a parser written from a shape inventory would accept whatever the inventory suggested. Both
 * directions are expensive: accepting more than A12 makes the checker miss a real defect, while accepting
 * LESS makes it reject models the Kernel admits, which is the one direction it must never take.
 *
 * <p>This file is deliberately separate from {@code RuleConditionSyntaxLawsTest}, which measures text that
 * cannot be parsed at all. Everything here is lexically clean and well-delimited; the only question is how the
 * parts COMPOSE, which is the first question the grammatical increment has to answer.
 *
 * <p>Own domain (the shared subscription fixture). Every row's first conjunct references {@code Tier}, the
 * probe rule's error field, so no row can be rejected for failing to reference its own target.
 */
class RuleConditionCompositionLawsTest {

    private static final String A = "FieldFilled(Tier)";
    private static final String B = "FieldFilled(AutoRenew)";
    private static final String C = "FieldFilled(TermsAccepted)";
    private static final String D = "FieldNotFilled(Tier)";

    /** How many parts one bracket level takes, with a single part as the liveness control. */
    @Test
    void howManyPartsOneBracketLevelJoins() {
        Map<String, String> actual = new LinkedHashMap<>();
        actual.put("one part — the liveness control", verdict(A));
        actual.put("two parts joined by And", verdict(A + " And " + B));
        actual.put("three parts joined by And", verdict(A + " And " + B + " And " + C));
        actual.put("four parts joined by And", verdict(A + " And " + B + " And " + C + " And " + D));
        actual.put("two parts joined by Or", verdict(A + " Or " + B));
        actual.put("three parts joined by Or", verdict(A + " Or " + B + " Or " + C));
        actual.put("four parts joined by Or", verdict(A + " Or " + B + " Or " + C + " Or " + D));
        // If a level really is capped, nesting is the escape hatch a checker must not reject.
        actual.put("four parts split across two bracket levels",
                verdict("(" + A + " And " + B + ") And (" + C + " And " + D + ")"));

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("one part — the liveness control", KernelLaws.ADMITTED);
        expected.put("two parts joined by And", KernelLaws.ADMITTED);
        expected.put("three parts joined by And", KernelLaws.ADMITTED);
        expected.put("four parts joined by And", KernelLaws.ADMITTED);
        expected.put("two parts joined by Or", KernelLaws.ADMITTED);
        expected.put("three parts joined by Or", KernelLaws.ADMITTED);
        expected.put("four parts joined by Or", KernelLaws.ADMITTED);
        expected.put("four parts split across two bracket levels", KernelLaws.ADMITTED);
        assertEquals(expected, actual, "how many parts one bracket level joins");
    }

    /**
     * Mixing {@code And} and {@code Or} at ONE bracket level is refused, and it has its own code.
     *
     * <p>A12 answers an unparenthesised mix with {@code MVK_BRACKET_MISSING} — it refuses to guess a
     * precedence rather than applying one silently, which is the better half of the two possible answers: had
     * it ACCEPTED the mix it would also have had a precedence, and a checker-side parser that guessed that
     * precedence would build a different tree from the Kernel's for text both accept, a divergence no
     * accept/reject test can see.
     *
     * <p><b>The rows are chosen so that "mixed somewhere in the text" and "mixed at ONE LEVEL" come apart</b>,
     * because that is the distinction a checker has to implement and the two are identical on the first three
     * rows alone. Every parenthesised row below mixes both connectives in the text and is ADMITTED; every
     * refused row has both at the same level. A check that looked only for both spellings anywhere would
     * reject five conditions A12 accepts.
     */
    @Test
    void whetherAndAndOrMayBeMixedWithoutParentheses() {
        Map<String, String> actual = new LinkedHashMap<>();
        actual.put("And then Or, unparenthesised", verdict(A + " And " + B + " Or " + C));
        actual.put("Or then And, unparenthesised", verdict(A + " Or " + B + " And " + C));
        actual.put("a longer unparenthesised mix", verdict(A + " And " + B + " Or " + C + " And " + D));
        // Each of these carries BOTH connectives and separates them by a bracket level.
        actual.put("grouped left — both spellings, different levels",
                verdict("(" + A + " And " + B + ") Or " + C));
        actual.put("grouped right — both spellings, different levels",
                verdict(A + " And (" + B + " Or " + C + ")"));
        actual.put("both sides grouped", verdict("(" + A + " And " + B + ") Or (" + C + " And " + D + ")"));
        actual.put("a grouped Or in the middle of an And chain",
                verdict(A + " And (" + B + " Or " + C + ") And " + D));
        actual.put("nested two levels deep",
                verdict(A + " Or (" + B + " And (" + C + " Or " + D + "))"));

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("And then Or, unparenthesised", "MVK_BRACKET_MISSING");
        expected.put("Or then And, unparenthesised", "MVK_BRACKET_MISSING");
        expected.put("a longer unparenthesised mix", "MVK_BRACKET_MISSING");
        expected.put("grouped left — both spellings, different levels", KernelLaws.ADMITTED);
        expected.put("grouped right — both spellings, different levels", KernelLaws.ADMITTED);
        expected.put("both sides grouped", KernelLaws.ADMITTED);
        expected.put("a grouped Or in the middle of an And chain", KernelLaws.ADMITTED);
        expected.put("nested two levels deep", KernelLaws.ADMITTED);
        assertEquals(expected, actual, "whether And and Or mix without parentheses");
    }

    /**
     * The level a CALL's parentheses open, which decides whether the rule above is implementable by tokens.
     *
     * <p>A checker can only apply "both connectives at one level" if it knows what a level IS. An argument
     * list is parenthesised too, and a filter's argument can itself contain a condition with its own
     * connectives — so if a call's parentheses did NOT open a level, an {@code And} inside a filter and an
     * {@code Or} outside it would look like a same-level mix and the checker would reject text A12 accepts.
     */
    @Test
    void aCallsParenthesesOpenTheirOwnLevel() {
        String filtered = "NumberOfFilledFields(Addons*/Name Having [Addons/Name] == \"x\" "
                + "And [Addons/MonthlyFee] > 1) > 0";
        Map<String, String> actual = new LinkedHashMap<>();
        actual.put("a filter carrying And, with no connective outside — the control", verdict(filtered));
        actual.put("…and an Or outside it", verdict(filtered + " Or " + A));
        actual.put("…and an And outside it", verdict(filtered + " And " + A));

        Map<String, String> expected = new LinkedHashMap<>();
        // A SEMANTIC code is this control passing: the filtered expression got through the grammar and was
        // rejected on meaning (it does not reference the probe rule's own error field, which the two rows
        // below supply). What it has to show is that the filter parses, and a parse code here would say it
        // does not — so the other two rows would be measuring nothing.
        expected.put("a filter carrying And, with no connective outside — the control",
                "MVK_ERROR_FIELD_NOT_REFERENCED");
        // The row that matters: And is inside the call, Or is outside, and A12 accepts — so the call's
        // parentheses open a level exactly as a grouping pair does.
        expected.put("…and an Or outside it", KernelLaws.ADMITTED);
        expected.put("…and an And outside it", KernelLaws.ADMITTED);
        assertEquals(expected, actual, "whether a call's parentheses open their own connective level");
    }

    /**
     * Whether two calls at the same nesting DEPTH share a level, which is the difference between keying a
     * checker on a depth counter and keying it on the bracket PAIR.
     *
     * <p>A depth counter is the obvious implementation and it is wrong here: both filters below sit one
     * parenthesis deep, one carries {@code And} and the other {@code Or}, and a checker that recorded "depth 1
     * has seen both spellings" would reject a condition A12 admits. They are different parenthesis pairs, so
     * they are different levels — a level is a FRAME, not a number. Over-rejection is the one direction a
     * checker must never take, which is why this row is measured rather than reasoned.
     */
    @Test
    void whetherTwoSiblingCallsAtTheSameDepthShareALevel() {
        String carryingAnd = "NumberOfFilledFields(Addons*/Name Having [Addons/Name] == \"x\" "
                + "And [Addons/MonthlyFee] > 1) > 0";
        String carryingOr = "NumberOfFilledFields(Addons*/Name Having [Addons/Name] == \"y\" "
                + "Or [Addons/MonthlyFee] > 2) > 0";
        Map<String, String> actual = new LinkedHashMap<>();
        actual.put("two sibling filters, one And and one Or, joined by Or",
                verdict(carryingAnd + " Or " + carryingOr + " Or " + A));
        // The control: the same two filters with the OUTER connectives mixed instead. If this were admitted
        // too, the row above would prove nothing — it would only show that A12 stopped checking.
        actual.put("…and the same pair with the outer level itself mixed",
                verdict(carryingAnd + " Or " + carryingOr + " And " + A));

        // The same claim with a COMPARISON between the two calls rather than a connective, so the pair is
        // joined by something that opens no connective level of its own.
        //
        // Two filters inside ONE argument list would be the finer case — they would share a parenthesis pair
        // as well as a depth — and it is deliberately absent: `SumOfProducts` is the only operator whose
        // argument list takes two entity specs, and a `Having` filter on either of them draws
        // `MVK_WILDCARD_AT_LOWEST_REPEATABLE_LEVEL_REQUIRED` whatever the filter says (the unfiltered call is ADMITTED,
        // so that is the operator's own wildcard law and not a connective verdict). No oracle for that shape
        // exists here, so the checker opens a frame at a filter too, which can only under-report.
        actual.put("two filtered calls compared, one And and one Or",
                verdict("NumberOfFilledFields(Addons*/Name Having [Addons/Name] == \"x\" "
                        + "And [Addons/MonthlyFee] > 1) > NumberOfFilledFields(Addons*/MonthlyFee Having "
                        + "[Addons/Name] == \"y\" Or [Addons/MonthlyFee] > 2) And " + A));

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("two sibling filters, one And and one Or, joined by Or", KernelLaws.ADMITTED);
        expected.put("…and the same pair with the outer level itself mixed", "MVK_BRACKET_MISSING");
        expected.put("two filtered calls compared, one And and one Or", KernelLaws.ADMITTED);
        assertEquals(expected, actual, "whether two calls at one depth share a connective level");
    }

    private static String verdict(String condition) {
        return KernelLaws.verdictOf(ModelWriter.addRule(Fixtures.subscriptionJson(), "/Subscription",
                "CompositionProbe", "../Tier", "PROBE", condition, "ERROR", List.of(en("x"), de("y"))));
    }
}
