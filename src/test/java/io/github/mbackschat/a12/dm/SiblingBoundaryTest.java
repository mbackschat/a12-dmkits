package io.github.mbackschat.a12.dm;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * Guards the sibling boundary (ARCHITECTURE "the umbrella", 2026-06-09): {@code dm.modelkit} (structure)
 * and {@code dm.rulekit} (the DSL) are <em>siblings</em> over the shared {@code dm.core} substrate —
 * <b>neither may import the other</b>. When D10's compiler-enforced module wall was dropped (modelkit
 * folded into the root module so it can use {@code dm.core} + the kernel), this package-level import
 * sweep became what holds the boundary. A failure means a kit reached sideways into its peer; route the
 * shared concern through {@code dm.core} instead.
 */
class SiblingBoundaryTest {

    private static final Path SRC = Path.of("src/main/java/io/github/mbackschat/a12/dm");

    @Test
    void modelkitDoesNotImportRulekit() {
        assertNoImportsInto(SRC.resolve("modelkit"), "io.github.mbackschat.a12.dm.rulekit");
    }

    @Test
    void rulekitDoesNotImportModelkit() {
        assertNoImportsInto(SRC.resolve("rulekit"), "io.github.mbackschat.a12.dm.modelkit");
    }

    private static void assertNoImportsInto(Path root, String forbiddenPrefix) {
        List<String> offenders = new ArrayList<>();
        try (var paths = Files.walk(root)) {
            paths.filter(p -> p.toString().endsWith(".java")).forEach(p -> {
                try {
                    for (String line : Files.readAllLines(p)) {
                        String t = line.strip();
                        if (t.startsWith("import ") && t.contains(forbiddenPrefix)) {
                            offenders.add(root.relativize(p) + " → " + t);
                        }
                    }
                } catch (IOException e) {
                    throw new UncheckedIOException("failed to read " + p, e);
                }
            });
        } catch (IOException e) {
            throw new UncheckedIOException("failed to walk " + root.toAbsolutePath()
                    + " (run from the module root)", e);
        }
        assertTrue(offenders.isEmpty(),
                () -> "sibling boundary violated — these files import " + forbiddenPrefix
                        + " (siblings must not depend on each other; route shared code through dm.core):\n  "
                        + String.join("\n  ", offenders));
    }
}
