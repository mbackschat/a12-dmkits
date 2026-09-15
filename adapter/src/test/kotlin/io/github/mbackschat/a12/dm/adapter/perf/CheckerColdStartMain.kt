@file:JvmName("CheckerColdStartMain")

package io.github.mbackschat.a12.dm.adapter.perf

import io.github.mbackschat.a12.dm.consistency.ModelSource
import io.github.mbackschat.a12.dm.consistency.check
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.time.Instant

/**
 * ONE cold check, in a process that will never run a second one — the entry point [CheckerColdStartPerfTest]
 * launches once per observation.
 *
 * **Why a separate process per observation rather than a loop.** Everything the cold boundary is about happens
 * once per JVM: class loading, the serializer's reflective setup, the interpreter-mode bytecode before any JIT
 * compilation. A loop measures the second run onwards, which is the PREPARED boundary and a different question.
 * So the harness pays a process launch per sample, and this file is deliberately the whole program.
 *
 * **Two quantities, printed separately because they answer different questions.** `checkNanos` is model TEXT to
 * complete report — the quantity the plan's H5-b row names, and the only one that is about the checker.
 * `processNanos` adds JVM startup ahead of it, which is what a standalone user actually waits for and what no
 * change to this code can shrink below the platform's floor. Reporting one without the other would either
 * overstate the checker's cost or hide the user's.
 *
 * **It also prints the report, not a count.** The silence claim is what makes a timing meaningful at all: a
 * checker that refused the model would be timing a different code path. The caller compares the printed codes
 * against the expected set rather than asserting how many there are.
 */
fun main(args: Array<String>) {
    require(args.size == 2) { "usage: CheckerColdStartMain <model.json> <diagnostic-label>" }
    val text = Files.readString(Path.of(args[0]))
    val started = System.nanoTime()
    val report = check(ModelSource(text, diagnosticLabel = args[1]))
    val checkNanos = System.nanoTime() - started
    val rejecting = report.findings
        .filter { it.justifiesRejection }
        .map { "${it.code}@${it.at}" }
        .sorted()
    // `startInstant` is the OS process start, so it precedes even the JVM's own initialization. Empty on a
    // platform that withholds it, which is reported as -1 rather than guessed at.
    val processNanos = ProcessHandle.current().info().startInstant()
        .map { Duration.between(it, Instant.now()).toNanos() }
        .orElse(-1L)
    println("checkNanos=$checkNanos")
    println("processNanos=$processNanos")
    println("rejecting=${rejecting.joinToString(",")}")
}
