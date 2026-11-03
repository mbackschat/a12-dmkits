package io.github.mbackschat.a12.dm.rulekit.catalog;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.ResourceBundle;
import java.util.TreeSet;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.core.ValueKind;
import io.github.mbackschat.a12.dm.rulekit.dsl.ConditionLanguage;
import io.github.mbackschat.a12.dm.rulekit.dsl.DslRenderer;
import io.github.mbackschat.a12.dm.rulekit.dsl.FieldPresenceCondition;
import io.github.mbackschat.a12.dm.rulekit.dsl.FieldRef;

/**
 * N3 keyword-parity, the <b>reverse</b> direction (the remaining N3 work named in {@link KeywordParityTest}):
 * every kernel lexer terminal ({@code LexerTerminals_{en,de}}) is either a <em>cataloged</em> operator
 * keyword or an explicit, reasoned exclusion. The forward test catches a catalog keyword that drifts from
 * the grammar; this one catches the opposite — a keyword the <em>kernel</em> adds (a new operator, alias,
 * or separator) that the catalog hasn't accounted for. The exclusion list is self-cleaning: a stale entry
 * (no longer a terminal, or now cataloged) fails too, so it tracks the live lexer.
 *
 * <p>Excluded terminals fall into a few kinds: case-insensitive aliases of a cataloged operator
 * ({@code AND}/{@code and} for {@code And}); grammar-literal separators rulekit renders directly
 * ({@code In}, {@code To}); structural/directive keywords that aren't condition operators
 * ({@code RuleGroup}/{@code RegelKontext}, {@code SuppressWarning}); root-group (Vordruck) presence
 * conditions modeled via the group-presence catalog entries; and the deferred constructs also listed in
 * {@code adapter.KernelConstructParityTest} (the dynamic-enum {@code ValueNotConsistent} and the legacy
 * German-only / multi-form ops).
 */
class KeywordReverseParityTest {

    private static final String BUNDLE =
            "com.mgmtp.a12.kernel.core.parser.internal.grammar.languages.LexerTerminals";
    private static final ResourceBundle EN = ResourceBundle.getBundle(BUNDLE, Locale.ENGLISH);
    private static final ResourceBundle DE = ResourceBundle.getBundle(BUNDLE, Locale.GERMAN);
    private static final OperatorCatalog CATALOG = OperatorCatalog.load();

    /** Excluded terminal → reason, keyed by {@code "<lang> <terminal>"} (e.g. {@code "EN And"}). */
    private static final Map<String, String> EXCLUDED = excluded();

    @Test
    void everyKernelLexerTerminalIsCatalogedOrExcluded() {
        TreeSet<String> catalogEn = new TreeSet<>();
        TreeSet<String> catalogDe = new TreeSet<>();
        for (OperatorEntry e : CATALOG.entries()) {
            catalogEn.add(e.keywords().en());
            catalogDe.add(e.keywords().de());
        }
        List<String> unaccounted = new ArrayList<>();
        unaccounted.addAll(uncovered("EN", EN, catalogEn));
        unaccounted.addAll(uncovered("DE", DE, catalogDe));
        assertTrue(unaccounted.isEmpty(), () -> "kernel lexer terminal(s) neither cataloged nor excluded "
                + "(a new/renamed kernel keyword? catalog it, or add to EXCLUDED with a reason): " + unaccounted);

        // self-cleaning: an EXCLUDED entry that is no longer an uncovered terminal must be dropped
        List<String> stale = new ArrayList<>();
        for (String key : EXCLUDED.keySet()) {
            String[] parts = key.split(" ", 2);
            ResourceBundle b = parts[0].equals("EN") ? EN : DE;
            TreeSet<String> cataloged = parts[0].equals("EN") ? catalogEn : catalogDe;
            if (!b.keySet().contains(parts[1]) || cataloged.contains(parts[1])) {
                stale.add(key + " — no longer an uncovered terminal (renamed/removed, or now cataloged); drop it");
            }
        }
        assertTrue(stale.isEmpty(), () -> "stale EXCLUDED entries (the exclusion list must track the live lexer): " + stale);
    }

    @Test
    void rehearsal_aKernelAddingAKeywordIsFlagged() {
        // a fabricated new terminal is neither cataloged nor excluded → the guardian above would fail
        assertTrue(!EN.keySet().contains("SomeBrandNewKeywordV99") && !EXCLUDED.containsKey("EN SomeBrandNewKeywordV99"),
                "a fabricated kernel keyword must be neither a terminal nor excluded");
        assertTrue(EN.keySet().contains("FieldFilled"), "a real terminal is present (the check isn't vacuous)");
    }

