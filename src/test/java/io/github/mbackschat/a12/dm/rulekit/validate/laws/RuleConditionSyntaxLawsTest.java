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
 * What A12 reports for condition text that is PRESENT and non-blank but cannot be parsed.
 *
 * <p><b>This is a different axis from its neighbour and the distinction is easy to miss.</b>
 * {@code RuleConditionAndTargetSlotLawsTest} measures the slot's PRESENCE — absent, empty, whitespace — and
 * answers `MVK_CONDITION_MISSING`. A grep for condition laws returns that file and reads as coverage, but
 * nothing there carries malformed SYNTAX, which is the whole question a checker-side parser has to answer:
 * which verdict it must reproduce, and — the sharper half — which malformed-looking texts A12 nonetheless
 * ACCEPTS, because a parser stricter than A12's would reject models the Kernel admits.
 *
 * <p><b>The rows that matter most are the ones separating a PARSE failure from a REFERENCE failure.</b> An
 * unknown identifier is not obviously one or the other: A12's lexer resolves operator keywords per language,
 * so a name it does not know stays an `ID` and may still reach the grammar as an entity reference. That
 * decides whether the checker's parser may report a syntax verdict for an unknown name or must leave it to
 * the reference family — and getting it backwards would make the two families report the same defect twice,
 * or neither report it.
 *
 * <p><b>Measured: there are FOUR parse-failure codes, one per phase, and `MVK_CONDITION` is none of them.</b>
 * The MVK ledger's `MVK_CONDITION` row describes rulekit's own authoring surface, not what
 * {@code checkConsistency} returns for a malformed STORED condition — a distinction that cost every one of
 * this grid's seven predictions, all of which said `MVK_CONDITION`. What A12 actually returns tracks the
 * ANTLR pipeline: {@code MVK_LEXER_STANDARD_ERROR} when the text cannot be tokenized at all,
 * {@code MVK_UNEXPECTED_TOKEN} when a token cannot be used where it stands, {@code MVK_EXPECTED_TOKEN_NOT_FOUND}
 * when a token the grammar requires is absent, and {@code MVK_INCOMPLETE_INPUT} when the input simply ends
 * early. A checker-side parser can therefore report the same four rather than one blanket code.
 *
 * <p>Own domain (the shared subscription fixture); the model's condition language is English throughout, so
 * the German-keyword row is a genuine foreign-language probe rather than a spelling variant.
 */
class RuleConditionSyntaxLawsTest {

    /** A condition over a real field of the probe's group — the liveness control. */
    private static final String VALID = "FieldFilled(Tier)";

    @Test
    void whatA12SaysAboutConditionTextItCannotParse() {
        Map<String, String> actual = new LinkedHashMap<>();
        actual.put("FieldFilled(Tier) — the control", verdict(VALID));
        actual.put("an unclosed argument list", verdict("FieldFilled(Tier"));
        actual.put("punctuation that is no condition at all", verdict("@@@"));
        actual.put("a trailing infix operator", verdict("FieldFilled(Tier) And"));
        actual.put("an unclosed string literal", verdict("FieldFilled(\\\"Tier)"));
        // The two discriminators between a parse failure and a reference failure.
        actual.put("an unknown name used as a call", verdict("NotAnOperator(Tier)"));
        actual.put("a GERMAN keyword in an English model", verdict("FeldAngegeben(Tier)"));
        // Whether a condition must be boolean-SHAPED at all, or whether a bare reference is admitted.
        actual.put("a bare field reference as the whole condition", verdict("Tier"));

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("FieldFilled(Tier) — the control", KernelLaws.ADMITTED);
        // FOUR distinct codes, one per parse phase, and none of them is `MVK_CONDITION`.
        expected.put("an unclosed argument list", "MVK_EXPECTED_TOKEN_NOT_FOUND");
        expected.put("punctuation that is no condition at all", "MVK_UNEXPECTED_TOKEN");
        expected.put("a trailing infix operator", "MVK_INCOMPLETE_INPUT");
        expected.put("an unclosed string literal", "MVK_LEXER_STANDARD_ERROR");
        // All three discriminators land on the PARSE side, which is the family-ownership answer: an unknown
        // name never reaches the reference check, so the syntax layer owns it and reporting it as an
        // unresolvable reference would name the wrong defect.
        expected.put("an unknown name used as a call", "MVK_UNEXPECTED_TOKEN");
        expected.put("a GERMAN keyword in an English model", "MVK_UNEXPECTED_TOKEN");
        expected.put("a bare field reference as the whole condition", "MVK_UNEXPECTED_TOKEN");
        assertEquals(expected, actual, "A12's verdict on condition text that does not parse");
    }

    /**
     * The DELIMITER grid, and it settles that a balanced-delimiter check CANNOT pick A12's code.
     *
     * <p>The question was whether a checker increment could report delimiter imbalance without a grammar, by
     * counting. It cannot. Only the shape where the input ENDS with something still open draws
     * {@code MVK_EXPECTED_TOKEN_NOT_FOUND}; every other imbalance draws {@code MVK_UNEXPECTED_TOKEN},
     * because the parser meets a token that is wrong where it stands before it ever runs out of input. Two of
     * the five predictions here were wrong in exactly that way — an unclosed value bracket and a bracket
     * closing a parenthesis both LOOK like "something is missing" and are both reported as "this token is
     * unexpected".
     *
     * <p>So the distinction A12 draws is positional, not arithmetic: counting tells you an imbalance exists,
     * never which of the two codes it is. A middle increment between lexing and parsing was planned on the
     * opposite assumption and was withdrawn on this grid.
     */
    @Test
    void whichCodeEachDelimiterImbalanceDraws() {
        Map<String, String> actual = new LinkedHashMap<>();
        actual.put("an unclosed call — the control", verdict("FieldFilled(Tier"));
        actual.put("an extra closing parenthesis", verdict("FieldFilled(Tier))"));
        actual.put("an unclosed value bracket", verdict("FieldFilled([Tier)"));
        actual.put("a bracket closing a parenthesis", verdict("FieldFilled(Tier]"));
        actual.put("a stray closing bracket", verdict("FieldFilled(Tier)]"));

        Map<String, String> expected = new LinkedHashMap<>();
        // The ONLY row that runs out of input: everything opened is still open when the text ends.
        expected.put("an unclosed call — the control", "MVK_EXPECTED_TOKEN_NOT_FOUND");
        expected.put("an extra closing parenthesis", "MVK_UNEXPECTED_TOKEN");
        // Both of these are delimiter imbalances that a counter would group with the control, and A12 does
        // not: it meets `Tier` where it wanted `]`, and `]` where it wanted `)`, and reports the token.
        expected.put("an unclosed value bracket", "MVK_UNEXPECTED_TOKEN");
        expected.put("a bracket closing a parenthesis", "MVK_UNEXPECTED_TOKEN");
        expected.put("a stray closing bracket", "MVK_UNEXPECTED_TOKEN");
        assertEquals(expected, actual, "which parse code each delimiter imbalance draws");
    }

    private static String verdict(String condition) {
        return KernelLaws.verdictOf(ModelWriter.addRule(Fixtures.subscriptionJson(), "/Subscription",
                "SyntaxProbe", "../Tier", "PROBE", condition, "ERROR", List.of(en("x"), de("y"))));
    }
}
