package io.github.mbackschat.a12.dm.cli;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;

/**
 * Keeps each public release's local infographic copy identical to the canonical source asset and locks the linked
 * scaled-preview contract in the READMEs and regeneration source. GitHub must be able to render each preview and
 * open the full PNG without depending on the private source repository or a mutable external URL.
 */
class InfographicAssetGuardTest {

    private static final Path ROOT = Path.of("..");
    private static final Path CANONICAL = ROOT.resolve("assets/infographics");
    private static final Path SPECIFICATION = ROOT.resolve("docs/INFOGRAPHICS.md");
    private static final Pattern REGISTRY_ROW = Pattern.compile(
            "\\|\\s*[^|]+\\s*\\|\\s*`([^`]+\\.png)`\\s*\\|\\s*\\[[^]]+]\\(([^)]+\\.md)\\)\\s*\\|"
                    + "\\s*`([^`]+\\.png)`\\s*\\|\\s*`?(\\d+)`?\\s*\\|");

    @Test
    void everyPlacementUsesTheExpectedPreviewLinkedToTheFullPng() throws IOException {
        for (var placement : placements()) {
            var document = Files.readString(placement.document());
            var relative = placement.relativePath();
            assertTrue(document.contains("<a href=\"" + relative + "\">"),
                    () -> placement.document() + " does not link to the full infographic " + relative);
            var imageStart = document.indexOf("<img src=\"" + relative + "\"");
            var imageEnd = imageStart < 0 ? -1 : document.indexOf('>', imageStart);
            assertTrue(imageStart >= 0 && imageEnd >= 0
                            && document.substring(imageStart, imageEnd)
                                    .contains("width=\"" + placement.previewWidth() + "\""),
                    () -> placement.document() + " does not render the expected preview for " + relative);
        }
    }

    @Test
    void canonicalAssetsArePngsRegisteredAndEveryLinkedCopyIsByteIdentical() throws IOException {
        var placements = placements();
        var registered = placements.stream().map(Placement::fileName).collect(Collectors.toSet());
        try (var assets = Files.list(CANONICAL)) {
            var actual = assets.filter(path -> path.getFileName().toString().endsWith(".png"))
                    .map(path -> path.getFileName().toString())
                    .collect(Collectors.toSet());
            assertTrue(actual.equals(registered),
                    () -> "canonical infographic assets and the INFOGRAPHICS.md registry differ: actual=" + actual
                            + ", registered=" + registered);
        }

        var placementKeys = new HashSet<String>();
        var documented = new HashSet<String>();
        for (var placement : placements) {
            var canonical = CANONICAL.resolve(placement.fileName());
            assertTrue(Files.isRegularFile(canonical), () -> "missing canonical infographic: " + canonical);
            assertTrue(isPng(canonical), () -> "infographic is not a PNG: " + canonical);
            assertTrue(placementKeys.add(placement.document() + "\0" + placement.relativePath()),
                    () -> "duplicate infographic registry placement: " + placement.document() + " -> "
                            + placement.relativePath());
            if (placement.document().normalize().equals(SPECIFICATION.normalize())) {
                documented.add(placement.fileName());
            }

            var linkedAsset = placement.document().getParent().resolve(placement.relativePath()).normalize();
            assertTrue(Files.isRegularFile(linkedAsset), () -> "missing linked infographic: " + linkedAsset);
            if (!linkedAsset.equals(canonical.normalize())) {
                assertArrayEquals(Files.readAllBytes(canonical), Files.readAllBytes(linkedAsset),
                        () -> linkedAsset + " has drifted from " + canonical);
            }
        }
        assertTrue(documented.equals(registered),
                () -> "every canonical infographic needs an accepted-output preview in INFOGRAPHICS.md: documented="
                        + documented + ", registered=" + registered);
    }

