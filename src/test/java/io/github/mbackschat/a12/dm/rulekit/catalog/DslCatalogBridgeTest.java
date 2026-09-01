package io.github.mbackschat.a12.dm.rulekit.catalog;

import io.github.mbackschat.a12.dm.Checkout;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

/**
 * The catalog↔DSL bridge never goes stale (G3 / DEV-API-GAPS §2) — bidirectional parity between the
 * <b>single source of truth</b> (the code-side {@code catalog:<Id>} Javadoc markers on every public
 * authoring entry point) and the shipped projection ({@code dslEntry} on each {@link OperatorEntry}):
 *
 * <ul>
 *   <li><b>markers → catalog:</b> every {@code catalog:<Id>} marker names a real catalog id, and the
 *       marker-derived {@code id → {method}} reverse map equals the catalog's {@code dslEntry} map
 *       (read through the <em>loaded record</em>, so a loader regression fails too);</li>
 *   <li><b>catalog → code:</b> every {@code dslEntry} member resolves to a public authoring method
 *       (reflection over the DSL types), so a stale/typo'd entry cannot ship;</li>
 *   <li><b>completeness:</b> every operator has EITHER a non-empty {@code dslEntry} (with markers) OR an
 *       explicit empty {@code dslEntry} + a {@code dslEntryNote} reason and <em>no</em> marker — so
 *       "absent" is impossible; it is always either mapped or explained.</li>
 * </ul>
 *
 * <p>Author by derivation: on a {@code dslEntry} mismatch the failure DUMPS the marker-derived map as
 * JSON (between {@code DSL_ENTRY_DUMP_BEGIN}/{@code END}) so the projection is generated from the
 * markers, never hand-authored twice. Mirrors {@link io.github.mbackschat.a12.dm.rulekit.validate}'s
 * source-reading parity guards ({@code RkCodeParityTest}); no Gradle input wiring (the repo-root walk
 * finds {@code Dsl.java}).
 */
class DslCatalogBridgeTest {

    private static final Checkout CHECKOUT = Checkout.locate();

    /**
     * A {@code catalog:<Id[,Id2,…]>} marker, paired to the next authoring method it documents. Group 1
     * is the comma-separated operator id(s) (one method can render several — {@code differsWithTolerance}
     * → the four {@code DiffersWithToleranceRange{N}}); group 2 is the method name (non-greedy up to the
     * first {@code (}). {@code public|default} so interface default methods ({@code Condition.and/or})
     * are covered. A marker MUST be the last token of its method's Javadoc so the next
     * {@code public}/{@code default} is that method.
     */
    private static final Pattern MARKER =
            Pattern.compile("catalog:([\\w,]+)\\b[\\s\\S]*?\\b(?:public|default)\\b[^(;{}]*?\\b(\\w+)\\s*\\(");

    private static final Pattern MARKER_TOKEN = Pattern.compile("catalog:[\\w,]+");

    /** The DSL authoring types the {@code dslEntry} members must resolve against (simple names, {@code dsl} package). */
    private static final List<String> AUTHORING_TYPES = List.of(
            "Dsl", "Condition", "FieldRef", "NumberRef", "StringRef", "EnumRef", "DateRef", "TimeRef",
            "DateTimeRef", "BoolRef", "ConfirmRef", "DateRangeRef", "DateFragmentRef", "GroupRef",
            "FieldListRef", "GroupListRef", "CategoryRef", "RuleGroupRef", "NumberExpr", "StringExpr",
            "EnumExpr", "DateExpr", "TimeExpr", "DateTimeExpr", "SemanticIndex",
            "RepetitionNotUniqueCondition", "Rule", "Computation");

