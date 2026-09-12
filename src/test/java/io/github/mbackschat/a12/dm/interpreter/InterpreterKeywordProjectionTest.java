package io.github.mbackschat.a12.dm.interpreter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.ResourceBundle;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.SourceText;

/**
 * The interpreter's bilingual keyword table holds only real kernel terminals, and every terminal it leaves out
 * is one we can name a reason for.
 *
 * <p><b>Why this exists.</b> {@code ConditionLanguage.GERMAN_TO_CANONICAL} is HAND-WRITTEN — 106 rows against
 * the kernel's 113 EN and 126 DE terminals — and until this test nothing connected it to anything. The catalog
 * has a generator and a diff guard, and {@code :consistency}'s table has
 * {@code ConditionKeywordProjectionTest}; the interpreter's had neither, so a kernel bump or a catalog
 * addition reached it only when someone happened to notice (searched 2026-09-12:
 * {@code git grep -ln 'ALL_AUTHORED_KEYWORDS|ENGLISH_KEYWORDS|canonicalKeyword|authoredForms'} over every test
 * source returned nothing).
 *
 * <p><b>Why a guard and not a generator.</b> The obvious fix — generate this table from the catalog the way
 * {@code :consistency} does — is wrong in both directions. The table is a TRANSLATION table, not an admission
 * table: its English side is the identity, so a generator would have to invent rows, and it would also import
 * the H-model terminals and the operators rulekit defers, claiming interpreter support that does not exist.
 * What can be checked is the pair of containments below.
 *
 * <p>It lives here rather than in {@code :interpreter} for the same reason
 * {@code ConditionKeywordProjectionTest} lives outside {@code :consistency}: the module is kernel-free by
 * contract ({@code verifyInterpreterPurity}), so a claim of its about the kernel has to be settled where the
 * kernel is on the classpath.
 */
class InterpreterKeywordProjectionTest {

    private static final Path TABLE = Path.of(
            "interpreter/src/commonMain/kotlin/io/github/mbackschat/a12/dm/interpreter/model/ConditionLanguage.kt");

    private static final String BUNDLE =
            "com.mgmtp.a12.kernel.core.parser.internal.grammar.languages.LexerTerminals";

    /**
     * The reference-group selector is spelled {@code @From}/{@code @Von} by the lexer and {@code From}/
     * {@code Von} by this table, because {@link
     * io.github.mbackschat.a12.dm.interpreter.parse} consumes the {@code '@'} as its own character before
     * looking the identifier up ({@code Parser.kt}, the {@code peek() == '@'} branch of {@code readArgs}). The
     * sigil is therefore carried by the parser and not by the vocabulary, and the two spellings denote one
     * terminal.
     */
    private static final Map<String, String> SIGIL_CARRIED_BY_THE_PARSER = Map.of("From", "@From", "Von", "@Von");

    /**
     * Terminals whose EN and DE spellings are IDENTICAL, so a German→canonical row would map a name to itself.
     * The interpreter still reads them: an identifier followed by {@code '('} whose lookup misses becomes
     * {@code Expr.Func(authoredName)}, and {@code eval.Operators} carries all four. {@link
     * #anIdenticallySpelledTerminalIsTrulyIdenticalInBothBundles} checks the premise rather than trusting it,
     * so a kernel bump that gave one of them a German spelling fails here instead of silently dropping it.
     */
    private static final Set<String> SAME_SPELLING_IN_BOTH_LANGUAGES = Set.of("Abs", "Min", "Max", "DateTime");

    /** Terminals that are not condition operators, so the condition parser's vocabulary never meets them. */
    private static final Set<String> NOT_A_CONDITION_OPERATOR = Set.of(
            // The computation preamble pragma, parsed off the front of the operation rather than within it.
            "SuppressWarning", "UnterdrueckeWarnung");

    /**
     * The operator rulekit defers (it depends on dynamically enumerated columns) and the interpreter with it.
     * Unlike the checker — which must list it, or read it as an undeclared field — the interpreter refuses it
     * loudly at evaluation, so leaving it out of the vocabulary costs nothing.
     */
    private static final Set<String> DEFERRED_OPERATOR = Set.of("ValueNotConsistent", "WertNichtKonsistent");

