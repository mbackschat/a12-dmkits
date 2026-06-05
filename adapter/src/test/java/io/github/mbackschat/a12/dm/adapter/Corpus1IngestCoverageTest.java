package io.github.mbackschat.a12.dm.adapter;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

/**
 * MODELKIT spike — Q1 counterpart: ingest coverage over <em>ordinary application</em> document models
 * (Corpus 1, the A12 Tools workspaces) — the representative complement to the rule-dense metamodel
 * measurement, so the metamodel number is interpretable. Proprietary + read-only: gated entirely on
 * {@code -Da12.corpus1.dir=<workspaces dir>}; skips (never runs in CI or on machines without the
 * volume) and commits nothing. Prints a coverage report; asserts nothing about the corpus content.
 */
class Corpus1IngestCoverageTest {

    @Test
    void measuresIngestCoverageOverOrdinaryApplicationModels() throws IOException {
        String dir = System.getProperty("a12.corpus1.dir");
        assumeTrue(dir != null && Files.isDirectory(Path.of(dir)),
                "a12.corpus1.dir not set / absent — skipping proprietary Corpus-1 harness");

        int models = 0, loaded = 0, totalRules = 0, converted = 0, opaque = 0;
        Map<String, Integer> loadFailures = new TreeMap<>();      // bucket → count
        Map<String, Integer> opaqueReasons = new TreeMap<>();
        int cap = 150;

        List<Path> candidates;
        try (Stream<Path> walk = Files.walk(Path.of(dir))) {
            candidates = walk.filter(p -> p.toString().endsWith(".json"))
                    .filter(Corpus1IngestCoverageTest::isDocumentModel)
                    .limit(cap)
                    .toList();
        }

        for (Path file : candidates) {
            models++;
            try {
                String json = Files.readString(file, StandardCharsets.UTF_8);
                Map<String, Ingest> outcomes = KernelAdapter.ofJson(json).ingestAll();
                loaded++;
                for (Ingest ingest : outcomes.values()) {
                    totalRules++;
                    if (ingest instanceof Ingest.Converted) {
                        converted++;
                    } else if (ingest instanceof Ingest.Opaque o) {
                        opaque++;
                        opaqueReasons.merge(bucketReason(o.reason()), 1, Integer::sum);
                    }
                }
            } catch (Exception e) {
                loadFailures.merge(bucketLoadFailure(e.getMessage()), 1, Integer::sum);
            }
        }

        double pct = totalRules == 0 ? 0 : 100.0 * converted / totalRules;
        StringBuilder r = new StringBuilder("\n=== Corpus-1 (ordinary application models) ingest coverage ===\n");
        r.append(String.format("  scanned %d document models; %d loaded via the adapter path%n", models, loaded));
        r.append(String.format("  rules in loaded models: %d  →  %d converted (%.1f%%), %d opaque%n",
                totalRules, converted, pct, opaque));
        r.append("\n  Load failures (models that never reached ingest), by cause:\n");
        loadFailures.entrySet().stream().sorted((a, b) -> Integer.compare(b.getValue(), a.getValue()))
                .forEach(e -> r.append(String.format("    %4d  %s%n", e.getValue(), e.getKey())));
        r.append("\n  Opaque reasons (the F10 tail), by frequency:\n");
        opaqueReasons.entrySet().stream().sorted((a, b) -> Integer.compare(b.getValue(), a.getValue()))
                .limit(25).forEach(e -> r.append(String.format("    %4d  %s%n", e.getValue(), e.getKey())));
        System.out.println(r);
    }

    private static boolean isDocumentModel(Path p) {
        try {
            String head = Files.readString(p, StandardCharsets.UTF_8);
            return head.contains("\"modelType\"") && head.contains("\"document\"")
                    && head.contains("\"modelRoot\"");
        } catch (Exception e) {
            return false;
        }
    }

    private static String bucketLoadFailure(String msg) {
        if (msg == null) {
            return "(no message)";
        }
        String m = msg.toLowerCase();
        if (m.contains("validierungsmodus") || m.contains("validation mode") || m.contains("sprache")
                || m.contains("locale")) {
            return "locale not supported in validation mode (bare en/de — KERNEL-FINDINGS §13)";
        }
        if (m.contains("reference") || m.contains("resolver") || m.contains("expand")
                || m.contains("alias") || m.contains("include")) {
            return "unresolved model include/reference (needs a directory resolver)";
        }
        return "other: " + (msg.length() > 70 ? msg.substring(0, 70) + "…" : msg);
    }

    private static String bucketReason(String reason) {
        if (reason == null) {
            return "(no reason)";
        }
        int node = reason.indexOf("kernel node:");
        if (node >= 0) {
            String rest = reason.substring(node + "kernel node:".length()).trim();
            int sp = rest.indexOf(' ');
            return "[unmodeled construct] " + (sp > 0 ? rest.substring(0, sp) : rest);
        }
        if (reason.contains("no element at path:")) {
            return "[unresolved path]";
        }
        return "[other] " + (reason.length() > 60 ? reason.substring(0, 60) + "…" : reason);
    }
}
