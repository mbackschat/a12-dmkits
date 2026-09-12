package io.github.mbackschat.a12.dm.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.ResourceBundle;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.SourceText;
import io.github.mbackschat.a12.dm.rulekit.catalog.OperatorCatalog;
import io.github.mbackschat.a12.dm.rulekit.catalog.OperatorEntry;
import io.github.mbackschat.a12.dm.rulekit.catalog.OperatorKind;

/**
 * {@code ConditionReferenceRemapper}'s hand-written bilingual sets name real kernel terminals, and its
 * non-reference atoms are exactly the constants.
 *
 * <p><b>What the remapper is.</b> The third hand-written condition vocabulary in this repository, after the
 * interpreter's translation table and the checker's generated one. It is a small lexer over the
 * REFERENCE-BEARING surface only — enough to rewrite entity references when a rename or move rebases a path,
 * with quoted literals and comments opaque. It never parses a whole condition, which is why its lists are
 * semantic subsets rather than the full terminal set.
 *
 * <p><b>What can be checked, and what cannot.</b> Which keywords introduce a reference position is partly a
 * judgement about the grammar — but only partly, and the sentence here used to say "no artifact in this
 * repository decides", which was too strong. The catalog decides one direction outright: every word-shaped
 * {@code OPERATOR} and {@code PATH_OP} keyword except the reference-group selector IS a separator, and
 * {@link #everyCatalogSeparatorKeywordIsInTheList} asserts it. That is the corrupting direction — a keyword
 * silently missing from the list leaves a path unrewritten by a rename, which was demonstrable by deleting
 * {@code Having} and watching nothing fail. The other direction stays a judgement: {@code In} is a separator
 * and is in no catalog kind at all, so an equality would invent a denominator. The
 * non-reference atoms are different: they are the identifiers that appear WITHOUT parentheses and are not
 * references, which is precisely the catalog's {@code CONSTANT} kind plus the rule-context entity. That one
 * is an equality in both directions, and it is the valuable half — a constant this list has not learned is
 * read as a field reference and silently rewritten.
 */
class ConditionRemapperVocabularyTest {

    private static final Path REMAPPER = Path.of(
            "core/src/main/java/io/github/mbackschat/a12/dm/core/ConditionReferenceRemapper.java");

    private static final String BUNDLE =
            "com.mgmtp.a12.kernel.core.parser.internal.grammar.languages.LexerTerminals";

    /**
     * The rule's own group, which the kernel spells {@code RuleGroup}/{@code RegelKontext}. It is a grammar
     * keyword rather than an operator, so the catalog does not carry it while the lexer does — the same
     * population {@code consistency/build.gradle} declares as uncataloged.
     */
    private static final Set<String> RULE_CONTEXT_ENTITY = Set.of("RuleGroup", "RegelKontext");

    /**
     * The reference-group selector, spelled {@code @From}/{@code @Von} by the lexer. The remapper stores it
     * without the sigil for the same reason the interpreter does — it matches the {@code '@'} separately —
     * so the two spellings denote one terminal.
     */
    private static final java.util.Map<String, String> SIGIL_CARRIED_BY_THE_SCANNER =
            java.util.Map.of("From", "@From", "Von", "@Von");

    @Test
    void everyKeywordTheRemapperKnowsIsARealTerminalOfThatLanguage() {
        for (String language : List.of("EN", "DE")) {
            Set<String> terminals = kernelTerminals(language);
            Set<String> invented = new TreeSet<>();
            for (String set : setsOf(language)) {
                for (String keyword : literalSet(set)) {
                    String spelled = SIGIL_CARRIED_BY_THE_SCANNER.getOrDefault(keyword, keyword);
                    if (!terminals.contains(spelled)) {
                        invented.add(set + ": " + keyword);
                    }
                }
            }
            assertEquals(Set.of(), invented,
                    () -> "the remapper treats " + language + " name(s) as grammar the kernel's lexer does not "
                            + "know, so a rename would scan around a reference that is really there");
        }
    }

    /**
     * The atoms the remapper refuses to rewrite are exactly the constants plus the rule-context entity.
     *
     * <p>Both directions matter and they fail differently. A constant MISSING from the list is read as a
     * field reference, so a rename rewrites {@code Today} into a path — silent corruption of a condition. A
     * non-constant PRESENT in it is a reference the remapper will not rebase, so the rename silently leaves a
     * dangling path behind.
     */
    @Test
    void theNonReferenceAtomsAreExactlyTheConstantsPlusTheRuleContextEntity() {
        OperatorCatalog catalog = OperatorCatalog.load();
        for (String language : List.of("EN", "DE")) {
            Set<String> expected = new TreeSet<>();
            for (OperatorEntry constant : catalog.byKind(OperatorKind.CONSTANT)) {
                OperatorEntry.Keywords keywords = constant.keywords();
                expected.add("EN".equals(language) ? keywords.en() : keywords.de());
            }
            Set<String> contextEntity = new TreeSet<>(RULE_CONTEXT_ENTITY);
            contextEntity.retainAll(kernelTerminals(language));
            expected.addAll(contextEntity);

            String set = "EN".equals(language) ? "ENGLISH_NON_REFERENCE_ATOMS" : "GERMAN_NON_REFERENCE_ATOMS";
            assertEquals(expected, literalSet(set),
                    () -> set + " must hold exactly the catalog's constant-kind keywords plus the rule-context "
                            + "entity — a constant missing here is rewritten into a path by the next rename");
        }
    }