    /**
     * H-model constructs — multi-form (Vordruck/USB) and bounded row iteration — classified {@code legacy} in
     * {@code docs/GRAMMAR-LEDGER.md} and unreachable from a rulekit-authored model. They exist only in the
     * German bundle, except {@code #Zeile}, which is a fixed lexer literal in neither.
     */
    private static final Set<String> H_MODEL_LEGACY = Set.of(
            "#Zeile", "AktuelleVordruckNummer", "AnzahlAngegebeneUsb", "AnzahlAngegebenerVordrucke",
            "KeinVordruckAngegeben", "MaximalAngegebenerZeilenindex", "MindestensEinVordruckAngegeben",
            "UsbNichtFortlaufend", "VordruckAngegeben", "VordruckNichtAngegeben", "VordruckNichtFortlaufend",
            "ZeileNichtEindeutig", "ZeilenLueckenlos", "ZeilenNichtAufsteigend");

    /**
     * The interpreter never treats a name as an operator that A12 would have read as a field.
     *
     * <p>This is the direction that can corrupt a result silently. A keyword the kernel does not know would
     * make the interpreter swallow a live field reference — no error, just a condition that reads something
     * else — which is the same failure mode the checker's table is guarded against from the other side.
     */
    @Test
    void everyKeywordTheInterpreterResolvesIsARealTerminalOfThatLanguage() {
        for (String language : List.of("EN", "DE")) {
            Set<String> invented = new TreeSet<>(resolvedByTheInterpreter(language));
            invented.removeAll(kernelTerminals(language));
            assertEquals(Set.of(), invented,
                    () -> "the interpreter resolves " + language + " name(s) the kernel's lexer does not know, "
                            + "so a model using one as a field name would lose the reference silently");
        }
    }

    /**
     * Every terminal the interpreter does NOT resolve is one of the four declared classes above.
     *
     * <p>This is the drift catcher: a kernel bump or a catalog addition that the hand-written table never
     * learned lands here as an unclassified name, rather than as an {@code UnsupportedConstructException} in
     * whichever model first uses it.
     */
    @Test
    void everyTerminalTheInterpreterDoesNotResolveIsDeclaredWithItsReason() {
        Set<String> declared = new HashSet<>();
        declared.addAll(SAME_SPELLING_IN_BOTH_LANGUAGES);
        declared.addAll(NOT_A_CONDITION_OPERATOR);
        declared.addAll(DEFERRED_OPERATOR);
        declared.addAll(H_MODEL_LEGACY);

        for (String language : List.of("EN", "DE")) {
            Set<String> unclassified = new TreeSet<>(kernelTerminals(language));
            unclassified.removeAll(resolvedByTheInterpreter(language));
            unclassified.removeAll(declared);
            assertEquals(Set.of(), unclassified,
                    () -> "the kernel knows " + language + " terminal(s) the interpreter's table does not, and "
                            + "no declared class in this test accounts for them — either add the row to "
                            + "ConditionLanguage.kt or declare the omission here with its reason");
        }
    }

    /**
     * The premise under {@link #SAME_SPELLING_IN_BOTH_LANGUAGES}, checked rather than asserted in prose.
     *
     * <p>Those four are excused from the translation table only because translating them would be the
     * identity. If a kernel release gave {@code Abs} a German spelling, the excuse would evaporate while the
     * exclusion list still read as deliberate — the exact shape of a comment that outlives the fact it rests
     * on.
     */
    @Test
    void anIdenticallySpelledTerminalIsTrulyIdenticalInBothBundles() {
        Set<String> en = kernelTerminals("EN");
        Set<String> de = kernelTerminals("DE");
        for (String keyword : new TreeSet<>(SAME_SPELLING_IN_BOTH_LANGUAGES)) {
            assertTrue(en.contains(keyword) && de.contains(keyword),
                    () -> "`" + keyword + "` is excused from the translation table because both languages "
                            + "spell it alike, and this bundle no longer agrees: EN=" + en.contains(keyword)
                            + " DE=" + de.contains(keyword));
        }
    }

