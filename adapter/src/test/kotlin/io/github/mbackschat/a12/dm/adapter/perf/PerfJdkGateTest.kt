package io.github.mbackschat.a12.dm.adapter.perf

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The JVM gate on perf measurement: **OpenJDK 21 and nothing else** (owner rule, 2026-08-23).
 *
 * Why this is a gate rather than a note. On 2026-08-19 `brew install openjdk@21` displaced Oracle GraalVM 21.0.12
 * as the build JDK, because the pin was `java_home -v 21`, which returns the highest-versioned JDK 21. The same
 * commit then measured **105.88 ms** under OpenJDK against **66.07 ms** under GraalVM, on the same machine on the
 * same day, breaching `charset-grapheme`'s floor — and it was read as an interpreter regression until a
 * same-commit control separated the variables. Across all 91 comparable lanes the swap moved 45 (median 1.15x)
 * and made 10 *faster*, which is what a JIT change looks like and what no anchor tolerance can absorb.
 *
 * The identity is deliberately FAMILY + MAJOR. `java.version` cannot carry this: GraalVM 21 reports `21.0.12` and
 * Homebrew OpenJDK reports `21.0.12.1`, so the strings differ by coincidence, not by family — and conversely a
 * patch-exact rule would redden CI on every Temurin bump for a difference that moves nothing.
 */
class PerfJdkGateTest {

    private fun graalvm() = JdkIdentity.of("Java HotSpot(TM) 64-Bit Server VM", "21.0.12+7-LTS-jvmci-23.1-b96", "21")
    private fun homebrew() = JdkIdentity.of("OpenJDK 64-Bit Server VM", "21.0.12.1", "21")
    private fun temurin() = JdkIdentity.of("OpenJDK 64-Bit Server VM", "21.0.14+7", "21")

    @Test
    fun `GraalVM is recognised by jvmci, not by its Oracle-branded VM name`() {
        // The tell that makes vendor-sniffing wrong: GraalVM's java.vm.name reads as stock HotSpot.
        assertEquals(JdkIdentity("GraalVM", "21"), graalvm())
        assertEquals(JdkIdentity("OpenJDK", "21"), homebrew())
    }

    @Test
    fun `an OpenJDK 21 is accepted whoever built it`() {
        assertNull(JdkIdentity.refusalFor(homebrew()), "Homebrew OpenJDK 21 is the local build JDK")
        assertNull(JdkIdentity.refusalFor(temurin()), "Temurin 21 is what CI and the nightly perf job run")
    }

    @Test
    fun `a GraalVM is REFUSED, and the refusal names it and says how to fix the toolchain`() {
        val refusal = JdkIdentity.refusalFor(graalvm())
        assertNotNull(refusal, "a GraalVM must be refused")
        refusal!!
        assertTrue(refusal.contains("GraalVM 21"), "the refusal must name what it found: $refusal")
        assertTrue(refusal.contains("gradle-environment.sh"), "and point at the pin, not at the anchors: $refusal")
    }

    @Test
    fun `a wrong MAJOR is refused even on OpenJDK`() {
        val refusal = JdkIdentity.refusalFor(JdkIdentity.of("OpenJDK 64-Bit Server VM", "25.0.1", "25"))
        assertNotNull(refusal, "a wrong major must be refused")
        refusal!!
        assertTrue(refusal.contains("OpenJDK 25"), "the refusal must name the major it found: $refusal")
    }

    @Test
    fun `an unrecognised VM keeps its own name rather than passing as OpenJDK`() {
        // The silent-accept failure mode: bucketing an unknown VM into the allowed family would let the next
        // compiler swap through exactly as this one got through.
        val exotic = JdkIdentity.of("Eclipse OpenJ9 VM", "openj9-0.46.0", "21")
        assertEquals(JdkIdentity("Eclipse OpenJ9 VM", "21"), exotic)
        assertNotNull(JdkIdentity.refusalFor(exotic), "an unrecognised VM must be refused")
    }

    @Test
    fun `the JVM actually running this suite satisfies the gate`() {
        // The control that keeps the five cases above from being a fixture exercise: if the pin regresses, this
        // is the assertion that notices, and it is the same call the record seam makes.
        assertNull(
            JdkIdentity.refusalFor(JdkIdentity.current),
            "this suite is running on ${JdkIdentity.current.text}, which the perf gate refuses",
        )
    }
}