    @Test
    void rendererQuotesEveryLiveLexerTerminalThatCanBeAnEntityName() {
        DslRenderer renderer = new DslRenderer(ConditionLanguage.EN);
        TreeSet<String> terminals = new TreeSet<>();
        terminals.addAll(EN.keySet());
        terminals.addAll(DE.keySet());
        terminals.stream()
                .filter(name -> name.matches("[A-Za-z0-9_ÄÜÖäüöß:]+"))
                .forEach(name -> assertTrue(renderer.render(new FieldPresenceCondition(
                                new FieldRef("/Owner/" + name, ValueKind.STRING), true))
                                .contains("/'" + name + "'"),
                        () -> "live lexer terminal must be quoted in an entity path: " + name));

        String ordinary = renderer.render(new FieldPresenceCondition(
                new FieldRef("/Owner/OrdinaryEntityV99", ValueKind.STRING), true));
        assertTrue(ordinary.contains("/OrdinaryEntityV99") && !ordinary.contains("'OrdinaryEntityV99'"),
                "the control name must remain unquoted");
    }

    private static List<String> uncovered(String lang, ResourceBundle bundle, TreeSet<String> cataloged) {
        List<String> out = new ArrayList<>();
        for (String terminal : bundle.keySet()) {
            if (!cataloged.contains(terminal) && !EXCLUDED.containsKey(lang + " " + terminal)) {
                out.add(lang + " " + terminal);
            }
        }
        return out;
    }

    private static Map<String, String> excluded() {
        Map<String, String> m = new LinkedHashMap<>();
        // case-insensitive aliases of a cataloged logical/separator operator
        put(m, "case-insensitive alias of And/Or (rendered canonically as And/Or)",
                "EN AND", "EN and", "EN OR", "EN or", "DE UND", "DE und", "DE ODER", "DE oder");
        // grammar-literal separators rulekit renders directly (not operator keywords)
        put(m, "the In value-list/operand separator — a grammar literal rulekit renders as ' In ', not an operator",
                "EN IN", "EN In", "EN in", "DE IN", "DE In", "DE in");
        put(m, "the To/toOp separator — a grammar literal (fieldAndValueListCondition / date-range), not an operator",
                "EN TO", "EN To", "EN to", "DE ZU", "DE Zu", "DE zu");
        // structural / directive keywords (not condition operators)
        put(m, "structural model keyword (rule-group declaration), not a condition operator",
                "EN RuleGroup", "DE RegelKontext");
        put(m, "directive keyword (suppress a warning), not a condition operator",
                "EN SuppressWarning", "DE UnterdrueckeWarnung");
        // root-group (Vordruck) presence conditions — modeled via the group-presence catalog entries (GroupFilled/…)
        put(m, "root-group (Vordruck) presence condition — modeled via the group-presence handling (RootGroupConditionName)",
                "DE VordruckAngegeben", "DE VordruckNichtAngegeben",
                "DE MindestensEinVordruckAngegeben", "DE KeinVordruckAngegeben");
        // deferred constructs (also in adapter.KernelConstructParityTest's exclusion list)
        put(m, "deferred: dynamic-enumeration dependent-column check (ValueNotConsistent)",
                "EN ValueNotConsistent", "DE WertNichtKonsistent");
        put(m, "uncataloged: kernel 31.1.1's term sum over a repeatable group's rows (SumOfTerms, KF656)",
                "EN SumOfTerms", "DE SummeVonTermen");
        put(m, "deferred: legacy German-only / multi-form (Vordruck/USB) construct — not modeled",
                "DE AktuelleVordruckNummer", "DE AnzahlAngegebeneUsb", "DE AnzahlAngegebenerVordrucke",
                "DE MaximalAngegebenerZeilenindex", "DE UsbNichtFortlaufend", "DE VordruckNichtFortlaufend",
                "DE ZeileNichtEindeutig", "DE ZeilenLueckenlos", "DE ZeilenNichtAufsteigend");
        return m;
    }

    private static void put(Map<String, String> m, String reason, String... keys) {
        for (String key : keys) {
            m.put(key, reason);
        }
    }
}