    @Test
    void performancePromptTracksThePublicPerformanceReport() throws IOException {
        var report = Files.readString(ROOT.resolve("interpreter/public-release/PERFORMANCE.md"));
        var specification = Files.readString(ROOT.resolve("docs/INFOGRAPHICS.md"));
        assertTrue(specification.contains("## Synchronization contract"),
                "the regeneration source must explain how mutable measurements stay synchronized");

        var stressRow = tableRow(report, "10,000 rows — stress");
        var interpreterJvm = tableRow(report, "**Interpreter — JVM**");
        var generatedJavaJvm = tableRow(report, "A12 Kernel, generated Java — JVM");
        var dynamicGroovyJvm = tableRow(report, "A12 Kernel, dynamic Groovy — JVM");
        var defaultInventory = tableRow(report, "Default inventory, 15 cases");
        var repeatedDocuments = tableRow(report, "Repeated-document evaluation");
        var hugeDocument = tableRow(report, "Huge-document edge, 20,000 rows");
        var hardestCase = tableRow(report, "Hardest case — 384 concentrated low-firing rules");
        var interpreterMemory = tableRow(report, "**Interpreter** — prepared model");
        var generatedJavaMemory = tableRow(report, "A12 Kernel, generated Java");
        var dynamicGroovyMemory = tableRow(report, "A12 Kernel, dynamic Groovy");

        var comparison = Pattern.compile(
                        "interpreter is about \\*\\*(\\d+×) the A12 Kernel's generated Java\\*\\* and about "
                                + "\\*\\*(\\d+×) its dynamic Groovy\\*\\*")
                .matcher(report);
        assertTrue(comparison.find(), "missing current JVM comparison ratios in public performance report");

        var evidence = Pattern.compile("<sub>Measured ([^·]+) · ([^·]+) · ([^·]+) · ([^·]+) ·")
                .matcher(report);
        assertTrue(evidence.find(), "missing dated measurement evidence in public performance report");

        var expectedPromptLines = List.of(
                "- Section title: \"WARM VALIDATION • " + plain(stressRow[1]).replace(" — stress", "")
                        + " • " + plain(stressRow[2]) + " findings\"",
                "- Bar value: \"" + plain(interpreterJvm[2]) + "\"",
                "- Bar value: \"" + plain(generatedJavaJvm[2]) + "\"",
                "- Bar value: \"" + plain(dynamicGroovyJvm[2]) + "\"",
                "- Callout: \"≈" + comparison.group(1) + " faster vs generated Java\"",
                "- Callout: \"≈" + comparison.group(2) + " faster vs dynamic Groovy\"",
                "- Card value: \"" + plain(defaultInventory[2]) + " faster\"",
                "- Card value: \"" + plain(repeatedDocuments[2]) + " faster\"",
                "- Card value: \"" + plain(hardestCase[2]) + " faster\"",
                "- Card value: \"" + plain(hugeDocument[2]) + " faster\"",
                "- Memory value: \"" + plain(interpreterMemory[2]) + "\"",
                "- Memory value: \"" + plain(generatedJavaMemory[2]) + "\"",
                "- Memory callout: \"" + plain(generatedJavaMemory[3]) + "\"",
                "- Memory value: \"" + plain(dynamicGroovyMemory[2]) + "\"",
                "- Memory callout: \"" + plain(dynamicGroovyMemory[3]) + "\"",
                "- Browser value: \"" + plain(tableRow(report, "200 rows — representative")[3])
                        .replace(" median", "") + " first answer\"",
                "- Browser value: \"" + plain(tableRow(report, "200 rows — representative")[4]) + " warm\"",
                "- Bottom evidence: \"Measured " + evidence.group(1).trim() + " • " + evidence.group(2).trim()
                        + " • " + evidence.group(4).trim() + " • " + evidence.group(3).trim() + "\"");

        for (var expectedPromptLine : expectedPromptLines) {
            assertTrue(specification.contains(expectedPromptLine),
                    () -> "performance infographic prompt has drifted from its public source: " + expectedPromptLine);
        }
    }

