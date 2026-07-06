package io.github.mbackschat.a12.dm.cli.interp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

import io.github.mbackschat.a12.dm.interpreter.load.ModelLoader;
import io.github.mbackschat.a12.dm.interpreter.model.ModelDef;

/**
 * Loader parity: the multiplatform {@link ModelLoader} (:interpreter {@code commonMain}) must produce the
 * <b>exact same</b> {@link ModelDef} as {@link InterpreterModelLoader#load} over every real fixture model —
 * across both roots, {@code cli/src/test/resources/models} and {@code examples/models}. Written <i>before</i>
 * {@code InterpreterModelLoader} delegated, it proved the port faithful (kotlinx tree API vs Jackson); it is
 * kept <i>after</i> the switch as the delegation guard — the JVM loader must never diverge from the engine's.
 *
 * <p>It reads repo model files at runtime, so it runs in the {@code docGuardTest} task, which declares both
 * model roots as its inputs (scoped re-run, no false-green — CLAUDE.md testing discipline).
 */
class ModelLoaderParityTest {

    @TestFactory
    Stream<DynamicTest> newLoaderMatchesTheJvmLoaderOverEveryFixture() throws IOException {
        List<Path> models = new ArrayList<>();
        for (String root : List.of("cli/src/test/resources/models", "examples/models")) {
            try (Stream<Path> walk = Files.walk(repoDir(root))) {
                walk.filter(Files::isRegularFile)
                        .filter(p -> p.getFileName().toString().endsWith(".json"))
                        .forEach(models::add);
            }
        }
        assertTrue(models.size() >= 25, "expected many fixture models across both roots, found " + models.size());
        return models.stream().sorted().map(p -> DynamicTest.dynamicTest(p.toString(), () -> {
            String json = Files.readString(p);
            ModelDef viaCommon = ModelLoader.load(json);
            ModelDef viaJvm = InterpreterModelLoader.load(json);
            assertEquals(viaJvm, viaCommon, "loader parity for " + p);
        }));
    }

    /** Locate a repo-root directory from the {@code :cli} module working dir (mirrors CliTourFreshnessTest). */
    private static Path repoDir(String relative) {
        for (String base : List.of("..", ".", "../..")) {
            Path p = Path.of(base, relative);
            if (Files.exists(p)) {
                return p;
            }
        }
        throw new IllegalStateException(relative + " not found from " + Path.of("").toAbsolutePath());
    }
}
