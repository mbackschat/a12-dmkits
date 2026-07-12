package io.github.mbackschat.a12.dm.adapter;
import io.github.mbackschat.a12.dm.rulekit.read.ReadComputation;
import io.github.mbackschat.a12.dm.rulekit.read.ReadResult;

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
 * MODELKIT spike — Q1: measure F10 read coverage over the real {@code a12-kernel-mm} metamodel
 * rules. Read-only over the EUPL-licensed sibling clone (or {@code -Da12.kernel.mm.dir=...}); skips
 * when absent. NOT a regression gate — it prints a coverage report (Converted vs Opaque, plus an
 * Opaque-reason histogram = the F10 tail) so the spike memo can be grounded in a measured number.
 * Asserts only that the harness actually exercised the canonical set.
 */
class KernelMmReadCoverageTest {

    @Test
    void measuresReadCoverageAcrossTheCanonicalMetamodels() throws IOException {
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
                Map<String, ReadResult> outcomes = KernelAdapter.ofJson(json).readAll();
                int rules = 0, conv = 0, opq = 0;
                for (ReadResult read : outcomes.values()) {
                    rules++;
                    if (read instanceof ReadResult.Converted) {
                        conv++;
                    } else if (read instanceof ReadResult.Opaque o) {
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
        StringBuilder report = new StringBuilder("\n=== kernel-mm read coverage (MODELKIT spike Q1) ===\n");
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
                () -> "expected to read the canonical metamodel set, loaded " + perModel.size() + report);
        // Regression floor: F-IDX (name-path keying) + boolean/confirm/!= + enum field-to-field + the
        // field-list×value-list quantifiers (constant AND entity-valued) + NumberOfFilledFields/Groups +
        // custom conditions (→ CustomConditionRef, name-only). Foreign validation-rule read is FULLY
        // typed: 547/547 (100%). The 6 computations kernel-mm ships are excluded here (readAll() is
        // validation rules only) and locked separately at 6/6 by the computation-coverage test below —
        // 547 + 6 = 553 typed elements, the kernel's whole getAllRules(). Lock the floor near the ceiling
        // so any converter/path-keying regression is loud here (opt-in: only runs where the clone exists).
        int finalConverted = converted;
        assertTrue(finalConverted >= 545,
                () -> "kernel-mm validation-rule read coverage regressed below the floor (converted="
                        + finalConverted + " of " + 547 + ")" + report);
    }

    @Test
    void measuresComputationReadCoverageAcrossTheCanonicalMetamodels() throws IOException {
        Path root = locateKernelMm();
        assumeTrue(root != null, "a12-kernel-mm not present — skipping spike coverage harness");

        int total = 0, converted = 0, opaque = 0;
        Map<String, Integer> opaqueReasons = new TreeMap<>();
        Map<String, Integer> distinctReasons = new TreeMap<>();
        Map<String, int[]> perModel = new LinkedHashMap<>();

        for (Path file : canonicalModels(root)) {
            String name = file.getParent().getFileName().toString();
            try {
                String json = Files.readString(file, StandardCharsets.UTF_8);
                Map<String, ReadComputation> outcomes = KernelAdapter.ofJson(json).readAllComputations();
                if (outcomes.isEmpty()) {
                    continue;
                }
                int t = 0, c = 0, o = 0;
                for (ReadComputation ic : outcomes.values()) {
                    t++;
                    if (ic instanceof ReadComputation.Converted) {
                        c++;
                    } else if (ic instanceof ReadComputation.Opaque op) {
                        o++;
                        opaqueReasons.merge(normalizeReason(op.reason()), 1, Integer::sum);
                        distinctReasons.merge(op.reason(), 1, Integer::sum);
                    }
                }
                total += t; converted += c; opaque += o;
                perModel.put(name, new int[] {t, c, o});
            } catch (Exception ignored) {
                // load failures are already reported by the rule-coverage test
            }
        }

        double pct = total == 0 ? 0 : 100.0 * converted / total;
        StringBuilder report = new StringBuilder("\n=== kernel-mm COMPUTATION read coverage ===\n");
        perModel.forEach((m, t) -> report.append(String.format("  %-34s %4d computations  %4d converted  %4d opaque%n",
                m, t[0], t[1], t[2])));
        report.append(String.format("%n  TOTAL: %d computations across %d models  →  %d converted (%.1f%%), %d opaque%n",
                total, perModel.size(), converted, pct, opaque));
        report.append("\n  Opaque reasons (by frequency):\n");
        opaqueReasons.entrySet().stream().sorted((a, b) -> Integer.compare(b.getValue(), a.getValue()))
                .forEach(e -> report.append(String.format("    %4d  %s%n", e.getValue(), e.getKey())));
        report.append("\n  Distinct full opaque reasons (triage):\n");
        distinctReasons.entrySet().stream().sorted((a, b) -> Integer.compare(b.getValue(), a.getValue()))
                .forEach(e -> report.append(String.format("    %4d  · %s%n", e.getValue(), e.getKey())));
        System.out.println(report);

        int finalConverted = converted, finalTotal = total;
        assertTrue(finalTotal >= 6 && finalConverted == finalTotal,
                () -> "kernel-mm computation read should be complete (converted " + finalConverted
                        + " of " + finalTotal + ")" + report);
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
