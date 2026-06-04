package io.github.mbackschat.a12.rulekit.model;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

/**
 * Opt-in integration harness over the {@code a12-kernel-mm} metamodels — instrument 3 of the
 * constraint-mining discipline and the model-index hardening test (corpus analysis: the kernel
 * team's own, rule-dense, typedef-heavy models). Read-only: the EUPL-licensed models are read from
 * the sibling clone (or {@code -Da12.kernel.mm.dir=...}), never copied; the test skips when absent.
 *
 * <p>Canonical set: per logical model directory, the newest non-{@code -previous} file, preferring
 * the {@code a12-} render variant, else {@code codegen-}, else {@code none-}. Verified 2026-06-04:
 * none of the 59 files carries {@code modelReferences} — all are self-contained, so no filesystem
 * resolver is needed (corrects the earlier corpus-analysis assumption).
 */
class KernelMmIntegrationTest {

    private static final String DEFAULT_DIR = "../a12-kernel-mm/documentModels";

    @Test
    void everyCanonicalMetamodelLoadsAndIndexes() throws IOException {
        Path root = Path.of(System.getProperty("a12.kernel.mm.dir", DEFAULT_DIR));
        assumeTrue(Files.isDirectory(root), "a12-kernel-mm not present — skipping opt-in harness");

        List<String> failures = new ArrayList<>();
        int models = 0;
        int fields = 0;
        Map<ValueKind, Integer> kinds = new EnumMap<>(ValueKind.class);

        for (Path file : canonicalModels(root)) {
            models++;
            try (Reader reader = Files.newBufferedReader(file)) {
                ModelIndex index = ModelIndex.of(ModelLoader.loadSelfContained(reader));
                for (String path : index.fieldPaths().toList()) {
                    fields++;
                    kinds.merge(index.kindOf(path), 1, Integer::sum);
                }
                index.groupPaths().forEach(index::isRepeating);   // exercise group classification
                index.locales();                                  // and the locale accessor
            } catch (Exception e) {
                failures.add(file.getFileName() + ": " + e.getMessage());
            }
        }

        System.out.println("kernel-mm harness: " + models + " canonical models, " + fields
                + " fields, kinds=" + kinds);
        assertTrue(models >= 10, "expected the canonical metamodel set, found " + models);
        assertTrue(failures.isEmpty(), () -> "metamodels failed to load/index:\n" + String.join("\n", failures));
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
