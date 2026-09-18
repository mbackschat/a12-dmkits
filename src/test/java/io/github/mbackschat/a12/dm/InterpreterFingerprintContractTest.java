package io.github.mbackschat.a12.dm;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;

import org.junit.jupiter.api.Test;

/**
 * <b>H4's mechanical discriminator exists, and covers the production closure rather than a convenient subset.</b>
 *
 * <p>The portable-consistency plan specifies it: "the comparison that decides whether a checker-only edit
 * requires an interpreter sweep is the interpreter's resolved production dependency set plus the digest of its
 * built artifacts", reusing {@code generateInterpreterReleaseChecksums} for the digest half and adding the
 * resolved-configuration half beside it. It was prose for the length of the plan and nothing pointed at it,
 * which is the shape "Advertise nothing you cannot point at" names — so this is the locus that fails.
 *
 * <p><b>Why a text lock rather than a behavioural one.</b> The task is a Groovy closure on a project, with no
 * seam a JUnit test can drive, exactly as {@code PurityGuardMechanismTest} records for the purity arms. The
 * behavioural oracle is the task's own double render, which this test requires it to keep: a fingerprint that
 * is not reproducible cannot answer the question it exists for, and a single render cannot tell.
 *
 * <p><b>What it deliberately does not assert:</b> the fingerprint's VALUE, or that any two commits agree. Those
 * are measurements and belong in the dated results log, not in a guard that would then have to be edited every
 * time the interpreter legitimately changes.
 */
class InterpreterFingerprintContractTest {

    private static final Checkout CHECKOUT = Checkout.locate();

    /**
     * The four PRODUCTION configurations, and the reason the set is spelled out rather than derived: a sweep
     * that silently dropped one would still render a fingerprint, and two commits differing only in the
     * dropped configuration would compare equal — a false "no sweep needed", which is the one wrong answer
     * this discriminator must never give.
     */
    private static final String[] PRODUCTION_CONFIGURATIONS = {
        "jvmCompileClasspath", "jvmRuntimeClasspath", "jsCompileClasspath", "jsRuntimeClasspath",
    };

    @Test
    void theFingerprintTaskCoversEveryProductionConfiguration() throws IOException {
        String build = Files.readString(CHECKOUT.resolve("interpreter/build.gradle"));
        int task = build.indexOf("interpreterProductionFingerprint");
        assertTrue(task >= 0,
                "interpreter/build.gradle declares no interpreterProductionFingerprint task, so H4's "
                        + "mechanical discriminator is still the prose the plan describes");

        String body = fingerprintBody(build);
        for (String configuration : PRODUCTION_CONFIGURATIONS) {
            assertTrue(body.contains(configuration),
                    "the fingerprint omits the production configuration " + configuration
                            + ", so two commits differing only there would compare equal and report that no "
                            + "interpreter sweep is needed");
        }
    }

    @Test
    void theFingerprintExcludesTestConfigurationsAndReusesTheReleaseManifest() throws IOException {
        String body = fingerprintBody(Files.readString(CHECKOUT.resolve("interpreter/build.gradle")));

        assertTrue(!body.contains("TestCompileClasspath") && !body.contains("TestRuntimeClasspath"),
                "the fingerprint resolves a TEST configuration. H4 is about what SHIPS: a test-only dependency "
                        + "bump would then demand an interpreter sweep that cannot change a delivered byte");

        assertTrue(body.contains("interpreterChecksums") || body.contains("SHA256SUMS"),
                "the fingerprint does not reuse generateInterpreterReleaseChecksums' manifest for the "
                        + "artifact-digest half — the plan says H4 'does not introduce a new digest mechanism'");
    }

    @Test
    void theFingerprintProvesItsOwnReproducibility() throws IOException {
        String body = fingerprintBody(Files.readString(CHECKOUT.resolve("interpreter/build.gradle")));

        assertTrue(body.contains("is not deterministic"),
                "the fingerprint renders once and trusts the result. A comparison across two checkouts is only "
                        + "evidence if the render is reproducible, and a single render cannot tell an unstable "
                        + "fingerprint from a real difference — render twice and refuse on a mismatch, as "
                        + "generateInterpreterReleaseChecksums already does");
    }

    /** The task's own declaration, bounded by the next top-level {@code def }, so a later task cannot satisfy it. */
    private static String fingerprintBody(String build) {
        int start = build.indexOf("def interpreterProductionFingerprint");
        assertTrue(start >= 0, "no `def interpreterProductionFingerprint` declaration in interpreter/build.gradle");
        int next = build.indexOf("\ndef ", start + 1);
        return next < 0 ? build.substring(start) : build.substring(start, next);
    }
}
