package io.github.mbackschat.a12.dm.adapter.perf

import io.github.mbackschat.a12.dm.adapter.laws.RuntimeLaws
import io.github.mbackschat.a12.dm.interpreter.PreparedModel
import java.nio.file.Files
import java.nio.file.Path
import java.util.Locale
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

/**
 * OPT-IN attribution report: WHICH classes hold the generated-Java engine's heap.
 *
 * `EngineFootprintReportTest` measures how much each strategy retains per resident model and can subtract the two
 * parts that are identified — the kernel's expanded model graph and the bytecode the harness keeps on the heap. It
 * left ~640–790 kB of the Java arm unattributed, and a byte total cannot say what that is. This does: it takes a
 * **differential class histogram** (`jcmd GC.class_histogram`, which collects before counting, so only live objects
 * are reported) with no engines held and again with [HOLD] held, and divides the per-class delta by [HOLD].
 *
 * The same differencing that makes the footprint numbers per-instance makes these per-instance: whatever the test
 * framework, the kernel's statics and the JIT already retain cancels, so what remains is what one more resident
 * engine costs. Classes below [MIN_BYTES_PER_ENGINE] per engine are folded into a remainder line, because a
 * long tail of 200-byte rows hides the answer rather than sharpening it.
 *
 * **Not a regression gate**, for the same reasons as its sibling: `@Tag("kernel-probe")` plus a property gate keeps
 * it out of every sweep including `-PrunPerf`, because it asserts nothing and its answer moves only when the kernel
 * does. Refresh on a kernel upgrade.
 *
 * Run: `./build.sh -Pfast -PrunKernelProbe :adapter:test --tests "*JavaEngineHeapHistogramReportTest*"
 *       -Da12.java.histogram=true`
 * Knobs: `-Da12.java.histogram.model=<repo-relative path>`, `-Da12.java.histogram.hold=<n>`,
 * `-Da12.java.histogram.strategy=java|groovy` (groovy for the contrast).
 */
@Tag("kernel-probe")
class JavaEngineHeapHistogramReportTest {

    @Test
    fun reportWhatOneResidentEngineRetainsByClass() {
        if (System.getProperty("a12.java.histogram") != "true") {
            return
        }
        val hold = System.getProperty("a12.java.histogram.hold")?.toInt() ?: HOLD
        val strategy = System.getProperty("a12.java.histogram.strategy") ?: "java"
        val modelPath = repoRoot().resolve(System.getProperty("a12.java.histogram.model") ?: DEFAULT_MODEL)
        val json = Files.readString(modelPath)
        val documentJson = seedDocumentJson(json)

        // Prime OUTSIDE the differential: load every class this strategy touches, so the delta is state, not classes.
        warm(strategy, json, documentJson)
        val baseline = histogram()
        val held = ArrayList<Any>(hold)
        repeat(hold) { held += warm(strategy, json, documentJson) }
        val after = histogram()
        check(held.size == hold) { "every engine must stay strongly reachable across the sample" }

        val perEngine = (baseline.keys + after.keys)
            // The baseline histogram is itself live while `after` is taken, so this test's own rows are scaffolding
            // by construction, not engine state. Excluded rather than reported and explained away.
            .filterNot { it.startsWith(javaClass.name) }
            .associateWith { name ->
                val before = baseline[name] ?: Counts(0, 0)
                val now = after[name] ?: Counts(0, 0)
                Counts((now.instances - before.instances) / hold, (now.bytes - before.bytes) / hold)
            }.filterValues { it.bytes > 0 }

        val ranked = perEngine.entries.sortedByDescending { it.value.bytes }
        val shown = ranked.filter { it.value.bytes >= MIN_BYTES_PER_ENGINE }
        val tail = ranked.filter { it.value.bytes < MIN_BYTES_PER_ENGINE }

        println("$strategy engine, ${modelPath.fileName}, per resident engine (hold=$hold), heap by class:")
        println("%12s %10s  %s".format("bytes", "instances", "class"))
        shown.forEach { println("%12d %10d  %s".format(it.value.bytes, it.value.instances, it.key)) }
        println(
            "%12d %10d  (%d further classes below $MIN_BYTES_PER_ENGINE B each)".format(
                tail.sumOf { it.value.bytes }, tail.sumOf { it.value.instances }, tail.size,
            ),
        )
        println("%12d %10d  TOTAL".format(ranked.sumOf { it.value.bytes }, ranked.sumOf { it.value.instances }))
        println(
            "(reference: retained model JSON ${json.toByteArray().size} B; " +
                "compiled Java bytecode ${RuntimeLaws.compiledJavaBytecodeBytes(json)} B)",
        )
    }

    private fun warm(strategy: String, modelJson: String, documentJson: String): Any = when (strategy) {
        "java" -> RuntimeLaws.kernelJavaEngine(modelJson).also {
            it.validationSignaturesSorted(it.instance(documentJson), Locale.US, null, emptyMap())
        }
        "groovy" -> RuntimeLaws.kernelEngine(modelJson).also {
            it.validationSignaturesSorted(it.instance(documentJson), Locale.US, null, emptyMap())
        }
        else -> throw IllegalArgumentException("unknown strategy $strategy; use java or groovy")
    }

    private fun seedDocumentJson(modelJson: String): String {
        val prepared = PreparedModel.loadExpandedJson(modelJson)
        return prepared.documents.encodeDocument(prepared.documents.seedDocument())
    }

    /**
     * `jcmd <pid> GC.class_histogram`, parsed. Its rows are `rank: instances bytes class`, and it performs a full
     * collection first, so an unreachable engine from the priming pass is not counted.
     */
    private fun histogram(): Map<String, Counts> {
        val jcmd = Path.of(System.getProperty("java.home"), "bin", "jcmd")
        check(Files.isExecutable(jcmd)) { "no jcmd at $jcmd; a JDK is required for the histogram" }
        val process = ProcessBuilder(jcmd.toString(), ProcessHandle.current().pid().toString(), "GC.class_histogram")
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().use { it.readText() }
        check(process.waitFor() == 0) { "jcmd GC.class_histogram failed: $output" }
        val row = Regex("""^\s*\d+:\s+(\d+)\s+(\d+)\s+(\S+)""")
        return output.lineSequence().mapNotNull { line ->
            row.find(line)?.let { match ->
                val (instances, bytes, name) = match.destructured
                name to Counts(instances.toLong(), bytes.toLong())
            }
        }.groupBy({ it.first }, { it.second })
            .mapValues { (_, counts) ->
                Counts(counts.sumOf { it.instances }, counts.sumOf { it.bytes })
            }
    }

    private fun repoRoot(): Path {
        var candidate: Path? = Path.of("").toAbsolutePath()
        while (candidate != null && !Files.isDirectory(candidate.resolve("docs"))) {
            candidate = candidate.parent
        }
        return checkNotNull(candidate) { "could not locate the repository root" }
    }

    private data class Counts(val instances: Long, val bytes: Long)

    private companion object {
        const val HOLD = 12
        const val MIN_BYTES_PER_ENGINE = 4_000L
        const val DEFAULT_MODEL = "interpreter/src/commonTest/resources/performance/many-rules-192.dm.json"
    }
}
