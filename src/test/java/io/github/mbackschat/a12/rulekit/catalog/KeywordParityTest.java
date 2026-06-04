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
}
