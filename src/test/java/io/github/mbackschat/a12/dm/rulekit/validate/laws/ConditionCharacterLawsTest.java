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
 * Which CHARACTERS A12's condition lexer cannot tokenize at all — the class its own message names.
 *
 * <p>A12 answers an untokenizable condition with {@code MVK_LEXER_STANDARD_ERROR}, and its message text
 * ("Character '%s' at position %d is incorrect") says that a stray character is one way to get there. Which
 * characters those are was never measured: one probe had found {@code @@@} lexing cleanly and failing a phase
 * later, and a single negative result is not a class.
 *
 * <p><b>The admitted set is DERIVABLE, which is what makes this measurable rather than a survey.</b>
 * {@code Base_ConditionLexer.g4} has no catch-all rule, so every character outside its terminals is a
 * token-recognition failure. Outside a string literal the terminals admit exactly: the {@code STR} fragment
 * ({@code a-z A-Z _ Ä Ü Ö ä ü ö ß :}), {@code DIGIT}, the whitespace set, and the punctuation the operator and
 * bracket terminals spell ({@code + - * / ^ > < = ! ( ) [ ] &#123; &#125; , . @ # $ ' "}). <i>Source:</i>
 * {@code kernel-tool/kernel-core-parser/src/main/antlr4/…/Base_ConditionLexer.g4}, kernel {@code 30.8.1},
 * read 2026-09-13.
 *
 * <p><b>The grid discriminates rather than merely observing, because the two outcomes have different codes.</b>
 * Every row puts one character in the same slot of one otherwise-valid condition. A character the lexer cannot
 * take answers {@code MVK_LEXER_STANDARD_ERROR}; one it CAN take, standing where the grammar cannot use it,
 * answers {@code MVK_UNEXPECTED_TOKEN}. So "illegal character" and "legal token in the wrong place" are
 * separable by the answer itself, and the legal-character rows are the control that keeps the illegal ones
 * from being about the slot.
 *
 * <p><b>Two characters exist ONLY inside a multi-character terminal</b> — {@code !} appears only in
 * {@code '!='} and {@code #} only in {@code '#Zeile'} — so the grammar predicts each is illegal when it stands
 * alone, though both are "punctuation the lexer knows". Those are the rows a set assembled by eye would get
 * wrong in the direction that matters, since a checker refusing them from a hand-listed alphabet would be
 * right, and one admitting all "known punctuation" would be wrong.
 *
 * <p>Own domain (the shared subscription fixture).
 */
class ConditionCharacterLawsTest {

    private static final String LEXER = "MVK_LEXER_STANDARD_ERROR";
    private static final String TOKEN = "MVK_UNEXPECTED_TOKEN";

    /**
     * One character in an operand slot of an otherwise-valid condition.
     *
     * <p>The slot is between a connective and the call that follows it, so a character the lexer accepts lands
     * as a token the parser then refuses, and the two outcomes are told apart by their codes rather than by
     * anything this test decides.
     */
    private static String verdictWithCharacter(String character) {
        return verdict("FieldFilled(Tier) And " + character + " FieldFilled(AutoRenew)");
    }

    @Test
    void aCharacterTheLexerCannotTakeIsADifferentFailureFromATokenTheGrammarCannotUse() {
        Map<String, String> actual = new LinkedHashMap<>();
        // The controls: characters the grammar's terminals DO spell, so the lexer takes them and the parser
        // is what refuses. If these answered the same as the rows below, the grid would be measuring the slot.
        actual.put("`$`, a terminal of its own — the control", verdictWithCharacter("$"));
        actual.put("`@`, a terminal of its own — the control", verdictWithCharacter("@"));
        actual.put("`,`, a terminal of its own — the control", verdictWithCharacter(","));
        actual.put("`ß`, in the STR fragment — the control", verdictWithCharacter("ß"));
        // Characters no terminal spells.
        actual.put("`%`", verdictWithCharacter("%"));
        actual.put("`&`", verdictWithCharacter("&"));
        actual.put("`|`", verdictWithCharacter("|"));
        actual.put("`;`", verdictWithCharacter(";"));
        actual.put("`?`", verdictWithCharacter("?"));
        actual.put("`~`", verdictWithCharacter("~"));
        actual.put("a backslash", verdictWithCharacter("\\"));
        // A letter that is not one of the five German characters the STR fragment names.
        actual.put("`é`", verdictWithCharacter("é"));
        actual.put("`€`", verdictWithCharacter("€"));
        // The two that exist only INSIDE a multi-character terminal.
        actual.put("`!`, which the lexer spells only as `!=`", verdictWithCharacter("!"));
        actual.put("`#`, which the lexer spells only as `#Zeile`", verdictWithCharacter("#"));

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("`$`, a terminal of its own — the control", TOKEN);
        expected.put("`@`, a terminal of its own — the control", TOKEN);
        expected.put("`,`, a terminal of its own — the control", TOKEN);
        // A STR character becomes a NAME, which the parser then refuses where a condition was expected.
        expected.put("`ß`, in the STR fragment — the control", TOKEN);
        expected.put("`%`", LEXER);
        expected.put("`&`", LEXER);
        expected.put("`|`", LEXER);
        expected.put("`;`", LEXER);
        expected.put("`?`", LEXER);
        expected.put("`~`", LEXER);
        expected.put("a backslash", LEXER);
        expected.put("`é`", LEXER);
        expected.put("`€`", LEXER);
        expected.put("`!`, which the lexer spells only as `!=`", LEXER);
        expected.put("`#`, which the lexer spells only as `#Zeile`", LEXER);
        assertEquals(expected, actual, "which characters A12's condition lexer cannot take");
    }

    /**
     * Inside a STRING LITERAL every one of those characters is ordinary text.
     *
     * <p>{@code StringLiteral : '"' ( '""' | ~["] )* '"'} admits anything but a quote, so the illegal-character
     * class is about text OUTSIDE a literal and nowhere else. Without these rows a checker could scan the raw
     * text for a bad character and reject a condition A12 accepts — the one direction that cannot be caught
     * downstream, and the reason this test exists beside the grid above rather than after it.
     */
    @Test
    void thoseSameCharactersAreOrdinaryTextInsideAStringLiteral() {
        List<String> characters = List.of("%", "&", "|", ";", "?", "~", "\\", "é", "€", "!", "#");
        Map<String, String> actual = new LinkedHashMap<>();
        Map<String, String> expected = new LinkedHashMap<>();
        for (String character : characters) {
            actual.put(character, verdictInLiteral(character));
            expected.put(character, KernelLaws.ADMITTED);
        }
        assertEquals(expected, actual, "the same characters inside a string literal");
    }

    private static String verdict(String condition) {
        return KernelLaws.verdictOf(ModelWriter.addRule(Fixtures.subscriptionJson(), "/Subscription",
                "CharacterProbe", "../Tier", "PROBE", condition, "ERROR", List.of(en("x"), de("y"))));
    }

    /**
     * The literal compared against a plain STRING field, in that field's own group.
     *
     * <p>It deliberately does not reuse the grid's rule above. That one targets {@code Tier}, which is an
     * ENUMERATION, so comparing it to arbitrary text draws
     * {@code MVK_INVALID_STRING_CONSTANT_FOR_ENUM_COMPARISON} — a verdict about the field's TYPE that would
     * have stood in for a verdict about the character, on every row alike and therefore invisibly.
     */
    private static String verdictInLiteral(String character) {
        return KernelLaws.verdictOf(ModelWriter.addRule(Fixtures.subscriptionJson(), "/Subscription/Billing",
                "LiteralProbe", "../InvoiceEmail", "PROBE",
                "[InvoiceEmail] == \"a" + character + "b\"", "ERROR", List.of(en("x"), de("y"))));
    }
}
