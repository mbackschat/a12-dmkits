package io.github.mbackschat.a12.dm.interpreter

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test

/**
 * OPT-IN measurement report: what one resident model costs, per shape. Prints; asserts nothing; commits nothing —
 * the same idiom as the corpus read-coverage reports, so it cannot become a load-sensitive CI gate.
 *
 * It exists because a bounded model cache has to be sized in BYTES and the relation between a model's DM-JSON and
 * its resident cost is not obvious: the prepared form is LARGER than the JSON it came from, and it grows on two
 * independent axes. The fixtures below therefore separate the field axis from the rule axis; a rules-only sweep
 * cannot tell "there is a fixed floor" apart from "cost tracks field count".
 *
 * Four quantities per model, and the third is the one a service actually budgets:
 *  - `dmJson`     the expanded DM-JSON, minified, as bytes on the wire
 *  - `cold`       a `PreparedModel` that has never served a request (its plans are lazy and unforced)
 *  - `warm`       the same after one `createInterpreter()` + `validateFull`, which forces every model-static plan
 *                 into the shared store — this is what a cache entry holds once it has served anything
 *  - `+interp`    warm, plus one live `Interpreter` retained alongside it
 *
 * Run: `./build.sh -Pfast :interpreter:jvmTest --tests "*ModelFootprintReportTest*" -Da12.footprint.report=true`
 * Add `-Da12.footprint.models=<path>,<path>` to measure other self-contained models.
 *
 * The cross-strategy companion — the same quantity for the kernel's dynamic-Groovy and generated-Java engines —
 * is `:adapter`'s `EngineFootprintReportTest`, which must live there because this module stays kernel-free.
 */
class ModelFootprintReportTest {

    @Test
    fun reportResidentCostPerModelShape() {
        if (System.getProperty("a12.footprint.report") != "true") {
            return
        }
        val repoRoot = repoRoot()
        val paths = (System.getProperty("a12.footprint.models")?.split(",")?.map { Path.of(it.trim()) }
            ?: DEFAULT_MODELS.map { repoRoot.resolve(it) })
            .filter { Files.isRegularFile(it) }
        check(paths.isNotEmpty()) { "no measurable model files found" }

        println("model                                    fields rules comps    dmJson      cold      warm   +interp")
        for (path in paths) {
            val json = Files.readString(path)
            val model = io.github.mbackschat.a12.dm.interpreter.load.ModelLoader.load(json)
            val shape = Triple(model.fields.size, model.rules.size, model.computations.size)
            val dmJson = minifiedBytes(json)

            val cold = retained { PreparedModel.loadExpandedJson(json) }
            val warm = retained {
                val prepared = PreparedModel.loadExpandedJson(json)
                prepared.createInterpreter().validateFull(prepared.documents.seedDocument())
                prepared
            }
            val withInterpreter = retained {
                val prepared = PreparedModel.loadExpandedJson(json)
                val interpreter = prepared.createInterpreter()
                interpreter.validateFull(prepared.documents.seedDocument())
                prepared to interpreter
            }
            println(
                "%-40s %6d %5d %5d %9d %9d %9d %9d".format(
                    path.fileName.toString(), shape.first, shape.second, shape.third,
                    dmJson, cold, warm, withInterpreter,
                ),
            )
        }
        println("(retained bytes per instance, JVM ${System.getProperty("java.version")}, holding $HOLD instances)")
    }

    /**
     * Retained bytes per instance: build [HOLD] of them, settle, and divide. Holding many amortizes the
     * measurement's own noise and any one-time class/init cost, which a single instance cannot separate.
     */
    private fun <T> retained(build: () -> T): Long {
        // Warm class loading and any module-level lazy init OUTSIDE the measured window.
        build()
        val baseline = settledUsedBytes()
        val held = ArrayList<T>(HOLD)
        repeat(HOLD) { held += build() }
        val used = settledUsedBytes()
        check(held.size == HOLD)
        return (used - baseline) / HOLD
    }

    private fun settledUsedBytes(): Long {
        val runtime = Runtime.getRuntime()
        var used = Long.MAX_VALUE
        repeat(SETTLE_PASSES) {
            System.gc()
            Thread.sleep(SETTLE_PAUSE_MILLIS)
            used = minOf(used, runtime.totalMemory() - runtime.freeMemory())
        }
        return used
    }

    private fun minifiedBytes(json: String): Int =
        kotlinx.serialization.json.Json.parseToJsonElement(json).toString().toByteArray().size

    private fun repoRoot(): Path {
        var candidate: Path? = Path.of("").toAbsolutePath()
        while (candidate != null && !Files.isDirectory(candidate.resolve("docs"))) {
            candidate = candidate.parent
        }
        return checkNotNull(candidate) { "could not locate the repository root" }
    }

    private companion object {
        const val HOLD = 40
        const val SETTLE_PASSES = 6
        const val SETTLE_PAUSE_MILLIS = 40L

        /** A shape matrix, not a size ladder: the first three vary FIELDS at ~one rule, the last two vary RULES. */
        val DEFAULT_MODELS = listOf(
            "examples/models/typedefs.dm.json",
            "examples/models/order-aggregates.dm.json",
            "corpus/models/order-stock-low.json",
            "interpreter/src/commonTest/resources/performance/many-rules-48.dm.json",
            "interpreter/src/commonTest/resources/performance/many-rules-192.dm.json",
        )
    }
}
