package io.github.mbackschat.a12.rulekit.catalog;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Locale;
import java.util.ResourceBundle;

import org.junit.jupiter.api.Test;

/**
 * N3 keyword-parity anchor (proposal §9, honesty direction 2) — every catalog operator keyword is
 * reconciled against the kernel's own lexer terminals (`LexerTerminals_{en,de}`, loaded from the
 * classpath: a {@code ListResourceBundle} mapping keyword → token). A worded keyword must be a real
 * terminal in its language; a symbolic one ({@code <=}, {@code ==}, {@code ->}) is a
 * language-independent symbol terminal (identical EN/DE), not in the worded bundle. So a typo or a
 * keyword that drifts from the grammar fails CI — covering both SUPPORTED operators and PLANNED
 * stubs (whose keywords were hand-sourced from these same bundles).
 *
 * <p>Forward direction (catalog → terminals). The reverse (every lexer terminal mapped or excluded)
 * and the parsetree construct-name / error-type parity are the remaining N3 work.
 */
class KeywordParityTest {

    private static final String BUNDLE =
            "com.mgmtp.a12.kernel.core.parser.internal.grammar.languages.LexerTerminals";
    private static final ResourceBundle EN = ResourceBundle.getBundle(BUNDLE, Locale.ENGLISH);
    private static final ResourceBundle DE = ResourceBundle.getBundle(BUNDLE, Locale.GERMAN);
    private static final OperatorCatalog CATALOG = OperatorCatalog.load();

    @Test
    void everyCatalogKeywordIsAKernelLexerTerminal() {
        for (OperatorEntry e : CATALOG.entries()) {
            String en = e.keywords().en();
            String de = e.keywords().de();
            if (isSymbolic(en)) {
                // a symbol terminal (e.g. <=, ==, ->) — identical in every condition language
                assertEquals(en, de, () -> e.id() + ": a symbolic keyword must be language-independent");
            } else {
                assertTrue(EN.keySet().contains(en),
                        () -> e.id() + ": EN keyword '" + en + "' is not a kernel lexer terminal");
                assertTrue(DE.keySet().contains(de),
                        () -> e.id() + ": DE keyword '" + de + "' is not a kernel lexer terminal");
            }
        }
    }

    private static boolean isSymbolic(String keyword) {
        return keyword.chars().noneMatch(Character::isLetter);
    }

    // --- dry-run negative-controls: rehearse a kernel keyword change and prove this guardian fires (task #4) ---

    @Test
    void rehearsal_aDriftedKeywordWouldBeRejected() {
        // The guardian asserts every catalog keyword is a real lexer terminal. Rehearse a kernel that
        // renames/removes one (or a catalog typo): a keyword that is not a terminal is absent from the
        // bundle, so the `EN.keySet().contains(...)` assertion above would fail.
        assertTrue(EN.keySet().contains("FieldFilled"), "a real terminal must be present");
        assertTrue(!EN.keySet().contains("FieldFilledRenamedByKernel"),
                "a drifted/renamed keyword must be absent — that is what makes the parity check bite");
    }

    @Test
    void rehearsal_theLexerBundlesAreNonEmpty() {
        // Anti-rot: an empty/missing bundle would make `contains(...)` always false and the forward
        // parity assertions fail loudly rather than silently pass — confirm the bundles actually loaded.
        assertTrue(EN.keySet().size() > 20 && DE.keySet().size() > 20, "lexer terminal bundles should be populated");
    }
}