    @Test
    void languageSizePromptTracksTheGeneratedRootProductionStatistics() throws IOException {
        var readme = Files.readString(ROOT.resolve("README.md"));
        var specification = Files.readString(SPECIFICATION);
        var statistics = section(readme, "<!-- source-statistics:root:start -->", "### Verification");

        var javaCode = Long.parseLong(plain(tableRow(statistics, "Java")[4]));
        var kotlinCode = Long.parseLong(plain(tableRow(statistics, "Kotlin")[4]));
        var expectedPromptLines = List.of(
                "- Java size: \"" + kloc(javaCode) + "\"",
                "- Kotlin size: \"" + kloc(kotlinCode) + "\"");

        for (var expectedPromptLine : expectedPromptLines) {
            assertTrue(specification.contains(expectedPromptLine),
                    () -> "language-comparison infographic prompt has drifted from generated source statistics: "
                            + expectedPromptLine);
        }
    }

    /**
     * <b>The cell beside those counts enumerates modules, and it is exactly as volatile with no binding at
     * all.</b> Measured 2026-09-20: while the kLOC row above was guarded and merely stale, the Kotlin
     * {@code STRUCTURE} cell read {@code "interpreter • adapter laws (tests)"} — it named one of five Kotlin
     * production modules and then named <em>tests</em>, contradicting both the poster's own production-only
     * caption and the prompt's explicit "do not imply that adapter laws are production Kotlin" constraint
     * twenty lines below it. Nothing could see that, because a count has an owning table and a prose cell
     * does not.
     *
     * <p>So this binds the cell to the repository rather than to a table. It asserts the two things that
     * failed and nothing more, because a guard demanding an exact module list would force a poster label
     * nobody can read: the cell must name every <em>major</em> Kotlin production module, and it must not
     * describe production Kotlin in test vocabulary. "Major" is a share of the Kotlin production files rather
     * than a pinned list, so a new leaf module does not fail the build while a new engine does — which is the
     * only case where the poster is actually wrong.
     */
    @Test
    void theLanguageStructureCellNamesEveryMajorKotlinProductionModule() throws IOException {
        var cell = languageStructureCell(Files.readString(SPECIFICATION), "Kotlin");

        for (var module : majorKotlinProductionModules()) {
            assertTrue(cell.contains(module), () ->
                    "the language infographic's Kotlin STRUCTURE cell does not name the major Kotlin production "
                    + "module '" + module + "'. A reader is shown a composition the repository contradicts, and "
                    + "no count guard can see it. Cell: \"" + cell + "\"");
        }
        assertTrue(TEST_VOCABULARY.stream().noneMatch(cell::contains), () ->
                "the language infographic's Kotlin STRUCTURE cell describes production Kotlin in test "
                + "vocabulary, which the poster's own production-only caption and its \"do not imply that "
                + "adapter laws are production Kotlin\" constraint both forbid. Cell: \"" + cell + "\"");
    }

    /**
     * Does-fire proof on the exact text that shipped, because the live prompt is green and a green assertion
     * proves only that it ran. Both arms must fire on {@code "interpreter • adapter laws (tests)"}: it omits
     * {@code consistency} — 136 of the 274 Kotlin production files — and it names tests.
     */
    @Test
    void theStructureCellGuardFiresOnTheCellThatShipped() throws IOException {
        var shipped = "interpreter • adapter laws (tests)";

        assertTrue(majorKotlinProductionModules().stream().anyMatch(module -> !shipped.contains(module)),
                "the major-module arm must reject the cell that shipped, or it is checking nothing");
        assertTrue(TEST_VOCABULARY.stream().anyMatch(shipped::contains),
                "the test-vocabulary arm must reject the cell that shipped, or it is checking nothing");
    }

    /** Words that describe verification, which the production-only caption forbids in this cell. */
    private static final List<String> TEST_VOCABULARY = List.of("test", "laws", "fixture");

    /** The share of Kotlin production files above which a module must be named on the poster. */
    private static final double MAJOR_MODULE_SHARE = 0.10;

