package io.github.mbackschat.a12.dm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The invariants {@link Checkout} advertises, each asserted rather than assumed. */
class CheckoutTest {

    private static final Checkout CHECKOUT = Checkout.locate();

    @Test
    void theCheckoutRootIsNotReachableThroughResolve() {
        for (String naming : List.of("", "   ", ".", "..", "../..", "./")) {
            assertThrows(IllegalArgumentException.class, () -> CHECKOUT.resolve(naming),
                    () -> "resolve(\"" + naming + "\") must not hand back the root or a path outside it — the "
                            + "absent root accessor is what makes a repository-root walk unwritable");
        }
    }

    @Test
    void resolveReachesAPathInsideTheCheckout() {
        Path docs = CHECKOUT.resolve("docs");
        assertTrue(docs.endsWith("docs"), () -> docs + " should be the checkout's docs directory");
        assertEquals("docs/README.md", CHECKOUT.relative(CHECKOUT.resolve("docs/README.md")));
    }

    /**
     * The defect the single definition exists to close: the narrowest per-guard spelling of "test sources"
     * matched none of the interpreter's Kotlin-Multiplatform test sources.
     */
    @Test
    void testSourcesReachEveryTestSourceSetIncludingTheMultiplatformOnes() {
        List<String> relative = CHECKOUT.testSources().stream().map(CHECKOUT::relative).toList();

        assertTrue(relative.stream().anyMatch(p -> p.startsWith("src/test/")),
                "the root module's test source set must be in scope");
        assertTrue(relative.stream().anyMatch(p -> p.startsWith("cli/src/test/")),
                "a module test source set must be in scope");
        for (String multiplatform : List.of("interpreter/src/commonTest/", "interpreter/src/jvmTest/")) {
            assertTrue(relative.stream().anyMatch(p -> p.startsWith(multiplatform)),
                    () -> multiplatform + " must be in scope — a per-guard `src/test` predicate matched none of "
                            + "these, so a census over it never saw the interpreter's test estate");
        }
    }

    @Test
    void everyDenominatorIsNonEmptyAndSourcesContainTheTestSources() {
        assertFalse(CHECKOUT.sources().isEmpty(), "sources()");
        assertFalse(CHECKOUT.markdown().isEmpty(), "markdown()");
        assertFalse(CHECKOUT.tracked("docs").isEmpty(), "tracked(docs)");
        assertTrue(CHECKOUT.sources().containsAll(CHECKOUT.testSources()),
                "test sources are sources; a disagreement here means the two predicates have drifted");
    }

    @Test
    void aDenominatorThatWouldBeEmptyIsRefusedRatherThanReturned() {
        // `tracked` resolves first, so a directory inside the checkout that tracks nothing is the reachable
        // vacuity: the refusal is what stops a scan over it from passing by reading nothing.
        assertThrows(IllegalStateException.class, () -> CHECKOUT.tracked("docs/README.md/nothing-here"),
                "an empty denominator must be refused, not returned");
    }

    @Test
    void commandRunsWithTheCheckoutAsItsWorkingDirectory() throws Exception {
        Process process = CHECKOUT.command(List.of("git", "rev-parse", "--show-toplevel"))
                .redirectErrorStream(true)
                .start();
        String reported = new String(process.getInputStream().readAllBytes()).strip();
        assertEquals(0, process.waitFor(), "git must run successfully in the checkout");
        assertTrue(CHECKOUT.resolve("settings.gradle").startsWith(Path.of(reported).toRealPath()),
                () -> "command ran in " + reported + ", which is not the checkout this instance describes");
    }

    @Test
    void trackedIsScopedToItsSubdirectory() {
        assertTrue(CHECKOUT.tracked("cli").stream().map(CHECKOUT::relative).allMatch(p -> p.startsWith("cli/")),
                "tracked(subdirectory) must not leak outside it");
    }
}
