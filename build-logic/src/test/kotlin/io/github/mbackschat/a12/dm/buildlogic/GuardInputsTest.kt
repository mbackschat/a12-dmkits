package io.github.mbackschat.a12.dm.buildlogic

import java.io.File
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class GuardInputsTest {

    @Test
    fun `a declared file covers itself`() {
        assertTrue(GuardInputs.isCovered("docs/README.md", setOf("docs/README.md")))
    }

    @Test
    fun `a read inside a declared directory is covered`() {
        assertTrue(GuardInputs.isCovered("docs/nested/deep-note.md", setOf("docs")))
    }

    @Test
    fun `a directory a declared file lies within is covered`() {
        // A guard resolves a directory in order to walk it while Gradle resolves a fileTree to its files, so
        // neither side ever names the other exactly.
        assertTrue(GuardInputs.isCovered("docs", setOf("docs/README.md")))
    }

    @Test
    fun `a sibling prefix must not count as coverage`() {
        // The mutant this case exists for: dropping the separator makes `docsX/` read as covered by a
        // declared `docs`, and the guard's lane then goes UP-TO-DATE on an edit it never sees.
        assertFalse(GuardInputs.isCovered("docsX/README.md", setOf("docs")))
        assertFalse(GuardInputs.isCovered("docs", setOf("docsX/README.md")))
    }

    @Test
    fun `an unrelated read is not covered`() {
        assertFalse(GuardInputs.isCovered("scripts/perf.sh", setOf("docs", "examples")))
    }

    @Test
    fun `undeclared reads are deduplicated and sorted`() {
        val reads = listOf("scripts/b.sh", "docs/x.md", "scripts/a.sh", "scripts/b.sh")
        assertEquals(
            listOf("scripts/a.sh", "scripts/b.sh"),
            GuardInputs.undeclaredReads(reads, setOf("docs")),
        )
    }

    @Test
    fun `no undeclared read is no refusal`() {
        assertNull(GuardInputs.undeclaredRefusal("docGuardTest", emptyList()))
        assertNull(GuardInputs.undeclaredReads(listOf("docs/a.md"), setOf("docs")).let {
            GuardInputs.undeclaredRefusal("docGuardTest", it)
        })
    }

    @Test
    fun `the undeclared refusal names the task, the count and every path`() {
        val refusal = GuardInputs.undeclaredRefusal("docGuardTest", listOf("scripts/a.sh", "scripts/b.sh"))
        requireNotNull(refusal)
        assertTrue(refusal.startsWith("docGuardTest: 2 repo path(s)"), refusal)
        assertTrue(refusal.contains("scripts/a.sh"), refusal)
        assertTrue(refusal.contains("scripts/b.sh"), refusal)
    }

    @Test
    fun `an empty declared tree is refused by name`() {
        assertNull(GuardInputs.emptyInputRefusal("docGuardTest", emptyList()))
        val refusal = GuardInputs.emptyInputRefusal("docGuardTest", listOf("the docs corpus"))
        requireNotNull(refusal)
        assertTrue(refusal.contains("the docs corpus"), refusal)
        assertTrue(refusal.contains("EMPTY or MISSING"), refusal)
    }

    @Test
    fun `a single declared file is empty when it does not exist, whatever the collection says`(
        @org.junit.jupiter.api.io.TempDir dir: File,
    ) {
        val present = File(dir, "there.md").apply { writeText("x") }
        val absent = File(dir, "gone.md")
        // The lambda stands for the FileCollection, which resolves a declared File whether or not it is
        // there — so it answers `false` in BOTH rows and cannot be what decides them.
        assertFalse(GuardInputs.declaredEntryIsEmpty(present) { false })
        assertTrue(GuardInputs.declaredEntryIsEmpty(absent) { false })
        // Anything that is not a single File defers to the collection.
        assertTrue(GuardInputs.declaredEntryIsEmpty("a file tree") { true })
        assertFalse(GuardInputs.declaredEntryIsEmpty("a file tree") { false })
        // A null value is a declaration someone forgot to finish; it must name itself as empty rather than
        // reaching Gradle and failing there.
        assertTrue(GuardInputs.declaredEntryIsEmpty(null) { false })
    }

    @Test
    fun `relativize produces forward-slashed repository-relative paths`() {
        val root = Path.of("/repo")
        assertEquals("docs/README.md", GuardInputs.relativize(root, File("/repo/docs/README.md")))
    }
}