    /**
     * Every catalog keyword that introduces a reference position is in the separator list.
     *
     * <p>The direction that corrupts. A separator missing from the list is not read as a separator, so the
     * path after it is never rebased and a rename leaves a dangling reference — the same failure mode the
     * atoms test names, one keyword class over. Deleting {@code Having} from the list used to change nothing.
     *
     * <p>The denominator is the catalog's own, not one invented here: the word-shaped members of the
     * {@code OPERATOR} and {@code PATH_OP} kinds. Symbols are excluded because the remapper scans identifiers,
     * and the reference-group selector because it carries a sigil the remapper matches separately — both are
     * declared rather than filtered silently, so a kind gaining a member fails here.
     */
    @Test
    void everyCatalogSeparatorKeywordIsInTheList() {
        OperatorCatalog catalog = OperatorCatalog.load();
        for (String language : List.of("EN", "DE")) {
            Set<String> required = new TreeSet<>();
            for (OperatorKind kind : List.of(OperatorKind.OPERATOR, OperatorKind.PATH_OP)) {
                for (OperatorEntry entry : catalog.byKind(kind)) {
                    OperatorEntry.Keywords keywords = entry.keywords();
                    String spelling = "EN".equals(language) ? keywords.en() : keywords.de();
                    if (isWordShaped(spelling) && !REFERENCE_GROUP_SELECTOR.contains(spelling)) {
                        required.add(spelling);
                    }
                }
            }
            String set = "EN".equals(language) ? "ENGLISH_REFERENCE_SEPARATORS" : "GERMAN_REFERENCE_SEPARATORS";
            Set<String> absent = new TreeSet<>(required);
            absent.removeAll(literalSet(set));
            assertEquals(Set.of(), absent,
                    () -> set + " is missing catalog keyword(s) that introduce a reference position, so the "
                            + "next rename leaves the path after them unrebased");
            // Without this the loop above passes on an empty catalog kind, which is the vacuity the whole file
            // exists to avoid — eight operators and two path operators are word-shaped in both languages.
            assertTrue(required.size() >= 10,
                    () -> "the catalog's separator kinds came back nearly empty (" + required
                            + "), so the containment asserted nothing");
        }
    }

    /** The lexer spells it with a sigil the remapper matches separately, so the list stores neither form. */
    private static final Set<String> REFERENCE_GROUP_SELECTOR = Set.of("@From", "@Von");

    /** The catalog's operator kind holds symbols too; the remapper only ever scans identifiers. */
    private static boolean isWordShaped(String spelling) {
        return Character.isLetter(spelling.charAt(0));
    }

    /**
     * The control that keeps both checks above from passing on an empty or merged reading: the two languages
     * disagree, and the catalog really does classify constants apart from everything else.
     */
    @Test
    void theTwoLanguagesAreReadSeparatelyAndTheCatalogHasConstants() {
        assertTrue(literalSet("ENGLISH_REFERENCE_SEPARATORS").contains("And")
                        && !literalSet("ENGLISH_REFERENCE_SEPARATORS").contains("Und"),
                "the remapper's EN separators must know And and not Und");
        assertTrue(literalSet("GERMAN_REFERENCE_SEPARATORS").contains("Und")
                        && !literalSet("GERMAN_REFERENCE_SEPARATORS").contains("And"),
                "the remapper's DE separators must know Und and not And");
        assertTrue(OperatorCatalog.load().byKind(OperatorKind.CONSTANT).size() >= 5,
                "the catalog must classify constants, or the equality above would assert against an empty set");
    }

    private static List<String> setsOf(String language) {
        return "EN".equals(language)
                ? List.of("ENGLISH_REFERENCE_SEPARATORS", "ENGLISH_NON_REFERENCE_ATOMS")
                : List.of("GERMAN_REFERENCE_SEPARATORS", "GERMAN_NON_REFERENCE_ATOMS");
    }

    private static Set<String> kernelTerminals(String language) {
        Locale locale = "EN".equals(language) ? Locale.US : Locale.GERMANY;
        return new TreeSet<>(ResourceBundle.getBundle(BUNDLE, locale).keySet());
    }

    private static final Pattern QUOTED = Pattern.compile("\"([^\"]+)\"");

    /**
     * One {@code Set.of(...)} constant, read from the shipped source with comments blanked.
     *
     * <p>Source text rather than reflection because the class is package-private by design; widening it so a
     * test can look would change production visibility to suit the guard.
     */
    private static Set<String> literalSet(String name) {
        String source = uncommented();
        Matcher declaration = Pattern.compile(name + "\\s*=\\s*Set\\.of\\((.*?)\\);", Pattern.DOTALL)
                .matcher(source);
        assertTrue(declaration.find(),
                () -> REMAPPER + " no longer declares a Set.of constant named " + name);
        Set<String> members = new TreeSet<>();
        Matcher member = QUOTED.matcher(declaration.group(1));
        while (member.find()) {
            members.add(member.group(1));
        }
        assertTrue(!members.isEmpty(), () -> name + " read as empty, so this guard would assert nothing");
        return members;
    }

    private static String uncommented() {
        try {
            return SourceText.uncommented(REMAPPER);
        } catch (IOException unreadable) {
            throw new UncheckedIOException("cannot read the remapper at " + REMAPPER, unreadable);
        }
    }
}
