package io.github.mbackschat.a12.dm;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/** Locks purity gates to compiled dependencies, where comment and literal delimiters cannot hide code. */
class PurityGuardMechanismTest {

    private static final Checkout CHECKOUT = Checkout.locate();

    @Test
    void purityGatesDoNotApproximateEitherSourceLanguageWithCommentRegexes() throws IOException {
        for (String relative : new String[] {
                "build.gradle", "interpreter-bridge/build.gradle", "schemakit/build.gradle",
                "consistency/build.gradle", "consistency-gate/build.gradle",
                "interpreter-preflight/build.gradle"}) {
            String build = Files.readString(CHECKOUT.resolve(relative));
            assertTrue(build.contains("verifyCompiledPurity"), relative);
            assertFalse(build.contains("replaceAll(/(?s)\\/\\*.*?\\*\\//"), relative);
            assertFalse(build.contains("=~ /^\\s*import\\s+"), relative + " still matches imports by regex — a "
                    + "fully-qualified use, a Kotlin source, or a name in a text block escapes that reading");
        }
    }

    /**
     * <b>The blind spot no purity gate can cover.</b> Compiled edges answer what our classes LINK; a kernel jar
     * that a published module merely declares is unlinked, invisible to jdeps, and on every consumer's
     * classpath regardless — so the claim a consumer actually cares about needs a resolution-level guard
     * (DEV-API-GAPS DG45). Asserted here because the two mechanisms are one contract split across two
     * instruments, and dropping either leaves the other reading as complete.
     */
    @Test
    void theKernelFreeClaimAlsoHasAResolutionGuardBecauseLinkageCannotSeeAnUnusedDependency() throws IOException {
        String build = Files.readString(CHECKOUT.resolve("build.gradle"));
        assertTrue(build.contains("verifyKernelFreeClasspath"), "the resolution-level guard must exist");
        assertTrue(build.contains("resolutionResult"),
                "it must read RESOLVED dependencies — a declared-only kernel jar is exactly what it is for");
        assertTrue(Files.readString(CHECKOUT.resolve("consistency/build.gradle"))
                        .contains("verifyKernelFreeClasspath"),
                ":consistency is published for npm consumers under H2, so its declared dependencies decide what "
                        + "they install — the compiled gate alone cannot see a dependency nothing imports");
    }

    /**
     * <b>A closed claim has to be closed over the route that never enters the graph.</b> The resolution guard
     * reads {@code resolutionResult}, and a {@code files(…)} dependency is not a component there at all — so
     * it is invisible by denominator rather than by filter, which no allowlist can fix. Measured 2026-09-18:
     * with a source directory planted on {@code :consistency}'s {@code jvmMain}, the guard reported BUILD
     * SUCCESSFUL. Asserted on the script's text because the arm is a Groovy closure on the root project with
     * no seam a JUnit test can drive; the does-fire evidence is the planted control recorded in
     * {@code docs/PORTABLE-CONSISTENCY-IMPLEMENTATION-PLAN.md}.
     */
    @Test
    void theClosedClaimAlsoRefusesAFileDependencyBecauseNoResolutionFilterCanClassifyOne() throws IOException {
        String build = Files.readString(CHECKOUT.resolve("build.gradle"));
        assertTrue(build.contains("FileCollectionDependency"),
                "the closed-claim arm must refuse a file dependency — it reaches a consumer's classpath while "
                        + "naming neither a coordinate nor a project path, so every other arm is blind to it");
        assertTrue(build.contains("permittedBuildRoots"),
                "it must discriminate by the OWNING BUILD DIRECTORY: the Kotlin plugin itself puts a module's "
                        + "own main output on its test classpaths this way, so refusing the declaration SHAPE "
                        + "would refuse the plugin");
    }

    @Test
    void bridgePlantKeepsDelimiterTextNextToAForbiddenNameDecoy() throws IOException {
        Path plant = CHECKOUT.resolve(
                "interpreter-bridge/src/test/kotlin/io/github/mbackschat/a12/dm/bridge/PurityGuardPlant.kt");
        String source = Files.readString(plant);
        assertTrue(source.contains("/*"), "the regression plant must contain the delimiter that fooled the regex");
        assertTrue(source.contains("com.mgmtp.plant.Decoy"), "the plant must keep the forbidden-name decoy after it");
    }
}
