package io.github.mbackschat.a12.dm.adapter.perf

import java.time.Duration
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.assertTimeoutPreemptively

/**
 * The control that makes [ChildProcess]'s timeout a fuse rather than a comment.
 *
 * **Every perf lane that launches a child advertised a harness fuse that could not fire.** Each one read the
 * child's whole output into a string *before* calling the timed `waitFor`, so a child that hung never reached
 * the timeout at all — `readText()` blocks until EOF, and EOF arrives only when the child exits. The `error(…)`
 * line naming the fuse was unreachable code in four places (`CheckerColdStartPerfTest`, `CheckerColdNodePerfTest`
 * twice, `CheckerColdBrowserPerfTest`), found by a cold review of the two new lanes on 2026-09-17 and present in
 * the oldest of them since it was written.
 *
 * **The obvious repair is a worse bug, which is why this locks both halves.** Swapping the two lines — wait
 * first, then read — deadlocks instead: a child writing past the OS pipe buffer blocks in its own `write` while
 * the parent waits for it to exit, and neither moves. So the fix redirects to a file, which is what
 * `ProfileCliPerfTest` in this same package had been doing correctly all along. `theFuseFires` covers the
 * original defect and `aChildLoudEnoughToFillThePipeBufferIsCapturedWhole` covers the wrong repair; a fix that
 * satisfies one and not the other is not a fix.
 */
class ChildProcessFuseTest {

    /**
     * A child that would outlive the fuse many times over must be killed BY the fuse.
     *
     * The preemptive outer bound is the whole point of the case: a regression to the read-first shape would
     * otherwise hang this test for 30 seconds rather than fail it, and a hang reads as infrastructure trouble
     * instead of as a finding.
     */
    @Test
    fun `the fuse fires on a child that never exits`() {
        val failure = assertTimeoutPreemptively(Duration.ofSeconds(20)) {
            assertThrows<IllegalStateException> {
                ChildProcess.runWithFuse(
                    command = listOf("/bin/sh", "-c", "sleep 30"),
                    timeoutSeconds = 1,
                ) { "a sleeping child" }
            }
        }
        assertTrue(
            failure.message!!.contains("1-second harness fuse"),
            "the refusal must name the fuse it tripped: ${failure.message}",
        )
        assertTrue(
            failure.message!!.contains("a sleeping child"),
            "the refusal must name what was being run: ${failure.message}",
        )
    }

    /**
     * Output far past any pipe buffer is captured WHOLE, which the naive wait-then-read repair cannot do.
     *
     * 4 MiB against a typical 64 KiB pipe buffer: a parent that waited for exit before reading would block
     * here forever, so this case is what separates the file redirect from the swap. The bound is generous
     * because the assertion is about completeness, not speed.
     */
    @Test
    fun `a child loud enough to fill the pipe buffer is captured whole`() {
        val lines = 65_536
        val outcome = assertTimeoutPreemptively(Duration.ofSeconds(60)) {
            ChildProcess.runWithFuse(
                // `yes` would never terminate; `seq` gives a bounded, verifiable flood.
                command = listOf("/bin/sh", "-c", "seq 1 $lines | sed 's/^/pad-pad-pad-pad-pad-pad-pad-pad-/'"),
                timeoutSeconds = 60,
            ) { "a loud child" }
        }
        assertEquals(0, outcome.exitCode, "the flood must still exit cleanly: ${outcome.output.take(200)}")
        assertEquals(lines, outcome.output.lineSequence().count { it.isNotBlank() })
        assertTrue(outcome.output.contains("pad-pad-pad-pad-pad-pad-pad-pad-$lines"), "the LAST line must survive")
    }

    /** A failing child reports its code and its output, because the caller decides what a non-zero means. */
    @Test
    fun `a non-zero exit is returned rather than thrown`() {
        val outcome = ChildProcess.runWithFuse(
            command = listOf("/bin/sh", "-c", "echo before-the-failure; exit 3"),
            timeoutSeconds = 30,
        ) { "a failing child" }
        assertEquals(3, outcome.exitCode)
        assertTrue(outcome.output.contains("before-the-failure"), "the output must survive a failure: $outcome")
    }

    /** Standard error is merged, so a child's diagnostics reach the caller's message rather than the void. */
    @Test
    fun `standard error is merged into the captured output`() {
        val outcome = ChildProcess.runWithFuse(
            command = listOf("/bin/sh", "-c", "echo to-stdout; echo to-stderr 1>&2"),
            timeoutSeconds = 30,
        ) { "a talkative child" }
        assertTrue(outcome.output.contains("to-stdout") && outcome.output.contains("to-stderr"), "$outcome")
    }
}
