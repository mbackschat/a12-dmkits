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
     * Which characters the lexer DISCARDS. Its {@code WS} rule is exactly space, carriage return, tab, form
     * feed and line feed, so every other character Unicode calls whitespace is one no terminal spells. In the
     * grid's slot a discarded character leaves a valid condition, so the two outcomes are ADMITTED and the
     * lexer error. And {@code #} is legal exactly where it opens {@code #Zeile}, the only {@code #} either
     * grammar spells ({@code ZEILE_ITER}, a fixed literal the multi-language handler never retypes), so
     * {@code #Zeile} lexes and is refused a phase later while {@code #Zeil} cannot lex.
     */
    @Test
    void onlyTheLexersFiveWhitespaceCharactersAreDiscarded() {
        Map<String, String> actual = new LinkedHashMap<>();
        actual.put("a space — the control", verdictWithCharacter(" "));
        actual.put("a tab", verdictWithCharacter("\t"));
        actual.put("a form feed", verdictWithCharacter("\f"));
        actual.put("a line feed", verdictWithCharacter("\n"));
        actual.put("a carriage return", verdictWithCharacter("\r"));
        actual.put("a no-break space", verdictWithCharacter("\u00a0"));
        actual.put("a vertical tab", verdictWithCharacter("\u000b"));
        actual.put("an em space", verdictWithCharacter("\u2003"));
        actual.put("an ideographic space", verdictWithCharacter("\u3000"));
        actual.put("a next-line character", verdictWithCharacter("\u0085"));
        actual.put("`#Zeile`", verdictWithCharacter("#Zeile"));
        actual.put("`#Zeil`", verdictWithCharacter("#Zeil"));

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("a space — the control", KernelLaws.ADMITTED);
        expected.put("a tab", KernelLaws.ADMITTED);
        expected.put("a form feed", KernelLaws.ADMITTED);
        expected.put("a line feed", KernelLaws.ADMITTED);
        expected.put("a carriage return", KernelLaws.ADMITTED);
        expected.put("a no-break space", LEXER);
        expected.put("a vertical tab", LEXER);
        expected.put("an em space", LEXER);
        expected.put("an ideographic space", LEXER);
        expected.put("a next-line character", LEXER);
        expected.put("`#Zeile`", TOKEN);
        expected.put("`#Zeil`", LEXER);
        assertEquals(expected, actual, "which characters A12's condition lexer discards");
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

    /**
     * Inside a {@code ;;} COMMENT the same characters are ordinary text too, up to either line terminator.
     *
     * <p>{@code Comment : ';;' ~('\r' | '\n')* …} takes every character but the two terminators, so a no-break
     * space is refused only where it stands between tokens. And the comment ends at a lone carriage return as
     * well as at a line feed, so text after a CR is live again: the character that is harmless inside the
     * comment answers with the lexer class there. That row is the one a tokenizer reading "to the end of the
     * line" as "to the next line feed" gets wrong, and the grid's slot rows keep it from being about the slot.
     */
    @Test
    void aCommentIsOrdinaryTextUpToEitherLineTerminator() {
        Map<String, String> actual = new LinkedHashMap<>();
        actual.put("a comment — the control", verdictWithComment(";; a note\n"));
        actual.put("a no-break space inside a comment", verdictWithComment(";; a\u00a0note\n"));
        actual.put("a vertical tab inside a comment", verdictWithComment(";; a\u000bnote\n"));
        actual.put("`é` inside a comment", verdictWithComment(";; a note é\n"));
        actual.put("a comment a lone carriage return ends", verdictWithComment(";; a note\r"));
        actual.put("`é` after a lone carriage return", verdictWithComment(";; a note\ré "));
        actual.put("a no-break space before a continuing comment line",
                verdictWithComment(";; a note\n\u00a0;; more\n"));

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("a comment — the control", KernelLaws.ADMITTED);
        expected.put("a no-break space inside a comment", KernelLaws.ADMITTED);
        expected.put("a vertical tab inside a comment", KernelLaws.ADMITTED);
        expected.put("`é` inside a comment", KernelLaws.ADMITTED);
        expected.put("a comment a lone carriage return ends", KernelLaws.ADMITTED);
        expected.put("`é` after a lone carriage return", LEXER);
        expected.put("a no-break space before a continuing comment line", LEXER);
        assertEquals(expected, actual, "which characters a condition comment holds");
    }

    /** A comment in the grid's slot, before the call it precedes, where the grammar admits one. */
    private static String verdictWithComment(String comment) {
        return verdict("FieldFilled(Tier) And " + comment + " FieldFilled(AutoRenew)");
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
