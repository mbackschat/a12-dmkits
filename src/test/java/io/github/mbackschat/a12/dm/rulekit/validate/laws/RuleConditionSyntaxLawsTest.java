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

    /**
     * The TRAILING-OPERATOR grid: does "the text ends with an infix operator" always mean the same code?
     *
     * <p>One row of the first grid measured it for {@code And}, and a checker increment keyed on the
     * generated keyword table's {@code OPERATOR} kind rests on that one row generalizing. It does — across
     * word operators, a comparison, arithmetic, and a trailing comma.
     *
     * <p><b>RETRACTED 2026-09-12 — the last two rows do NOT show what this grid originally claimed.</b> They
     * were read as "a dangling PATH operator draws {@code MVK_UNEXPECTED_TOKEN}, so the catalog's
     * {@code OPERATOR}/{@code PATH_OP} split tracks A12's parse behaviour and the kind column is safe to key
     * on". {@link #whetherTheLastTokenAloneCanDecideTheCode} refutes it in both directions: the same
     * {@code For} with the value bracket removed draws {@code MVK_INCOMPLETE_INPUT}, and a trailing
     * {@code And} inside an unclosed call draws {@code MVK_UNEXPECTED_TOKEN}. What both of these rows
     * actually carry is an unclosed {@code [} — a fact about the text BEFORE the last token.
     *
     * <p>What survives is narrower and still useful: every row here whose text is delimiter-BALANCED at the
     * trailing operator draws {@code MVK_INCOMPLETE_INPUT}, across word operators, a comparison and
     * arithmetic. That is the region the checker restricts itself to. It is a statement about where the
     * measurements are, not a mechanism — the refutation grid is there precisely because the mechanism this
     * javadoc used to assert was built on a single table member and generalized to a kind.
     */
    @Test
    void aConditionThatEndsWithAnInfixOperatorIsIncompleteWhateverTheOperator() {
        Map<String, String> actual = new LinkedHashMap<>();
        actual.put("ends with And — the control", verdict("FieldFilled(Tier) And"));
        actual.put("ends with Or", verdict("FieldFilled(Tier) Or"));
        actual.put("ends with a comparison", verdict("[Tier] >"));
        actual.put("ends with arithmetic", verdict("[Tier] > 1 +"));
        actual.put("ends with a comma inside a call", verdict("AllFieldsFilled(Tier,"));
        // PATH operators are a different catalog kind, so grouping them with the infix ones would be a
        // transfer rather than a measurement — these two rows are what make the keyword-kind key safe.
        actual.put("ends with the category arrow", verdict("FieldFilled([Tier ->"));
        actual.put("ends with the semantic-index keyword", verdict("FieldFilled([Tier For"));

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("ends with And — the control", "MVK_INCOMPLETE_INPUT");
        expected.put("ends with Or", "MVK_INCOMPLETE_INPUT");
        expected.put("ends with a comparison", "MVK_INCOMPLETE_INPUT");
        expected.put("ends with arithmetic", "MVK_INCOMPLETE_INPUT");
        expected.put("ends with a comma inside a call", "MVK_INCOMPLETE_INPUT");
        // NOT the same class: a PATH operator left dangling is reported as an unexpected token, so grouping
        // it with the infix ones would have made a checker emit the wrong code for both.
        expected.put("ends with the category arrow", "MVK_UNEXPECTED_TOKEN");
        expected.put("ends with the semantic-index keyword", "MVK_UNEXPECTED_TOKEN");
        assertEquals(expected, actual, "whether ending on an infix operator is one class");
    }

    /**
     * The grid that REFUTES the account above: the last token does not decide the code, the grammar does.
     *
     * <p>The two grids above were read as showing that a trailing {@code OPERATOR} draws
     * {@code MVK_INCOMPLETE_INPUT} and a trailing {@code PATH_OP} draws {@code MVK_UNEXPECTED_TOKEN}, so a
     * checker could pick the code from the catalog's kind column alone, looking at nothing before the last
     * token. <b>Both halves are false, and one discriminating character shows it.</b> Removing the value
     * bracket from {@code FieldFilled([Tier For} flips it to {@code MVK_INCOMPLETE_INPUT}; adding an unclosed
     * delimiter before a trailing {@code And} flips that to {@code MVK_UNEXPECTED_TOKEN}. The two remaining
     * {@code PATH_OP} members behave the opposite way from the one that was measured.
     *
     * <p>The account was built on exactly one table member — {@code For} — since {@code ->} is punctuation and
     * is not a keyword at all, and it was then generalized to a three-member kind whose other two members
     * contradict it. That is the scope-transfer shape: no new branch was needed, so nothing prompted the
     * question. What the rows below actually establish is a NEGATIVE result, and it is the useful one: which
     * parse code a truncated condition draws depends on the parser's state, so no last-token rule can pick it
     * and the checker must restrict itself to the shapes measured rather than infer the rest.
     */
    @Test
    void whetherTheLastTokenAloneCanDecideTheCode() {
        Map<String, String> actual = new LinkedHashMap<>();
        // The rows the retracted account was built on — kept so the refutation is read beside its source.
        actual.put("For after an unclosed value bracket", verdict("FieldFilled([Tier ->"));
        actual.put("the arrow after an unclosed value bracket", verdict("FieldFilled([Tier For"));
        // One character removed, and the code changes: the bracket, not the keyword's kind, was deciding.
        actual.put("the same arrow with no value bracket", verdict("FieldFilled(Tier ->"));
        actual.put("the same For with no value bracket", verdict("FieldFilled(Tier For"));
        actual.put("a bare value bracket and the arrow", verdict("[Tier ->"));
        actual.put("a bare value bracket and For", verdict("[Tier For"));
        // …and the OPERATOR side flips the other way, which the account cannot accommodate at all.
        actual.put("And inside an unclosed call", verdict("FieldFilled(Tier And"));
        actual.put("And after an unclosed value bracket", verdict("FieldFilled([Tier) And"));
        // Mismatched pairs, which a DEPTH COUNTER reads as balanced: the checker matches delimiter kinds
        // instead, and these two rows are what make that difference observable rather than defensive.
        actual.put("And after a mismatched closer", verdict("FieldFilled(Tier] And"));
        actual.put("And after a paren closing a bracket", verdict("FieldFilled[Tier) And"));
        // The two PATH_OP members that were never measured, both against the account.
        actual.put("the from-scope keyword left dangling",
                verdict("RepetitionNotUnique(Addons*/Name @From"));
        actual.put("the filter keyword left dangling",
                verdict("NumberOfFilledFields(Addons*/Name Having"));
        // Positive controls. What they have to establish is that the two rows above are truncations of text
        // that PARSES — so a SEMANTIC code here is the control passing, not failing: A12 got through the
        // grammar and rejected the completed condition on its meaning. (Neither completes to an admitted
        // condition on this fixture: `@From` wants a non-wildcard scope and the filter's error field is not
        // referenced by the condition. Both are rejections from well past the parser.)
        actual.put("the from-scope keyword completed — a control",
                verdict("RepetitionNotUnique(Addons*/Name @From Addons)"));
        actual.put("the filter keyword completed — a control",
                verdict("NumberOfFilledFields(Addons*/Name Having [Addons/Name] == \"x\") > 0"));

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("For after an unclosed value bracket", "MVK_UNEXPECTED_TOKEN");
        expected.put("the arrow after an unclosed value bracket", "MVK_UNEXPECTED_TOKEN");
        expected.put("the same arrow with no value bracket", "MVK_INCOMPLETE_INPUT");
        expected.put("the same For with no value bracket", "MVK_INCOMPLETE_INPUT");
        expected.put("a bare value bracket and the arrow", "MVK_INCOMPLETE_INPUT");
        expected.put("a bare value bracket and For", "MVK_INCOMPLETE_INPUT");
        expected.put("And inside an unclosed call", "MVK_UNEXPECTED_TOKEN");
        expected.put("And after an unclosed value bracket", "MVK_UNEXPECTED_TOKEN");
        expected.put("And after a mismatched closer", "MVK_UNEXPECTED_TOKEN");
        expected.put("And after a paren closing a bracket", "MVK_UNEXPECTED_TOKEN");
        expected.put("the from-scope keyword left dangling", "MVK_INCOMPLETE_INPUT");
        expected.put("the filter keyword left dangling", "MVK_INCOMPLETE_INPUT");
        expected.put("the from-scope keyword completed — a control", "MVK_NO_WILDCARD");
        expected.put("the filter keyword completed — a control", "MVK_ERROR_FIELD_NOT_REFERENCED");
        assertEquals(expected, actual, "whether the last token's kind alone can pick the parse code");
    }

    /**
     * The FULL trailing-token set, because the checker refuses on more than the grid above measured.
     *
     * <p>The completeness family keys on two things: the tokenizer's symbolic-operator set (nine symbols) and
     * the generated keyword table's {@code OPERATOR} kind (the whole catalog, not just {@code And}/{@code Or}).
     * Two symbols and two keywords had rows. A client-side refusal may never be broader than its measured
     * basis, so the rest are measured here rather than carried by resemblance — and the symbol most likely to
     * be wrong is {@code *}, which in A12 is also the REPETITION marker, a second meaning the tokenizer does
     * not separate.
     *
     * <p>The last rows go the other way: {@code AND}, {@code and}, {@code In} and {@code To} are real kernel
     * lexer terminals that the generated table classifies {@code UNCATALOGED}, because the catalog carries
     * one canonical spelling per operator. The checker is silent on all of them while catching {@code And} —
     * an internal inconsistency, and this grid says which direction to fix it in.
     *
     * <p>Every row is delimiter-BALANCED, since the grid above showed an unclosed delimiter changes the code.
     */
    @Test
    void whichTrailingTokensRunA12OutOfInput() {
        Map<String, String> actual = new LinkedHashMap<>();
        for (String symbol : List.of("==", "!=", "<=", ">=", "<", ">", "+", "*", "^", "-", "/")) {
            actual.put("ends with " + symbol, verdict("[Tier] " + symbol));
        }
        for (String spelling : List.of("And", "AND", "and", "Or", "OR", "or")) {
            actual.put("ends with " + spelling, verdict("FieldFilled(Tier) " + spelling));
        }
        for (String keyword : List.of("In", "IN", "in", "To", "TO", "to", "PatternMatched",
                "DiffersWithToleranceRange1")) {
            actual.put("ends with " + keyword, verdict("[Tier] " + keyword));
        }

        Map<String, String> expected = new LinkedHashMap<>();
        for (String symbol : List.of("==", "!=", "<=", ">=", "<", ">", "+", "*", "^", "-", "/")) {
            expected.put("ends with " + symbol, "MVK_INCOMPLETE_INPUT");
        }
        for (String spelling : List.of("And", "AND", "and", "Or", "OR", "or")) {
            expected.put("ends with " + spelling, "MVK_INCOMPLETE_INPUT");
        }
        // `PatternMatched` and `DiffersWithToleranceRange1` settle the scope transfer: the family keys on the
        // catalog's whole OPERATOR kind while only `And`/`Or` had rows, and the kind does generalize.
        expected.put("ends with PatternMatched", "MVK_INCOMPLETE_INPUT");
        expected.put("ends with DiffersWithToleranceRange1", "MVK_INCOMPLETE_INPUT");
        // …and `In`/`To` do NOT, in any spelling. They are kernel terminals the generated table leaves
        // UNCATALOGED, so the checker is silent on them — which this grid shows is the correct answer rather
        // than a lucky one, because A12 meets a token it cannot use rather than running out of input.
        for (String keyword : List.of("In", "IN", "in", "To", "TO", "to")) {
            expected.put("ends with " + keyword, "MVK_UNEXPECTED_TOKEN");
        }
        assertEquals(expected, actual, "which trailing tokens A12 answers by running out of input");
    }

    private static String verdict(String condition) {
        return KernelLaws.verdictOf(ModelWriter.addRule(Fixtures.subscriptionJson(), "/Subscription",
                "SyntaxProbe", "../Tier", "PROBE", condition, "ERROR", List.of(en("x"), de("y"))));
    }
}