    @Test
    void markersAndDslEntryAreInBidirectionalParity() throws IOException {
        assertTrue(Files.isRegularFile(CHECKOUT.resolve("rulekit/src/main/java/io/github/mbackschat/a12/dm/rulekit/dsl/Dsl.java")),
                "rulekit's Dsl.java, whose markers this parity reads, is missing — a rename must update this guard, which used to report the "
                        + "absence as a repository it could not locate");
        OperatorCatalog catalog = OperatorCatalog.load();

        // LEFT — the marker-derived id → {method} reverse map (the single source of truth).
        Map<String, TreeSet<String>> derived = new TreeMap<>();
        int matches = 0;
        int markerTokens = 0;
        List<Path> sources = dslSources();
        assertTrue(sources.size() > 20, () -> "expected to scan the dsl source tree, found " + sources.size());
        for (Path f : sources) {
            String text = Files.readString(f);
            Matcher tok = MARKER_TOKEN.matcher(text);
            while (tok.find()) {
                markerTokens++;
            }
            Matcher m = MARKER.matcher(text);
            while (m.find()) {
                matches++;
                String method = m.group(2);
                for (String id : m.group(1).split(",")) {
                    derived.computeIfAbsent(id, k -> new TreeSet<>()).add(method);
                }
            }
        }
        // every catalog:<Id> token paired to a method — a stray/mis-placed marker (no following method,
        // or two markers before one method) makes the counts diverge and fails loudly.
        int finalMatches = matches;
        int finalTokens = markerTokens;
        assertTrue(finalTokens == finalMatches, () -> "every catalog:<Id> marker must pair to a method — "
                + finalTokens + " marker tokens but " + finalMatches + " paired; a marker is stray or two "
                + "share one method's Javadoc (place the marker as the LAST token before the method)");

        // every marker id is a real catalog id
        List<String> unknownIds = derived.keySet().stream()
                .filter(id -> catalog.byId(id).isEmpty())
                .sorted()
                .toList();
        assertTrue(unknownIds.isEmpty(),
                () -> "catalog:<Id> markers naming non-existent operators: " + unknownIds);

        // every dslEntry member resolves to a public authoring method (catalog → code)
        java.util.Set<String> authoringMethods = authoringMethodNames();
        List<String> unresolved = catalog.entries().stream()
                .flatMap(e -> e.dslEntry().stream())
                .distinct()
                .filter(member -> !authoringMethods.contains(member))
                .sorted()
                .toList();
        assertTrue(unresolved.isEmpty(),
                () -> "dslEntry members that resolve to no public DSL authoring method: " + unresolved);

        // RIGHT — the catalog's dslEntry map (read through the loaded record).
        Map<String, TreeSet<String>> served = new TreeMap<>();
        for (OperatorEntry e : catalog.entries()) {
            if (!e.dslEntry().isEmpty()) {
                served.put(e.id(), new TreeSet<>(e.dslEntry()));
            }
        }

        // completeness: an empty dslEntry MUST carry a dslEntryNote AND have no marker.
        List<String> badEmpties = new ArrayList<>();
        for (OperatorEntry e : catalog.entries()) {
            if (e.dslEntry().isEmpty()) {
                if (e.dslEntryNote() == null || e.dslEntryNote().isBlank()) {
                    badEmpties.add(e.id() + " (empty dslEntry needs a dslEntryNote reason)");
                } else if (derived.containsKey(e.id())) {
                    badEmpties.add(e.id() + " (has a catalog:<Id> marker but an empty dslEntry — populate it)");
                }
            }
        }
        assertTrue(badEmpties.isEmpty(), () -> "incomplete/contradictory dslEntry declarations: " + badEmpties);

        // the bidirectional set-equality — the anti-drift core.
        if (!derived.equals(served)) {
            fail("dslEntry is out of parity with the code-side catalog:<Id> markers.\n"
                    + diff(derived, served)
                    + "\nPaste the marker-derived projection below into operators.json (one dslEntry per id):\n"
                    + dump(derived));
        }
    }

    /** A machine-readable id → sorted-members dump for author-by-derivation injection. */
    private static String dump(Map<String, TreeSet<String>> derived) {
        String body = derived.entrySet().stream()
                .map(en -> "  " + quote(en.getKey()) + ": ["
                        + en.getValue().stream().map(DslCatalogBridgeTest::quote).collect(Collectors.joining(", "))
                        + "]")
                .collect(Collectors.joining(",\n"));
        return "DSL_ENTRY_DUMP_BEGIN\n{\n" + body + "\n}\nDSL_ENTRY_DUMP_END";
    }

    private static String diff(Map<String, TreeSet<String>> derived, Map<String, TreeSet<String>> served) {
        StringBuilder sb = new StringBuilder();
        TreeSet<String> ids = new TreeSet<>(derived.keySet());
        ids.addAll(served.keySet());
        for (String id : ids) {
            TreeSet<String> l = derived.getOrDefault(id, new TreeSet<>());
            TreeSet<String> r = served.getOrDefault(id, new TreeSet<>());
            if (!l.equals(r)) {
                sb.append("  ").append(id).append(": markers=").append(l).append(" catalog.dslEntry=").append(r)
                        .append('\n');
            }
        }
        return sb.toString();
    }

    private static String quote(String s) {
        return '"' + s + '"';
    }

    private java.util.Set<String> authoringMethodNames() {
        java.util.Set<String> names = new TreeSet<>();
        for (String simple : AUTHORING_TYPES) {
            Class<?> type;
            try {
                type = Class.forName("io.github.mbackschat.a12.dm.rulekit.dsl." + simple);
            } catch (ClassNotFoundException e) {
                return fail("authoring type not found: " + simple);
            }
            for (var method : type.getMethods()) {
                names.add(method.getName());
            }
        }
        return names;
    }


    private static List<Path> dslSources() throws IOException {
        Path dir = CHECKOUT.resolve("rulekit/src/main/java/io/github/mbackschat/a12/dm/rulekit/dsl");
        try (Stream<Path> walk = Files.walk(dir)) {
            return walk.filter(f -> f.toString().endsWith(".java")).toList();
        }
    }
}
