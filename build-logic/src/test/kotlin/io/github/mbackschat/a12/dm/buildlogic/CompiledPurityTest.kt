package io.github.mbackschat.a12.dm.buildlogic

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class CompiledPurityTest {

    private val jdepsOutput = """
        rulekit.jar -> java.base
           io.github.Foo    -> java.lang.Object      java.base
           io.github.Foo    -> com.mgmtp.kernel.Bar  not found
           io.github.Baz    -> java.util.List        java.base
    """.trimIndent()

    @Test
    fun `only the arrow's right-hand side is read as a dependency`() {
        // jdeps' per-archive summary line has the same shape as a class edge, so its right-hand side — a
        // module name — is read as a dependency too. It cannot satisfy a positive control (a package prefix
        // never matches a module name) and can only ever ADD a correctly-directed violation, so it is left
        // in rather than filtered out.
        assertEquals(
            listOf("java.base", "java.lang.Object", "com.mgmtp.kernel.Bar", "java.util.List"),
            CompiledPurity.dependencies(jdepsOutput),
        )
    }

    @Test
    fun `a line with no arrow contributes nothing`() {
        assertEquals(emptyList<String>(), CompiledPurity.dependencies("rulekit.jar\n\n   \nsplit package"))
    }

    @Test
    fun `a scan over no classes is refused rather than reported clean`() {
        val refusal = CompiledPurity.zeroClassesRefusal("kit purity", 0)
        requireNotNull(refusal)
        assertTrue(refusal.contains("ZERO class files"), refusal)
        assertNull(CompiledPurity.zeroClassesRefusal("kit purity", 1))
    }

    @Test
    fun `a toolchain with no jdeps beside its launcher is refused as such`(@TempDir dir: java.io.File) {
        val launcher = java.io.File(dir, "bin/java").apply { parentFile.mkdirs(); writeText("") }
        val present = java.io.File(dir, "bin/jdeps").apply { writeText("") }
        assertNull(CompiledPurity.missingJdepsRefusal("kit purity", present, launcher))
        val refusal =
            CompiledPurity.missingJdepsRefusal("kit purity", java.io.File(dir, "bin/absent"), launcher)
        requireNotNull(refusal)
        assertTrue(refusal.contains("cannot find jdeps beside"), refusal)
    }

    @Test
    fun `a non-zero jdeps exit is refused and carries its output`() {
        assertNull(CompiledPurity.inspectionFailedRefusal("kit purity", 0, "ignored"))
        val refusal = CompiledPurity.inspectionFailedRefusal("kit purity", 2, "error: bad option")
        requireNotNull(refusal)
        assertTrue(refusal.contains("error: bad option"), refusal)
    }

    @Test
    fun `a missing positive control is refused`() {
        val deps = CompiledPurity.dependencies(jdepsOutput)
        assertNull(CompiledPurity.missingPositiveControlRefusal("kit purity", deps, "java.lang.Object"))
        val refusal =
            CompiledPurity.missingPositiveControlRefusal("kit purity", deps, "javax.annotation.Nullable")
        requireNotNull(refusal)
        assertTrue(refusal.contains("javax.annotation.Nullable"), refusal)
    }

    @Test
    fun `forbidden packages match by prefix, deduplicated and sorted`() {
        val deps = listOf("com.mgmtp.b.Z", "com.mgmtp.a.Y", "com.mgmtp.b.Z", "java.util.List")
        assertEquals(
            listOf("com.mgmtp.a.Y", "com.mgmtp.b.Z"),
            CompiledPurity.violations(deps, listOf("com.mgmtp.")),
        )
    }

    @Test
    fun `a package that merely shares a prefix start is still matched, so the prefix must be exact`() {
        // `com.mgmtp.` with the trailing dot is what the callers pass; `com.mgmtpx` must not match it.
        assertEquals(emptyList<String>(), CompiledPurity.violations(listOf("com.mgmtpx.Y"), listOf("com.mgmtp.")))
    }

    @Test
    fun `no violation is no refusal, and a violation names every offender`() {
        assertNull(CompiledPurity.violationRefusal("kit purity", emptyList()))
        val refusal = CompiledPurity.violationRefusal("kit purity", listOf("com.mgmtp.a.Y", "groovy.lang.X"))
        requireNotNull(refusal)
        assertTrue(refusal.contains("com.mgmtp.a.Y"), refusal)
        assertTrue(refusal.contains("groovy.lang.X"), refusal)
    }
}
