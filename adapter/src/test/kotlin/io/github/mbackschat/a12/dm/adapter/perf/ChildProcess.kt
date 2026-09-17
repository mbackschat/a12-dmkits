package io.github.mbackschat.a12.dm.adapter.perf

import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.io.path.deleteIfExists
import kotlin.io.path.readText

/**
 * One child process, run to completion under a timeout that can actually fire.
 *
 * **Every perf lane in this package that launches a child had a fuse it could never trip.** The shape was
 * always the same: read the child's whole output into a string, *then* call the timed `waitFor`. But
 * `readText()` blocks until EOF and EOF arrives only when the child exits, so a hung child never reaches the
 * timeout and the `error(…)` line naming it is unreachable. Four sites carried it — `CheckerColdStartPerfTest`,
 * `CheckerColdNodePerfTest` (the observation child and the version probe) and `CheckerColdBrowserPerfTest` —
 * and the oldest had it from the day it was written. A cold review of the two newest found it on 2026-09-17.
 *
 * **Swapping the two lines is a worse bug, and it is the repair that suggests itself.** Wait first and a child
 * whose output exceeds the OS pipe buffer (typically 64 KiB) blocks in its own `write` while the parent waits
 * for an exit that can no longer come — a deadlock where the old shape merely had a dead fuse. So the output
 * goes to a FILE, leaving nothing for the child to block on, which is what `ProfileCliPerfTest` in this same
 * package had been doing correctly all along. [ChildProcessFuseTest] locks both halves, because a change that
 * satisfies one and not the other is not a fix.
 *
 * Standard error is merged rather than separated: every caller here treats the child's diagnostics as part of
 * the evidence for a refusal, and a lane that dropped stderr would report a parse failure without the reason.
 */
internal object ChildProcess {

    /** What a completed child produced. A non-zero [exitCode] is the CALLER's to interpret, never a throw. */
    internal data class Outcome(val exitCode: Int, val output: String)

    /**
     * Run [command] to completion, or kill it and fail with everything it managed to say.
     *
     * [describe] is called only on the failure paths, so a caller may build a message as expensive as it needs
     * without paying for it on every one of a lane's hundred-odd observations.
     */
    fun runWithFuse(
        command: List<String>,
        directory: Path? = null,
        environment: Map<String, String> = emptyMap(),
        timeoutSeconds: Long,
        describe: () -> String,
    ): Outcome {
        val captured = Files.createTempFile("perf-child", ".out")
        try {
            val builder = ProcessBuilder(command)
                .redirectErrorStream(true)
                .redirectOutput(captured.toFile())
            if (directory != null) {
                builder.directory(directory.toFile())
            }
            builder.environment().putAll(environment)
            val process = builder.start()
            if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
                process.destroyForcibly().waitFor()
                error(
                    "${describe()} exceeded the $timeoutSeconds-second harness fuse and was killed; it had " +
                        "printed: ${captured.readText()}",
                )
            }
            return Outcome(process.exitValue(), captured.readText())
        } finally {
            captured.deleteIfExists()
        }
    }
}
