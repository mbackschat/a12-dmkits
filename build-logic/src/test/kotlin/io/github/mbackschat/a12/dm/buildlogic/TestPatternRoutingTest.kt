package io.github.mbackschat.a12.dm.buildlogic

import java.io.File
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class TestPatternRoutingTest {

    private val hostByClass = mapOf(
        "a.b.DocsIndexGuardTest" to "docGuardTest",
        "a.b.PerfGateTest" to "perfTest",
    )

    @Test
    fun `a star is the only wildcard`() {
        assertTrue(TestPatternRouting.matches("*DocsIndexGuardTest", "a.b.DocsIndexGuardTest"))
        assertTrue(TestPatternRouting.matches("a.b.*", "a.b.DocsIndexGuardTest"))
        assertTrue(TestPatternRouting.matches("a.b.DocsIndexGuardTest", "a.b.DocsIndexGuardTest"))
        assertFalse(TestPatternRouting.matches("*PerfGateTest", "a.b.DocsIndexGuardTest"))
    }

    @Test
    fun `a dot in the pattern is literal, not a regex any-character`() {
        assertFalse(TestPatternRouting.matches("a.b.DocsIndexGuardTest", "aXb.DocsIndexGuardTest"))
    }

    @Test
    fun `a trailing method selector still selects its class`() {
        // Gradle's `--tests` accepts a trailing `.method`, so the pattern is probed with it dropped too.
        assertTrue(
            TestPatternRouting.matches("a.b.DocsIndexGuardTest.everyDocIsIndexed", "a.b.DocsIndexGuardTest"),
        )
    }

    @Test
    fun `no pattern selects nothing, and a pattern selects only what it matches`() {
        assertEquals(emptyMap<String, String>(), TestPatternRouting.misrouted(hostByClass, emptyList()))
        assertEquals(
            mapOf("a.b.PerfGateTest" to "perfTest"),
            TestPatternRouting.misrouted(hostByClass, listOf("*PerfGate*")),
        )
    }

    @Test
    fun `a class file becomes its fully qualified name, and a nested class is skipped`(@TempDir dir: File) {
        val root = File(dir, "classes").apply { mkdirs() }
        val pkg = File(root, "a/b").apply { mkdirs() }
        assertEquals("a.b.Foo", TestPatternRouting.fullyQualifiedName(root, File(pkg, "Foo.class")))
        assertNull(TestPatternRouting.fullyQualifiedName(root, File(pkg, "Foo\$Inner.class")))
        assertNull(TestPatternRouting.fullyQualifiedName(root, File(pkg, "Foo.java")))
    }

    @Test
    fun `the corrective names each class and the command that runs it`() {
        val refusal = TestPatternRouting.misroutedRefusal(
            ":", listOf("*PerfGate*"), mapOf("a.b.PerfGateTest" to "perfTest"),
        )
        assertTrue(refusal.contains("a.b.PerfGateTest"), refusal)
        assertTrue(refusal.contains("./build.sh :perfTest --tests '*PerfGate*'"), refusal)
    }

    @Test
    fun `an owned class executing in the broad lane is refused, naming it`() {
        assertNull(TestPatternRouting.leakRefusal(":test", emptyList()))
        val refusal = TestPatternRouting.leakRefusal(":test", listOf("a.b.PerfGateTest"))
        requireNotNull(refusal)
        assertTrue(refusal.contains("a.b.PerfGateTest"), refusal)
        assertTrue(refusal.contains("1 class(es)"), refusal)
    }
}