    /**
     * Kotlin production modules carrying at least {@link #MAJOR_MODULE_SHARE} of the estate's Kotlin
     * production files. Derived from the source tree, so it tracks a module being added or emptied. Only
     * {@code main}/{@code commonMain}/{@code jvmMain}/{@code jsMain} count — the caption says production.
     */
    private static List<String> majorKotlinProductionModules() throws IOException {
        var perModule = new java.util.TreeMap<String, Long>();
        try (var modules = Files.list(ROOT)) {
            for (var module : modules.filter(Files::isDirectory).toList()) {
                var count = kotlinProductionFiles(module);
                if (count > 0) {
                    perModule.put(module.getFileName().toString(), count);
                }
            }
        }
        var total = perModule.values().stream().mapToLong(Long::longValue).sum();
        assertTrue(total > 0, "found no Kotlin production source at all, so this scan broke");
        return perModule.entrySet().stream()
                .filter(entry -> entry.getValue() >= total * MAJOR_MODULE_SHARE)
                .map(java.util.Map.Entry::getKey)
                .toList();
    }

    private static long kotlinProductionFiles(Path module) throws IOException {
        long count = 0;
        for (var sourceSet : List.of("main", "commonMain", "jvmMain", "jsMain")) {
            var root = module.resolve("src").resolve(sourceSet).resolve("kotlin");
            if (!Files.isDirectory(root)) {
                continue;
            }
            try (var files = Files.walk(root)) {
                count += files.filter(Files::isRegularFile)
                        .filter(file -> file.toString().endsWith(".kt"))
                        .count();
            }
        }
        return count;
    }

    /** The value of one column's {@code STRUCTURE} line in the language-comparison prompt. */
    private static String languageStructureCell(String specification, String language) {
        var prefix = "- " + language + " structure: \"";
        var start = specification.indexOf(prefix);
        assertTrue(start >= 0, () -> "the language infographic prompt has no '" + prefix + "…' line");
        var from = start + prefix.length();
        return specification.substring(from, specification.indexOf('"', from));
    }

    private static boolean isPng(Path file) throws IOException {
        var bytes = Files.readAllBytes(file);
        return bytes.length >= 8
                && bytes[0] == (byte) 0x89
                && bytes[1] == 0x50
                && bytes[2] == 0x4e
                && bytes[3] == 0x47
                && bytes[4] == 0x0d
                && bytes[5] == 0x0a
                && bytes[6] == 0x1a
                && bytes[7] == 0x0a;
    }

    private static List<Placement> placements() throws IOException {
        var specification = Files.readString(SPECIFICATION);
        var start = specification.indexOf("<!-- infographic-registry:start -->");
        var end = specification.indexOf("<!-- infographic-registry:end -->");
        assertTrue(start >= 0 && end > start, "INFOGRAPHICS.md must contain the guarded registry markers");

        var matcher = REGISTRY_ROW.matcher(specification.substring(start, end));
        var placements = new java.util.ArrayList<Placement>();
        while (matcher.find()) {
            placements.add(new Placement(
                    Path.of(matcher.group(1)).getFileName().toString(),
                    SPECIFICATION.getParent().resolve(matcher.group(2)).normalize(),
                    matcher.group(3),
                    Integer.parseInt(matcher.group(4))));
        }
        assertTrue(!placements.isEmpty(), "INFOGRAPHICS.md registry contains no parseable placements");
        return List.copyOf(placements);
    }

    private static String[] tableRow(String markdown, String label) {
        var row = markdown.lines()
                .filter(line -> line.startsWith("|") && line.contains("| " + label + " |"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("missing performance-report table row: " + label));
        return row.split("\\|", -1);
    }

    private static String plain(String cell) {
        return cell.trim().replace("**", "");
    }

    private static String section(String document, String startMarker, String endMarker) {
        var start = document.indexOf(startMarker);
        var end = document.indexOf(endMarker, start + startMarker.length());
        assertTrue(start >= 0 && end > start,
                () -> "missing bounded document section: " + startMarker + " -> " + endMarker);
        return document.substring(start, end);
    }

    private static String kloc(long codeLines) {
        return String.format(java.util.Locale.ROOT, "%.1f kLOC", codeLines / 1000.0);
    }

    private record Placement(String fileName, Path document, String relativePath, int previewWidth) {
    }
}
