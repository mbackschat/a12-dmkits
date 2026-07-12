package io.github.mbackschat.a12.dm.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledIfSystemProperty;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/**
 * Public-API surface lock for the five published Java kits — a golden-diff <em>visibility</em> guard
 * (Set S; {@code docs/RELEASE-SPEC.md} §11, {@code docs/TESTING-SPEC.md} "structural-law guardians").
 *
 * <p>The kits publish to Maven ({@code io.github.mbackschat.a12.dm:dmkits-*}); once a consumer builds against
 * them, a change to a public signature is a change to their expectations. At 0.x a break is <em>allowed</em>,
 * so this is <b>not</b> a japicmp-style "fail on any incompatibility" gate — it is the project's established
 * golden-guard pattern ({@code DocSnippetFreshnessTest}, the schema {@code examples}): the committed golden is
 * the reviewed truth, the guard fails on any drift, and the author regenerates deliberately so the change
 * surfaces as a reviewable diff. {@link PublicApiDumper} produces the dump; this class asserts and, under
 * {@code -Dapi.fix=true} ({@code ./gradlew dumpKitApi}), regenerates. Verifier and fixer share one dump path,
 * so they cannot disagree; CI never runs the fixer, so a forgotten regenerate still fails the build.
 *
 * <p><b>Placement.</b> This guard reads the committed goldens under {@code api/} at runtime, so it runs in the
 * dedicated {@code apiGuardTest} task with {@code api/} declared as <em>its</em> inputs (never on the broad
 * suite) — a golden edit re-runs only this guard and can't restore from the build cache into a false green
 * (TESTING-SPEC "Tests that read repo files at runtime"). A kit's public API changing invalidates the task via
 * its compiled classes already on the test classpath.
 */
class PublicApiGuardTest {

    /** A published kit: its Maven artifact id (= golden file stem) and its Gradle module directory. */
    private record Kit(String artifact, String moduleDir) {}

    private static final List<Kit> KITS = List.of(
            new Kit("dmkits-core", "core"),
            new Kit("dmkits-rulekit", "rulekit"),
            new Kit("dmkits-modelkit", "modelkit"),
            new Kit("dmkits-edit", "edit"),
            new Kit("dmkits-schemakit", "schemakit"));

    @Test
    @DisabledIfSystemProperty(named = "api.fix", matches = "true")
    void everyKitPublicApiMatchesItsGolden() throws IOException {
        for (Kit k : KITS) {
            Path golden = goldenFile(k);
            assertTrue(Files.exists(golden), () ->
                    "missing API golden " + repoRoot().relativize(golden) + " — generate it once with "
                    + "`./gradlew dumpKitApi`, then commit it.");
            String live = PublicApiDumper.dump(mainClasses(k), classLoader());
            String committed = Files.readString(golden);
            assertEquals(committed, live, () ->
                    "The public API of " + k.artifact() + " has DRIFTED from its golden "
                    + repoRoot().relativize(golden) + ".\n"
                    + "At 0.x a public-surface change is allowed, but it must be DELIBERATE and REVIEWED: "
                    + "regenerate the goldens with `./gradlew dumpKitApi` and review the resulting diff in the "
                    + "same change (this is a visibility guard, not a no-break promise — docs/RELEASE-SPEC.md §11).");
        }
    }

    /**
     * Determinism is the whole game: a flaky dump makes the guard noise. Dumping twice in one JVM catches the
     * common non-determinism (unsorted reflection iteration); the regenerate-twice-diff check covers the rest.
     */
    @Test
    @DisabledIfSystemProperty(named = "api.fix", matches = "true")
    void theDumperIsDeterministic() {
        for (Kit k : KITS) {
            String first = PublicApiDumper.dump(mainClasses(k), classLoader());
            String second = PublicApiDumper.dump(mainClasses(k), classLoader());
            assertEquals(first, second, () ->
                    "PublicApiDumper is non-deterministic for " + k.artifact()
                    + " — two dumps of the same classes differ, so the golden guard would be flaky.");
        }
    }

    /**
     * The autonomous fixer — only under {@code -Dapi.fix=true} (i.e. {@code ./gradlew dumpKitApi}). Rewrites
     * every kit golden in place from the live dump; never runs in CI (so a forgotten regenerate still fails).
     */
    @Test
    @EnabledIfSystemProperty(named = "api.fix", matches = "true")
    void regenerateGoldens() throws IOException {
        Path dir = repoRoot().resolve("api");
        Files.createDirectories(dir);
        for (Kit k : KITS) {
            Files.writeString(goldenFile(k), PublicApiDumper.dump(mainClasses(k), classLoader()));
        }
    }

    private static Path goldenFile(Kit k) {
        return repoRoot().resolve("api").resolve(k.artifact() + ".api");
    }

    private static Path mainClasses(Kit k) {
        return repoRoot().resolve(k.moduleDir()).resolve("build/classes/java/main");
    }

    private static ClassLoader classLoader() {
        return PublicApiGuardTest.class.getClassLoader();
    }

    /** The repo root — the nearest ancestor holding {@code settings.gradle} (robust to the module working dir). */
    private static Path repoRoot() {
        Path p = Path.of("").toAbsolutePath();
        while (p != null && !Files.exists(p.resolve("settings.gradle"))) {
            p = p.getParent();
        }
        if (p == null) {
            throw new IllegalStateException("repo root (settings.gradle) not found from " + Path.of("").toAbsolutePath());
        }
        return p;
    }
}
