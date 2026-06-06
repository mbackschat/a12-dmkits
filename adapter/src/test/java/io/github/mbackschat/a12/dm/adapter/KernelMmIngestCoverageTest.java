package io.github.mbackschat.a12.dm.adapter;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import org.junit.jupiter.api.Test;

/**
 * MODELKIT spike — Q1: measure F10 ingest coverage over the real {@code a12-kernel-mm} metamodel
 * rules. Read-only over the EUPL-licensed sibling clone (or {@code -Da12.kernel.mm.dir=...}); skips
 * when absent. NOT a regression gate — it prints a coverage report (Converted vs Opaque, plus an
 * Opaque-reason histogram = the F10 tail) so the spike memo can be grounded in a measured number.
 * Asserts only that the harness actually exercised the canonical set.
 */
class KernelMmIngestCoverageTest {

    @Test
    void measuresIngestCoverageAcrossTheCanonicalMetamodels() throws IOException {
        Path root = locateKernelMm();
        assumeTrue(root != null, "a12-kernel-mm not present — skipping spike coverage harness");

        int totalRules = 0, converted = 0, opaque = 0;
        List<String> loadFailures = new ArrayList<>();
        Map<String, Integer> opaqueReasons = new TreeMap<>();       // construct → count (the F10 tail)
        Map<String, int[]> perModel = new LinkedHashMap<>();        // model → {rules, converted, opaque}
        Map<String, Integer> distinctReasons = new TreeMap<>();   // full reason → count (fine-grained triage)

        for (Path file : canonicalModels(root)) {
            String name = file.getParent().getFileName().toString();
            try {
                String json = Files.readString(file, StandardCharsets.UTF_8);
                Map<String, Ingest> outcomes = KernelAdapter.ofJson(json).ingestAll();
                int rules = 0, conv = 0, opq = 0;
                for (Ingest ingest : outcomes.values()) {
                    rules++;
                    if (ingest instanceof Ingest.Converted) {
                        conv++;
                    } else if (ingest instanceof Ingest.Opaque o) {
                        opq++;
                        opaqueReasons.merge(normalizeReason(o.reason()), 1, Integer::sum);
                        distinctReasons.merge(o.reason(), 1, Integer::sum);
                    }
                }
                totalRules += rules; converted += conv; opaque += opq;
                perModel.put(name, new int[] {rules, conv, opq});
            } catch (Exception e) {
                loadFailures.add(name + ": " + e.getClass().getSimpleName() + " " + e.getMessage());
            }
        }

        double pct = totalRules == 0 ? 0 : 100.0 * converted / totalRules;
        StringBuilder report = new StringBuilder("\n=== kernel-mm ingest coverage (MODELKIT spike Q1) ===\n");
        perModel.forEach((m, t) -> report.append(String.format("  %-34s %4d rules  %4d converted  %4d opaque%n",
                m, t[0], t[1], t[2])));
        report.append(String.format("%n  TOTAL: %d rules across %d models  →  %d converted (%.1f%%), %d opaque%n",
                totalRules, perModel.size(), converted, pct, opaque));
        report.append("\n  Opaque reasons (the F10 tail, by frequency):\n");
        opaqueReasons.entrySet().stream()
                .sorted((a, b) -> Integer.compare(b.getValue(), a.getValue()))
                .forEach(e -> report.append(String.format("    %4d  %s%n", e.getValue(), e.getKey())));
        report.append("\n  Distinct full opaque reasons (triage, by frequency):\n");
        distinctReasons.entrySet().stream()
                .sorted((a, b) -> Integer.compare(b.getValue(), a.getValue()))
                .forEach(e -> report.append(String.format("    %4d  · %s%n", e.getValue(), e.getKey())));
        if (!loadFailures.isEmpty()) {
            report.append("\n  Models that failed to load via the adapter path:\n");
            loadFailures.forEach(f -> report.append("    ").append(f).append('\n'));
        }
        System.out.println(report);

        assertTrue(perModel.size() >= 10,
                () -> "expected to ingest the canonical metamodel set, loaded " + perModel.size() + report);
        // Regression floor: F-IDX (name-path keying) + boolean/confirm/!= + enum field-to-field + the
        // field-list×value-list quantifiers (constant AND entity-valued) + NumberOfFilledFields/Groups +
        // … + root-group presence + arithmetic operands took foreign-rule ingest from 0 → ~90% (496/553).
        // Lock it so a path-keying or converter regression is loud here (opt-in: only runs where the
        // kernel-mm clone is present).
        int finalConverted = converted;
        assertTrue(finalConverted >= 494,
                () -> "kernel-mm ingest coverage regressed below the floor (converted="
                        + finalConverted + " of " + 553 + ")" + report);
    }

    /** Collapse an Opaque reason to the construct that caused it, so similar causes group in the histogram. */
    private static String normalizeReason(String reason) {
        if (reason == null) {
            return "(no reason)";
        }
        int node = reason.indexOf("kernel node:");
        if (node >= 0) {                                     // clean F10 tail — an unmodeled construct
            String rest = reason.substring(node + "kernel node:".length()).trim();
            int sp = rest.indexOf(' ');
            return "[unmodeled construct] kernel node: " + (sp > 0 ? rest.substring(0, sp) : rest);
        }
        int path = reason.indexOf("no element at path:");
        if (path >= 0) {                                     // resolution gap — bucket by the path's first 2 segments
            String p = reason.substring(path + "no element at path:".length()).trim();
            String[] seg = p.split("/");
            String bucket = seg.length >= 3 ? "/" + seg[1] + "/" + seg[2] + "/…" : p;
            return "[unresolved path] " + bucket;
        }
        return "[other] " + (reason.length() > 70 ? reason.substring(0, 70) + "…" : reason);
    }

    /** First existing of the system-property dir, then the sibling-clone candidates. */
    private static Path locateKernelMm() {
        List<String> candidates = new ArrayList<>();
        String prop = System.getProperty("a12.kernel.mm.dir");
        if (prop != null) {
            candidates.add(prop);
        }
        candidates.add("../a12-kernel-mm/documentModels");
        candidates.add("../../a12-kernel-mm/documentModels");
        for (String c : candidates) {
            Path p = Path.of(c);
            if (Files.isDirectory(p)) {
                return p;
            }
        }
        return null;
    }

    /** Newest non-previous file per directory, preferring the a12- variant, else codegen-, else none-. */
    private static List<Path> canonicalModels(Path root) throws IOException {
        List<Path> result = new ArrayList<>();
        try (var dirs = Files.list(root)) {
            for (Path dir : dirs.filter(Files::isDirectory).sorted().toList()) {
                Path best = null;
                int bestRank = Integer.MAX_VALUE;
                try (var files = Files.list(dir)) {
                    for (Path f : files.sorted().toList()) {
                        String name = f.getFileName().toString();
                        if (!name.endsWith(".json") || name.contains("-previous")) {
                            continue;
                        }
                        int rank = name.startsWith("a12-") ? 0 : name.startsWith("codegen-") ? 1 : 2;
                        if (rank < bestRank) {
                            bestRank = rank;
                            best = f;
                        }
                    }
                }
                if (best != null) {
                    result.add(best);
                }
            }
        }
        return result;
    }
}