    /**
     * The control that keeps the two containments from passing vacuously: the languages really do disagree,
     * so a table that had merged them would fail above instead of sailing through.
     */
    @Test
    void aKeywordOfOneLanguageIsAnOrdinaryNameInTheOther() {
        assertTrue(resolvedByTheInterpreter("EN").contains("And")
                        && !resolvedByTheInterpreter("EN").contains("Und"),
                "the interpreter's EN vocabulary must know And and not Und");
        assertTrue(resolvedByTheInterpreter("DE").contains("Und")
                        && !resolvedByTheInterpreter("DE").contains("And"),
                "the interpreter's DE vocabulary must know Und and not And");
    }

    private static Set<String> kernelTerminals(String language) {
        Locale locale = "EN".equals(language) ? Locale.US : Locale.GERMANY;
        return new TreeSet<>(ResourceBundle.getBundle(BUNDLE, locale).keySet());
    }

    private static final Pattern SET_OF = Pattern.compile("private val (\\w+) = setOf\\(([^)]*)\\)");
    private static final Pattern TRANSLATION_ROW = Pattern.compile("\"([^\"]+)\" to \"([^\"]+)\",");
    private static final Pattern QUOTED = Pattern.compile("\"([^\"]+)\"");

    /**
     * What {@code ConditionLanguage.canonicalKeyword} answers for, read from the shipped source.
     *
     * <p>Source text rather than the compiled enum, because the members that hold this are {@code internal}
     * and {@code private} — and deliberately so; widening them to let a test look would change the module's
     * API to suit its guard. Comments are blanked first ({@link SourceText}), so the doc comment listing
     * example spellings cannot be mistaken for table rows.
     */
    private static Set<String> resolvedByTheInterpreter(String language) {
        String source = uncommented();
        Map<String, String> translation = translationTable(source);
        Set<String> resolved = new TreeSet<>();
        if ("DE".equals(language)) {
            resolved.addAll(translation.keySet());
            resolved.addAll(literalSet(source, "DE_AND"));
            resolved.addAll(literalSet(source, "DE_OR"));
            resolved.addAll(literalSet(source, "DE_TO"));
        } else {
            // The English side is the translation's RANGE plus the two grammar keywords that have no German
            // row of their own, which is exactly how ENGLISH_KEYWORDS is built in the source.
            resolved.addAll(translation.values());
            resolved.add("In");
            resolved.add("To");
            resolved.addAll(literalSet(source, "EN_AND"));
            resolved.addAll(literalSet(source, "EN_OR"));
            resolved.addAll(literalSet(source, "EN_TO"));
        }
        resolved.addAll(literalSet(source, "IN"));
        return resolved.stream()
                .map(keyword -> SIGIL_CARRIED_BY_THE_PARSER.getOrDefault(keyword, keyword))
                .collect(java.util.stream.Collectors.toCollection(TreeSet::new));
    }

    private static Map<String, String> translationTable(String source) {
        int start = source.indexOf("GERMAN_TO_CANONICAL = mapOf(");
        assertTrue(start >= 0, "ConditionLanguage.kt no longer declares GERMAN_TO_CANONICAL as a map literal");
        String body = source.substring(start, source.indexOf("\n        )", start));
        Map<String, String> rows = new java.util.LinkedHashMap<>();
        Matcher row = TRANSLATION_ROW.matcher(body);
        while (row.find()) {
            rows.put(row.group(1), row.group(2));
        }
        assertTrue(rows.size() > 50,
                () -> "read only " + rows.size() + " translation rows, so this test is no longer reading the "
                        + "table it is meant to guard");
        return rows;
    }

    private static Set<String> literalSet(String source, String name) {
        Matcher declaration = SET_OF.matcher(source);
        while (declaration.find()) {
            if (declaration.group(1).equals(name)) {
                Set<String> members = new TreeSet<>();
                Matcher member = QUOTED.matcher(declaration.group(2));
                while (member.find()) {
                    members.add(member.group(1));
                }
                return members;
            }
        }
        throw new IllegalStateException("ConditionLanguage.kt no longer declares a set named " + name);
    }

    private static String uncommented() {
        try {
            return SourceText.uncommented(TABLE);
        } catch (IOException unreadable) {
            throw new UncheckedIOException("cannot read the interpreter's keyword table at " + TABLE, unreadable);
        }
    }
}
