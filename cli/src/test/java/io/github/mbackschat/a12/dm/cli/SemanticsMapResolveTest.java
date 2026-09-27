package io.github.mbackschat.a12.dm.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * The resolve-guard for <b>{@code docs/SEMANTICS-MAP.md}</b> — the {@link CommandReferencesResolveTest}
 * pattern applied to the semantics navigation map: every reference the map makes must resolve against the
 * live surface it points at, and every member of each derivable surface must appear in the map, so the map
 * is <i>derivable-and-checkable</i>, never hand-curated status. What is checked, per column:
 *
 * <ul>
 *   <li><b>Sections</b> — the two §-keyed tables each carry exactly §1–§14 (the KERNEL-SEMANTICS taxonomy).</li>
 *   <li><b>KERNEL-FINDINGS</b> — a row claims supplements iff {@code docs/KERNEL-FINDINGS.md} has ≥ 1 heading
 *       tagged {@code §n} (multi-tags like {@code §1/§3} count for both sections).</li>
 *   <li><b>Catalog facets</b> — the dimension names cited across the map equal exactly the {@code semantics}
 *       keys the live {@code operators.json} carries (a renamed/added dimension fails here).</li>
 *   <li><b>Corpus families</b> — each row's family list equals the grouping derived from the committed cases'
 *       {@code meta.sections} (the corpus contract's navigation join key), and every case's tags are
 *       well-formed {@code §1..§14}.</li>
 *   <li><b>Locking tests</b> — every backticked {@code *Test} token resolves to a file in a designated lock
 *       source (validate/laws, adapter/laws, the Kernel-backed adapter preparation locks, or interpreter
 *       commonTest, plus the explicit checker contract locks), and every {@code *Test} class in those sources appears somewhere in the map (a new law test
 *       must be placed — in a § row or the outside-the-taxonomy table — before the build goes green).</li>
 *   <li><b>Links</b> — every relative markdown link resolves to an existing file.</li>
 * </ul>
 *
 * The canonical prose ({@code docs/KERNEL-SEMANTICS.md}, integrated in-repo 2026-07-09) is a first-class
 * derivation surface, so the section-title agreement check runs always-on like the rest.
 *
 * <p>Runs in the dedicated {@code semanticsMapGuardTest} task with exactly its input files declared
 * (TESTING-SPEC "Tests that read repo files at runtime"), so a map/doc/corpus/test-tree edit re-runs only
 * this class and can never restore from the build cache into a false green.
 */
class SemanticsMapResolveTest {

    private static final Path MAP = Path.of("../docs/SEMANTICS-MAP.md");
    private static final Path KERNEL_FINDINGS = Path.of("../docs/KERNEL-FINDINGS.md");
    private static final Path CATALOG = Path.of("../rulekit/src/main/resources/catalog/operators.json");
    private static final Path CORPUS_CASES = Path.of("../corpus/cases");
    private static final Path KERNEL_SEMANTICS = Path.of("../docs/KERNEL-SEMANTICS.md");
    private static final Path ADAPTER_TESTS =
            Path.of("../adapter/src/test/java/io/github/mbackschat/a12/dm/adapter");
    private static final List<Path> LOCK_TREES = List.of(
            Path.of("../src/test/java/io/github/mbackschat/a12/dm/rulekit/validate/laws"),
            Path.of("../adapter/src/test/java/io/github/mbackschat/a12/dm/adapter/laws"),
            Path.of("../adapter/src/test/kotlin/io/github/mbackschat/a12/dm/adapter/laws"),
            Path.of("../interpreter/src/commonTest"));
    private static final List<Path> EXPLICIT_LOCK_FILES = List.of(
            Path.of("../consistency/src/commonTest/kotlin/io/github/mbackschat/a12/dm/consistency/FieldReadContractTest.kt"),
            Path.of("../consistency/src/commonTest/kotlin/io/github/mbackschat/a12/dm/consistency/NumericNameContractTest.kt"),
            Path.of("../consistency/src/commonTest/kotlin/io/github/mbackschat/a12/dm/consistency/OperationContractTest.kt"),
            Path.of("../consistency/src/commonTest/kotlin/io/github/mbackschat/a12/dm/consistency/ReportHarvestContractTest.kt"),
            Path.of("../consistency/src/commonTest/kotlin/io/github/mbackschat/a12/dm/consistency/SyntaxVerdictTest.kt"),
            Path.of("../adapter/src/test/kotlin/io/github/mbackschat/a12/dm/adapter/A12ParseTraceTest.kt"),
            Path.of("../adapter/src/test/kotlin/io/github/mbackschat/a12/dm/adapter/A12ReportStreamTest.kt"));
    private static final Pattern WORKSPACE_PREPARATION_LOCK =
            Pattern.compile("^Workspace.*Test\\.java$");

    private static final int SECTIONS = 14;
    /** A KERNEL-FINDINGS heading's section tag(s): {@code ## §9 supplement}, {@code ### §6 supplement}, {@code ## §11/§12 supplement}. */
    private static final Pattern KF_TAG = Pattern.compile("^#{2,3} §(\\d+)(?:/§(\\d+))? ", Pattern.MULTILINE);
    /** A backticked token that names a test class. */
    private static final Pattern CLASS_TOKEN = Pattern.compile("`([A-Z][A-Za-z0-9]*Test)`");
    /** A relative markdown link target (external links skipped). */
    private static final Pattern MD_LINK = Pattern.compile("\\]\\(([^)#\\s]+)(?:#[^)]*)?\\)");
    /** A well-formed corpus section tag. */
    private static final Pattern SECTION_TAG = Pattern.compile("§(1[0-4]|[1-9])");

    // ---------------------------------------------------------------- the real inputs, derived live

    @Test
    void theMapResolvesAgainstEveryLiveSurface() throws IOException {
        List<String> violations = check(Files.readString(MAP), liveInputs());
        assertTrue(violations.isEmpty(), () -> "docs/SEMANTICS-MAP.md is out of step with the live surfaces:\n"
                + String.join("\n", violations));
    }

    /** Each row's Area equals the canonical KERNEL-SEMANTICS section title (in-repo, always-on). */
    @Test
    void theAreaTitlesMatchTheCanonicalSiblingHeadings() throws IOException {
        Map<Integer, String> canonical = new TreeMap<>();
        Matcher m = Pattern.compile("^## (\\d+)\\. (.+?)\\s*$", Pattern.MULTILINE)
                .matcher(Files.readString(KERNEL_SEMANTICS));
        while (m.find()) {
            canonical.put(Integer.parseInt(m.group(1)), m.group(2));
        }
        assertEquals(SECTIONS, canonical.size(), "the canonical taxonomy no longer has 14 numbered sections");

        List<String> violations = new ArrayList<>();
        for (Map.Entry<Integer, List<String>> row : navRows(Files.readString(MAP)).entrySet()) {
            String area = row.getValue().get(1).trim();
            String want = canonical.get(row.getKey());
            if (!area.equals(want)) {
                violations.add("§" + row.getKey() + " area is `" + area + "` but KERNEL-SEMANTICS titles it `" + want + "`");
            }
        }
        assertTrue(violations.isEmpty(), () -> String.join("\n", violations));
    }

    private static Inputs liveInputs() throws IOException {
        // KERNEL-FINDINGS §-tags.
        Set<Integer> kfTags = new TreeSet<>();
        Matcher tag = KF_TAG.matcher(Files.readString(KERNEL_FINDINGS));
        while (tag.find()) {
            kfTags.add(Integer.parseInt(tag.group(1)));
            if (tag.group(2) != null) {
                kfTags.add(Integer.parseInt(tag.group(2)));
            }
        }

        // Live facet dimensions = the union of `semantics` keys over the catalog.
        Set<String> dims = new TreeSet<>();
        JsonNode catalog = new ObjectMapper().readTree(Files.readString(CATALOG));
        catalog.path("operators").forEach(op -> op.path("semantics").fieldNames().forEachRemaining(dims::add));

        // Corpus: § → families, from each case's meta.sections; tags must be well-formed.
        Map<Integer, Set<String>> corpus = new TreeMap<>();
        List<String> corpusProblems = new ArrayList<>();
        try (Stream<Path> files = Files.walk(CORPUS_CASES)) {
            for (Path caseFile : files.filter(p -> p.toString().endsWith(".json")).toList()) {
                String family = caseFile.getParent().getFileName().toString();
                for (JsonNode s : new ObjectMapper().readTree(Files.readString(caseFile)).path("meta").path("sections")) {
                    if (!SECTION_TAG.matcher(s.asText()).matches()) {
                        corpusProblems.add(caseFile + " carries a malformed meta.sections tag `" + s.asText()
                                + "` (must be §1..§14 — the KERNEL-SEMANTICS taxonomy)");
                    } else {
                        corpus.computeIfAbsent(Integer.parseInt(s.asText().substring(1)), k -> new TreeSet<>()).add(family);
                    }
                }
            }
        }

        // Every lock source's *Test class names (file stems — one top-level class per file by convention).
        Set<String> treeClasses = new TreeSet<>();
        for (Path lock : EXPLICIT_LOCK_FILES) {
            assertTrue(Files.isRegularFile(lock), "the declared lock file disappeared: " + lock);
            String name = lock.getFileName().toString();
            treeClasses.add(name.substring(0, name.lastIndexOf('.')));
        }
        for (Path tree : LOCK_TREES) {
            try (Stream<Path> files = Files.walk(tree)) {
                files.map(p -> p.getFileName().toString())
                        .filter(n -> n.endsWith("Test.java") || n.endsWith("Test.kt"))
                        .map(n -> n.substring(0, n.lastIndexOf('.')))
                        .forEach(treeClasses::add);
            }
        }
        try (Stream<Path> files = Files.walk(ADAPTER_TESTS)) {
            files.map(p -> p.getFileName().toString())
                    .filter(n -> WORKSPACE_PREPARATION_LOCK.matcher(n).matches())
                    .map(n -> n.substring(0, n.lastIndexOf('.')))
                    .forEach(treeClasses::add);
        }
        return new Inputs(kfTags, dims, corpus, treeClasses, MAP.getParent());
    }

    // ---------------------------------------------------------------- the pure checker

    record Inputs(Set<Integer> kfTags, Set<String> facetDims, Map<Integer, Set<String>> corpusSections,
                  Set<String> treeClasses, Path linkBase) {
    }

    /** All violations of the map against the derived inputs (empty = the map is in step). */
    static List<String> check(String map, Inputs in) {
        List<String> violations = new ArrayList<>();

        Map<Integer, List<String>> nav = navRows(map);
        Map<Integer, List<String>> locks = lockRows(map);
        for (int s = 1; s <= SECTIONS; s++) {
            if (!nav.containsKey(s)) {
                violations.add("the navigation table has no §" + s + " row");
            }
            if (!locks.containsKey(s)) {
                violations.add("the locks table has no §" + s + " row");
            }
        }

        // KERNEL-FINDINGS column (nav cell 2): non-empty iff the doc carries a §n-tagged heading.
        for (Map.Entry<Integer, List<String>> row : nav.entrySet()) {
            boolean claims = !isEmptyCell(row.getValue().get(2));
            boolean has = in.kfTags().contains(row.getKey());
            if (claims && !has) {
                violations.add("§" + row.getKey() + " claims KERNEL-FINDINGS supplements but no §"
                        + row.getKey() + "-tagged heading exists");
            }
            if (!claims && has) {
                violations.add("§" + row.getKey() + " shows no KERNEL-FINDINGS supplements but §"
                        + row.getKey() + "-tagged headings exist");
            }
        }

        // Facets column (nav cell 3): cited dimension names == the live catalog dimensions, exactly.
        Set<String> citedDims = new TreeSet<>();
        for (List<String> row : nav.values()) {
            backticked(row.get(3)).forEach(citedDims::add);
        }
        for (String d : citedDims) {
            if (!in.facetDims().contains(d)) {
                violations.add("the map cites facet dimension `" + d + "` which the live catalog does not carry");
            }
        }
        for (String d : in.facetDims()) {
            if (!citedDims.contains(d)) {
                violations.add("live facet dimension `" + d + "` appears nowhere in the map");
            }
        }

        // Corpus column (nav cell 4): each row equals the derived family set for that section.
        for (Map.Entry<Integer, List<String>> row : nav.entrySet()) {
            Set<String> cited = new TreeSet<>(backticked(row.getValue().get(4)));
            Set<String> derived = in.corpusSections().getOrDefault(row.getKey(), Set.of());
            if (!cited.equals(derived)) {
                violations.add("§" + row.getKey() + " corpus families are " + cited
                        + " but the cases' meta.sections derive " + derived);
            }
        }

        // Lock classes: every cited class exists; every tree class is cited somewhere in the map.
        Set<String> citedClasses = new TreeSet<>();
        Matcher c = CLASS_TOKEN.matcher(map);
        while (c.find()) {
            citedClasses.add(c.group(1));
        }
        for (String cited : citedClasses) {
            if (!in.treeClasses().contains(cited)) {
                violations.add("`" + cited + "` is cited but no such class exists in the configured lock sources");
            }
        }
        for (String cls : in.treeClasses()) {
            if (!citedClasses.contains(cls)) {
                violations.add("lock class `" + cls + "` appears nowhere in the map — place it in its section"
                        + " row or the outside-the-taxonomy table");
            }
        }

        // Relative links resolve.
        Matcher link = MD_LINK.matcher(map);
        while (link.find()) {
            String target = link.group(1);
            if (target.startsWith("http")) {
                continue;
            }
            if (!Files.exists(in.linkBase().resolve(target))) {
                violations.add("link target `" + target + "` does not resolve");
            }
        }
        return violations;
    }

    /** The navigation table's §-keyed rows (5 content cells: §, area, findings, facets, corpus). */
    private static Map<Integer, List<String>> navRows(String map) {
        return sectionRows(map, 5);
    }

    /** The locks table's §-keyed rows (4 content cells: §, validate/laws, adapter/laws, interpreter). */
    private static Map<Integer, List<String>> lockRows(String map) {
        return sectionRows(map, 4);
    }

    private static Map<Integer, List<String>> sectionRows(String map, int cells) {
        Map<Integer, List<String>> rows = new LinkedHashMap<>();
        for (String line : map.lines().toList()) {
            if (!line.startsWith("| §")) {
                continue;
            }
            String[] raw = line.substring(1, line.length() - 1).split("\\|", -1);
            if (raw.length != cells) {
                continue;
            }
            Matcher m = Pattern.compile("^§(\\d+)$").matcher(raw[0].trim());
            if (m.matches()) {
                rows.put(Integer.parseInt(m.group(1)), Stream.of(raw).map(String::trim).toList());
            }
        }
        return rows;
    }

    private static boolean isEmptyCell(String cell) {
        return cell.isBlank() || cell.equals("—");
    }

    private static List<String> backticked(String cell) {
        List<String> out = new ArrayList<>();
        Matcher m = Pattern.compile("`([^`]+)`").matcher(cell);
        while (m.find()) {
            out.add(m.group(1));
        }
        return out;
    }

    // ---------------------------------------------------------------- does-fire proofs

    private static Inputs fakeInputs() {
        return new Inputs(
                new TreeSet<>(java.util.stream.IntStream.rangeClosed(1, 14).boxed().toList()),
                Set.of("emptyOperand"),
                Map.of(5, Set.of("comparison")),
                Set.of("RealLawsTest"),
                Path.of("."));
    }

    private static String fakeMap(String navCells, String lockCells) {
        StringBuilder sb = new StringBuilder();
        sb.append("| § | Area | KERNEL-FINDINGS | Catalog facets | Corpus families |\n|---|---|---|---|---|\n");
        for (int s = 1; s <= SECTIONS; s++) {
            sb.append("| §").append(s).append(s == 5 ? navCells : " | x | §-tagged | `emptyOperand` | — |").append('\n');
        }
        sb.append("\n| § | validate/laws | adapter/laws | interpreter |\n|---|---|---|---|\n");
        for (int s = 1; s <= SECTIONS; s++) {
            sb.append("| §").append(s).append(s == 5 ? lockCells : " | — | — | — |").append('\n');
        }
        return sb.toString();
    }

    @Test
    void firesOnAStaleClassAWrongCorpusCellAndAnUnplacedTreeClass() {
        String map = fakeMap(" | x | §-tagged | `emptyOperand` | `clock` |", " | `BogusLawsTest` | — | — |");
        List<String> violations = check(map, fakeInputs());
        assertTrue(violations.stream().anyMatch(v -> v.contains("`BogusLawsTest`") && v.contains("no such class")),
                () -> "the stale class must fire: " + violations);
        assertTrue(violations.stream().anyMatch(v -> v.contains("§5 corpus families")),
                () -> "the wrong corpus cell must fire (clock cited, comparison derived): " + violations);
        assertTrue(violations.stream().anyMatch(v -> v.contains("`RealLawsTest`") && v.contains("appears nowhere")),
                () -> "the unplaced tree class must fire: " + violations);
    }

    @Test
    void firesOnAMissingSectionRowAndAnUnknownFacetDimension() {
        String map = fakeMap(" | x | §-tagged | `bogusDim` | `comparison` |", " | `RealLawsTest` | — | — |")
                .replace("| §14 | x | §-tagged | `emptyOperand` | — |\n", "");
        List<String> violations = check(map, fakeInputs());
        assertTrue(violations.stream().anyMatch(v -> v.contains("no §14 row")),
                () -> "the missing row must fire: " + violations);
        assertTrue(violations.stream().anyMatch(v -> v.contains("`bogusDim`")),
                () -> "the unknown dimension must fire: " + violations);
    }

    @Test
    void passesOnAMapInStepWithItsInputs() {
        String map = fakeMap(" | x | §-tagged | `emptyOperand` | `comparison` |", " | `RealLawsTest` | — | — |");
        assertEquals(List.of(), check(map, fakeInputs()));
    }
}
