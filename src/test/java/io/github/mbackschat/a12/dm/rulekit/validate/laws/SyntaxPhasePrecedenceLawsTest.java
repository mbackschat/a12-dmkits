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
 * Which phase A12 reports when one condition carries TWO syntax failures.
 *
 * <p>{@code RuleConditionSyntaxLawsTest} measures the code for a text with ONE failure, per phase. This grid
 * measures the ARBITRATION between phases, which is a separate question and the one a checker running several
 * syntax families has to answer: with a bad quote and a bad token both present, A12 names exactly one of them,
 * and a checker that reports the wrong one sends the author to the wrong end of the text — or, where two
 * families each report their own, hands them two findings for one defect.
 *
 * <p><b>The candidate account is POSITIONAL: the failure that sits earliest in the text wins, whichever phase
 * owns it.</b> Its mechanism is that ANTLR's lexer is demand-driven — it produces a token only when the parser
 * asks for one — so the lexer cannot report a failure at a position the parser has not yet reached, and the
 * parser cannot reach a position the lexer has already refused to hand it. Every row below is an ordered PAIR
 * of the same two defects, because order is the only variable that can be moved without changing what the two
 * defects are: a grid that varied the defects instead could not tell position from kind.
 *
 * <p>Own domain (the shared subscription fixture), English condition language throughout.
 */
class SyntaxPhasePrecedenceLawsTest {

    private static final String LEXER = "MVK_LEXER_STANDARD_ERROR";
    private static final String TOKEN = "MVK_UNEXPECTED_TOKEN";

    /**
     * The parser's phase against the lexer's, in both orders, for both lexical classes.
     *
     * <p>The two lexical classes are measured separately rather than by transfer: an unterminated literal and
     * an illegal character both draw {@code MVK_LEXER_STANDARD_ERROR} on their own, but they reach it by
     * different terminals — the literal MATCHES a terminal that never ends, the character matches none — and
     * nothing says a shared code implies shared precedence.
     */
    @Test
    void whetherTheEARLIERFailureWinsWhicheverPhaseOwnsIt() {
        Map<String, String> actual = new LinkedHashMap<>();
        // The two defects on their own, so a row below is attributable to their ORDER and not to either one.
        actual.put("an extra closing parenthesis alone — the control", verdict("FieldFilled(Tier))"));
        actual.put("an unterminated literal alone — the control", verdict("FieldFilled(Tier) And \"x"));
        actual.put("an illegal character alone — the control", verdict("FieldFilled(Tier) And %"));
        // The pairs. Same two defects, same text either side of the swap.
        actual.put("an extra closer, then an unterminated literal", verdict("FieldFilled(Tier)) And \"x"));
        actual.put("an unterminated literal, then an extra closer", verdict("\"x And FieldFilled(Tier))"));
        actual.put("an extra closer, then an illegal character", verdict("FieldFilled(Tier)) And %"));
        actual.put("an illegal character, then an extra closer", verdict("% And FieldFilled(Tier))"));
        // The tight case: one character apart, which is where the parser's error RECOVERY could lex the bad
        // quote before it has finished reporting the token it already holds.
        actual.put("an extra closer immediately followed by a bad quote", verdict("FieldFilled(Tier))\"x"));

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("an extra closing parenthesis alone — the control", TOKEN);
        expected.put("an unterminated literal alone — the control", LEXER);
        expected.put("an illegal character alone — the control", LEXER);
        expected.put("an extra closer, then an unterminated literal", TOKEN);
        expected.put("an unterminated literal, then an extra closer", LEXER);
        expected.put("an extra closer, then an illegal character", TOKEN);
        expected.put("an illegal character, then an extra closer", LEXER);
        expected.put("an extra closer immediately followed by a bad quote", TOKEN);
        assertEquals(expected, actual, "which of two syntax failures A12 reports");
    }

    /**
     * The lexer against the COMPLETENESS phase, and this is the grid that separates first-wins from last-wins.
     *
     * <p>ANTLR does not stop at a character it cannot take: it reports the failure, skips the character and
     * lexes on. So {@code FieldFilled('Tier) And} raises the lexer's failure at the quote and then hands the
     * parser {@code FieldFilled(Tier) And}, which runs out of input and raises a SECOND failure — the control
     * row below is that same text with the quote removed, and it draws {@code MVK_INCOMPLETE_INPUT}. Two
     * failures, and A12 reports one: whichever it names says whether the FIRST or the LAST error wins, which
     * no row of the pairs grid above can tell apart, both of its failures being fatal to the token stream.
     */
    @Test
    void whetherTheFIRSTFailureWinsWhenTheLexerRecoversAndTheParserThenFails() {
        Map<String, String> actual = new LinkedHashMap<>();
        actual.put("a trailing connective alone — the control", verdict("FieldFilled(Tier) And"));
        actual.put("a bad quote before a trailing connective", verdict("FieldFilled('Tier) And"));
        actual.put("an illegal character before a trailing connective", verdict("FieldFilled(%Tier) And"));

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("a trailing connective alone — the control", "MVK_INCOMPLETE_INPUT");
        expected.put("a bad quote before a trailing connective", LEXER);
        expected.put("an illegal character before a trailing connective", LEXER);
        assertEquals(expected, actual, "whether the first or the last failure is reported");
    }

    /**
     * A parse error sitting AFTER the lexical one, which is what stops the account collapsing into "the
     * parser outranks the lexer".
     *
     * <p>Both pairs above put the parse error first when the parser wins, so they are equally consistent with
     * a phase-precedence rule that has nothing to do with position. These rows move the parse error behind the
     * lexical failure and keep everything else: a stray {@code ]} draws {@code MVK_UNEXPECTED_TOKEN} on its
     * own (the control), and behind a bad quote it must not.
     */
    @Test
    void whetherAParseErrorLATERInTheTextStillLoses() {
        Map<String, String> actual = new LinkedHashMap<>();
        actual.put("a stray closing bracket alone — the control", verdict("FieldFilled(Tier) ]"));
        actual.put("a bad quote before a stray closing bracket", verdict("FieldFilled('Tier) ]"));
        actual.put("an illegal character before a stray closing bracket", verdict("FieldFilled(%Tier) ]"));

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("a stray closing bracket alone — the control", TOKEN);
        expected.put("a bad quote before a stray closing bracket", LEXER);
        expected.put("an illegal character before a stray closing bracket", LEXER);
        assertEquals(expected, actual, "whether a parse error behind a lexical one wins");
    }

    private static String verdict(String condition) {
        return KernelLaws.verdictOf(ModelWriter.addRule(Fixtures.subscriptionJson(), "/Subscription",
                "PrecedenceProbe", "../Tier", "PROBE", condition, "ERROR", List.of(en("x"), de("y"))));
    }
}
