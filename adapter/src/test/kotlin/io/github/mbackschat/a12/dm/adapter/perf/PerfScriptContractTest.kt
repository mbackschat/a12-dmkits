package io.github.mbackschat.a12.dm.adapter.perf

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

/** Locks the failure-propagation contract of the Node performance launcher without running a timed bundle. */
class PerfScriptContractTest {

    @Test
    fun nodeFailureIsNotSwallowed() {
        val script = Files.readString(Path.of(System.getProperty("perf.script")))
        val nodeInvocation = script.substringAfter("\"${'$'}node\" --require")
            .substringBefore(";;")

        assertTrue(nodeInvocation.contains("mocha.js"), "the guard must inspect the Node/Mocha invocation")
        assertFalse(nodeInvocation.contains("|| true"),
            "scripts/perf.sh js must propagate a failing Node/Mocha exit")
    }
}
