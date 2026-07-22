package io.github.mbackschat.a12.dm.adapter.perf

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

/** Locks the failure-propagation contract of the Node performance launcher without running a timed bundle. */
class PerfScriptContractTest {

    @Test
    fun gateUsesItsDedicatedTask() {
        val script = script()
        val gateInvocation = script.substringAfter("gate)").substringBefore(";;")

        assertTrue(gateInvocation.contains(":adapter:perfGateTest"),
            "scripts/perf.sh gate must execute the non-cacheable dedicated task")
        assertFalse(gateInvocation.contains(":adapter:test"),
            "the routine gate must not enable performance tests on the broad adapter suite")
    }

    @Test
    fun nodeFailureIsNotSwallowed() {
        val script = script()
        val nodeInvocation = script.substringAfter("\"${'$'}node\" --require")
            .substringBefore(";;")

        assertTrue(nodeInvocation.contains("mocha.js"), "the guard must inspect the Node/Mocha invocation")
        assertFalse(nodeInvocation.contains("|| true"),
            "scripts/perf.sh js must propagate a failing Node/Mocha exit")
    }

    private fun script(): String = Files.readString(Path.of(System.getProperty("perf.script")))
}
