package io.github.mbackschat.a12.dm.consistency;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.ResourceBundle;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

/**
 * The portable checker's condition-keyword table holds exactly the kernel lexer's terminals, per language.
 *
 * <p>{@code :consistency} is Kernel-free by contract (H1), so its table is generated from rulekit's operator
 * catalog plus the terminals that catalog deliberately omits, declared in {@code consistency/build.gradle}.
 * That makes the table a claim about the kernel that the module itself cannot check — which is what this
 * test is for, and why it lives here, where the kernel is on the classpath. It is the third direction of the
 * N3 keyword parity: {@code KeywordParityTest} checks catalog → terminals, {@code KeywordReverseParityTest}
 * terminals → catalog-or-excluded, and this one terminals ↔ the checker's shipped table, which must be
 * complete in both directions because a missing keyword reads as an undeclared field and an invented one
 * hides a live reference.
 *
 * <p>The languages are compared separately on purpose. The kernel resolves a name against exactly one
 * language's terminals ({@code AbstractMultiLanguageHandler.setTypeInLexer}), so their difference is
 * semantic, not cosmetic: the German bundle's H-model operators are ordinary field names in an English
 * model.
 */
class ConditionKeywordProjectionTest {

    private static final Path TABLE = Path.of(
            "consistency/src/commonMain/kotlin/io/github/mbackschat/a12/dm/consistency/syntax/ConditionKeywords.kt");

    private static final String BUNDLE =
            "com.mgmtp.a12.kernel.core.parser.internal.grammar.languages.LexerTerminals";

    /**
     * {@code #Zeile} is the fixed {@code ZEILE_ITER} literal rather than a translated terminal, so it is in
     * no language's bundle. The table carries it because a name scan must still not read {@code Zeile} as a
     * reference.
     */
    private static final Set<String> FIXED_LITERALS = Set.of("#Zeile");

    @Test
    void eachLanguagesTableIsExactlyThatLanguagesLexerTerminals() {
        Map<String, Set<String>> table = shippedTable();
        for (Locale locale : List.of(Locale.US, Locale.GERMANY)) {
            String language = locale == Locale.US ? "EN" : "DE";
            Set<String> expected = new TreeSet<>(ResourceBundle.getBundle(BUNDLE, locale).keySet());
            expected.addAll(FIXED_LITERALS);
            assertEquals(expected, table.get(language),
                    () -> "the " + language + " keyword table must hold exactly the lexer's terminals — "
                            + "regenerate with `./build.sh :consistency:generateConditionKeywords` after "
                            + "declaring the difference in consistency/build.gradle");
        }
    }

    /**
     * The fact that makes one merged table wrong, measured rather than argued: each language's spelling of
     * {@code And} is an ordinary name in the other, so a checker holding the union would drop a live
     * reference in whichever direction it guessed. It also keeps the comparison above from being vacuous in
     * the way that would matter — two tables that happened to be equal would fail here.
     */
    @Test
    void aKeywordOfOneLanguageIsAnOrdinaryNameInTheOther() {
        Set<String> kernelEn = ResourceBundle.getBundle(BUNDLE, Locale.US).keySet();
        Set<String> kernelDe = ResourceBundle.getBundle(BUNDLE, Locale.GERMANY).keySet();
        assertTrue(kernelEn.contains("And") && !kernelEn.contains("Und"),
                "the kernel's EN terminals know And and not Und");
        assertTrue(kernelDe.contains("Und") && !kernelDe.contains("And"),
                "the kernel's DE terminals know Und and not And");

        Map<String, Set<String>> table = shippedTable();
        assertTrue(table.get("EN").contains("And") && !table.get("EN").contains("Und"),
                "the shipped EN table must follow the kernel's EN answer");
        assertTrue(table.get("DE").contains("Und") && !table.get("DE").contains("And"),
                "the shipped DE table must follow the kernel's DE answer");
    }

    private static final Pattern LANGUAGE = Pattern.compile("ConditionLanguage\\.(EN|DE) to mapOf\\(");
    private static final Pattern ENTRY = Pattern.compile("^\\s+\"(.+)\" to KeywordKind\\.");

    /**
     * The generated table as source text — the shipped artifact, not the catalog it was generated from.
     *
     * <p>Scoped to the {@code CONDITION_KEYWORDS} declaration because the same file carries a second
     * per-language table ({@code CONDITION_OPERATOR_KEYWORDS}, the operator spellings). Its headers are
     * spelled identically, so an unscoped scan read it as a continuation and ended with two empty sets.
     */
    private static Map<String, Set<String>> shippedTable() {
        Map<String, Set<String>> table = new LinkedHashMap<>();
        String language = null;
        boolean inside = false;
        try {
            for (String line : Files.readAllLines(TABLE)) {
                if (line.startsWith("val ")) {
                    inside = line.startsWith("val CONDITION_KEYWORDS");
                    language = null;
                }
                if (!inside) {
                    continue;
                }
                Matcher header = LANGUAGE.matcher(line);
                Matcher entry = ENTRY.matcher(line);
                if (header.find()) {
                    language = header.group(1);
                    table.put(language, new TreeSet<>());
                } else if (language != null && entry.find()) {
                    table.get(language).add(entry.group(1));
                } else if (line.startsWith("    )")) {
                    language = null;
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read the generated keyword table at " + TABLE, e);
        }
        assertEquals(Set.of("EN", "DE"), table.keySet(),
                "the generated table must carry both condition languages");
        return table;
    }
}
