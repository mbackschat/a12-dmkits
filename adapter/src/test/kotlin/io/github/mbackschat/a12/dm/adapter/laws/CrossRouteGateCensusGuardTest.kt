package io.github.mbackschat.a12.dm.adapter.laws

import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Source census — **every law that applies a kernel result to a destination AND reads the same fixture through
 * the interpreter's typed Document-JSON transport must call [CrossRouteSourceGate.requireRouteAgnostic].**
 *
 * Those two sides come through different A12 ingress routes, and the routes disagree about a source-identical
 * cell for every non-STRING kind ([SourceIdentityIngressRouteDiffTest]). Without the gate such a fixture
 * diverges with a message pointing at whatever the law is about, which is the wrong place to look — the trap
 * that [SeparateDestinationRoutePreconditionTest] measures. Two sites qualify today and both are wired; this
 * exists so a third cannot be added without the gate.
 *
 * **Scope, stated because the census would otherwise look under-inclusive.** It covers the TYPED transport
 * only. Laws that build the interpreter's document by hand (`newDocument()`) are a *different* route with its
 * own semantics — hand-built matches V1 for NUMBER (no retained decimal identity) and the typed route for
 * everything else — and are tracked as `IG143` in `docs/INTERPRETER-GAPS.md` rather than folded in here,
 * because gating them needs that hybrid resolved first.
 */
class CrossRouteGateCensusGuardTest {

    private val laws: List<Path> = repoRoot()
        .resolve("adapter/src/test/kotlin/io/github/mbackschat/a12/dm/adapter/laws")
        .let { dir -> Files.list(dir).use { it.toList() } }
        .filter { it.fileName.toString().endsWith(".kt") }
        // This file's own planted-source literal contains both markers, so it would select itself — and it
        // carries the gate's NAME as a constant, which would let it self-satisfy the census too.
        .filter { it.fileName.toString() != "CrossRouteGateCensusGuardTest.kt" }
        .sorted()

    @Test
    fun everyTypedCrossRouteSiteCallsTheGate() {
        val ungated = laws.filter { qualifies(Files.readString(it)) }
            .filter { !Files.readString(it).contains(GATE) }
            .map { it.fileName.toString() }
        assertEquals(
            emptyList<String>(), ungated,
            "these laws apply a kernel result to a destination and also read the fixture through the "
                + "interpreter's typed transport, but never call $GATE — so a source-identical fixture would "
                + "diverge for an ingress reason and read as a defect in the feature under test",
        )
    }

    /** Anti-vacuity: a census whose denominator collapsed to zero passes while checking nothing. */
    @Test
    fun theCensusFindsTheSitesItIsMeantToCover() {
        val covered = laws.filter { qualifies(Files.readString(it)) }.map { it.fileName.toString() }
        assertTrue(laws.size > 40, "the law directory scan collapsed: ${laws.size} files")
        assertEquals(
            listOf("SeparateDestinationClearDiffTest.kt", "SeparateDestinationEngines.kt"), covered,
            "the two known cross-route sites must be the ones the census selects; a change here means the "
                + "class grew or shrank and the scope note above needs re-reading",
        )
    }

    @Test
    fun theSameCensusRejectsAPlantedUngatedSite() {
        val planted = """
            class Planted {
                fun x() { RuntimeLaws.appliedStatesToDestination(model, source, destination, probe) }
                fun y() { prepared.documents.readDocument(json) }
            }
        """.trimIndent()
        assertTrue(qualifies(planted), "the selector must recognise a cross-route site")
        assertTrue(!planted.contains(GATE), "and the planted site must be ungated, or this proves nothing")

        val gated = planted.replace("fun x() {", "fun x() { $GATE(model, prepared, source);")
        assertTrue(qualifies(gated) && gated.contains(GATE),
            "the negative control: the same site with the gate present must still be selected AND pass")
    }

    private fun qualifies(source: String): Boolean =
        KERNEL_APPLY.any { source.contains(it) } && source.contains("readDocument(")

    private fun repoRoot(): Path {
        var candidate: Path? = Path.of("").toAbsolutePath()
        while (candidate != null) {
            if (Files.exists(candidate.resolve("settings.gradle"))) return candidate
            candidate = candidate.parent
        }
        throw IllegalStateException("cannot locate the repository root from ${Path.of("").toAbsolutePath()}")
    }

    private companion object {
        const val GATE = "requireRouteAgnostic"
        val KERNEL_APPLY = listOf("appliedStatesToDestination(", "appliedDocumentToDestination(")
    }
}
