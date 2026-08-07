package io.github.mbackschat.a12.dm.adapter.perf

import com.mgmtp.a12.kernel.md.rt.api.ICustomCondition
import io.github.mbackschat.a12.dm.adapter.laws.RuntimeLaws
import io.github.mbackschat.a12.dm.adapter.profile.ModelSynthesizer
import io.github.mbackschat.a12.dm.interpreter.CustomCondition
import io.github.mbackschat.a12.dm.interpreter.CustomConditionFactory
import io.github.mbackschat.a12.dm.interpreter.CustomConditionRegistry
import io.github.mbackschat.a12.dm.interpreter.InterpreterConfiguration
import io.github.mbackschat.a12.dm.interpreter.PreparedModel
import io.github.mbackschat.a12.dm.interpreter.load.ModelLoader
import java.lang.management.ManagementFactory
import java.nio.file.Files
import java.nio.file.Path
import java.util.Locale
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

/**
 * OPT-IN measurement report: what one RESIDENT model costs, per evaluation strategy — the interpreter's
 * `PreparedModel` against the kernel's two, dynamic Groovy and generated Java. It is the cross-strategy companion
 * to `:interpreter`'s `ModelFootprintReportTest` (which measures the interpreter alone) and it exists because a
 * service sizing a model cache has to know how the choice of engine moves that budget.
 *
 * **Two accounts, because one is not enough here.** The interpreter's resident cost is entirely HEAP: it holds a
 * parsed model plus lazily built plans and generates no code. Both kernel strategies GENERATE AND LOAD CLASSES per
 * model, and class metadata lives in **Metaspace**, which `Runtime.totalMemory() - freeMemory()` cannot see. A
 * heap-only comparison would therefore understate the kernel strategies by construction, so every arm reports heap,
 * metaspace and loaded-class count.
 *
 * **The equivalent-work check runs first.** A footprint is only comparable between engines that computed the same
 * answer, so each model's three engines validate the identical document — the interpreter's own seed document,
 * encoded as A12 Document JSON and read back by the kernel's own deserializer — and their message-code multisets
 * must agree before any arm is measured. Without it a silently degraded engine would report an attractively small
 * footprint.
 *
 * `@Tag("perf")` plus an env gate: this is GC-sensitive and Groovy codegen is slow, so it never runs by default.
 *
 * Run: `./build.sh -Pfast -PrunPerf :adapter:test --tests "*EngineFootprintReportTest*" -Da12.engine.footprint=true`
 * Knobs: `-Da12.engine.footprint.hold=<n>` (instances per arm), `-Da12.engine.footprint.models=<path>,<path>`.
 */
@Tag("perf")
class EngineFootprintReportTest {

    @Test
    fun reportResidentCostPerEvaluationStrategy() {
        if (System.getProperty("a12.engine.footprint") != "true") {
            return
        }
        val hold = System.getProperty("a12.engine.footprint.hold")?.toInt() ?: DEFAULT_HOLD
        val models = measuredModels()

        println(
            "model                                   arm            heapB    metaB     totalB classes codes",
        )
        for ((label, json) in models) {
            val bindings = bindingsFor(json)
            val documentJson = seedDocumentJson(json)
            val codes = agreedMessageCodes(json, documentJson, bindings).size

            report(label, "modeldef", codes, measure(hold) { ModelLoader.load(json) })
            report(label, "interp cold", codes, measure(hold) { PreparedModel.loadExpandedJson(json) })
            report(label, "interp warm", codes, measure(hold) { warmInterpreter(json, documentJson, bindings) })
            report(label, "kernel model", codes, measure(hold) { RuntimeLaws.expandedDocumentModel(json) })
            report(label, "groovy cold", codes, measure(hold) { RuntimeLaws.kernelEngine(json) })
            report(label, "groovy warm", codes, measure(hold) { warmGroovy(json, documentJson, bindings) })
            report(label, "java warm", codes, measure(hold) { warmJava(json, documentJson, bindings) })
            println(
                "%-39s %-11s %9d %8d   (dm-json %d; generated source bytes: groovy, java)".format(
                    label, "gen source",
                    RuntimeLaws.generatedGroovySource(json).toByteArray().size,
                    RuntimeLaws.generatedJavaSource(json).toByteArray().size,
                    json.toByteArray().size,
                ),
            )
        }
        println(
            "(retained per resident model, hold=$hold, JVM ${System.getProperty("java.version")}; " +
                "totalB = heap + Metaspace + Compressed Class Space; the gen-source row is " +
                "deterministic input volume, not resident cost)",
        )
    }

    /**
     * One place binds BOTH engines, so a model with a `CustomCondition` cannot be measured with the callback
     * supplied to only one of them — the mistake `PerfRuntimeBindings` exists to prevent for timing, made here for
     * footprint. The interpreter takes a factory and so needs no enumeration; the kernel takes a map, so the names
     * are read out of the model's own DSL text. A name the pattern misses fails the agreement check loudly rather
     * than silently measuring two different computations.
     */
    private fun bindingsFor(modelJson: String): Bindings = Bindings(
        configuration = InterpreterConfiguration.builder()
            .customConditions(
                CustomConditionRegistry.fromFactory(
                    CustomConditionFactory { CustomCondition { CONSTANT_CONDITION } },
                ),
            )
            .build(),
        kernelConditions = CUSTOM_CONDITION_REFERENCE.findAll(modelJson)
            .map { it.groupValues[1] }
            .toSortedSet()
            .associateWith { constantKernelCondition(CONSTANT_CONDITION) },
    )

    /**
     * The committed fixtures, plus one model **derived from a real-world A12 model's shape**.
     *
     * The hand-authored fixtures are a deliberate shape matrix, but they are not realistic in scale: the largest is
     * 54 kB of DM-JSON with one group, while production models in the `a12-kernel-mm` reference clone reach 281 kB
     * across 79 fields, 29 groups and 89 rules. A rule of thumb fitted only to the matrix therefore has no group or
     * repeatability term at all, and under-predicts a real model.
     *
     * The last entry closes that gap without committing anyone's model: a `dmtool profile extract` profile of a real
     * model, which [MODEL-PROFILE-SPEC] defines as identifier-free — counts and distributions only, no identifiers,
     * paths, literals, messages or expressions (verified: the committed artifact contains no string values at all) —
     * synthesized back into an own-domain model at a fixed seed. Its shape is faithful where footprint depends on it
     * (field, group and rule counts exactly; boolean arity within bucket) and only partly faithful on the operator
     * mix and iteration capacity, which drive evaluation work rather than retained size. It is a footprint fixture,
     * not a performance clone.
     */
    private fun measuredModels(): List<Pair<String, String>> {
        val repoRoot = repoRoot()
        // The test JVM's working directory is the module, not the repository, so a relative override is resolved
        // against the repository root — the only base a caller reading the default list would expect.
        val override = System.getProperty("a12.engine.footprint.models")?.split(",")?.map { it.trim() }
        val paths = (override ?: DEFAULT_MODELS).map { repoRoot.resolve(it) }
        val missing = paths.filterNot { Files.isRegularFile(it) }
        check(missing.isEmpty()) { "requested model files do not exist: $missing" }
        val fromFiles = paths.map { it.fileName.toString() to Files.readString(it) }
        if (override != null) {
            return fromFiles
        }
        val profile = checkNotNull(javaClass.getResourceAsStream(REALISTIC_SHAPE_PROFILE)) {
            "missing footprint profile resource $REALISTIC_SHAPE_PROFILE"
        }.use { it.readBytes().decodeToString() }
        return fromFiles + ("profile-derived-real-shape" to ModelSynthesizer.synthesize(profile, SYNTHESIS_SEED))
    }

    /**
     * The interpreter's own seed document, in A12's canonical Document JSON. Using the interpreter's encoder as the
     * source is deliberate: it is a published boundary whose output the kernel's deserializer accepts, so neither
     * engine gets a document shaped for its own convenience.
     */
    private fun seedDocumentJson(modelJson: String): String {
        val prepared = PreparedModel.loadExpandedJson(modelJson)
        return prepared.documents.encodeDocument(prepared.documents.seedDocument())
    }

    /**
     * The equivalent-work gate: all three strategies must agree on the message-code multiset, and the interpreter
     * must report nothing unsupported. Both halves matter — an engine that skipped a rule would otherwise report an
     * attractively small footprint for having done less work.
     */
    private fun agreedMessageCodes(
        modelJson: String,
        documentJson: String,
        bindings: Bindings,
    ): List<String> {
        val prepared = PreparedModel.loadExpandedJson(modelJson)
        val result = prepared.createInterpreter(bindings.configuration)
            .validateFull(prepared.documents.readDocument(documentJson))
        assertEquals(
            emptyList<Any>(), result.unsupported,
            "the interpreter skipped part of this model; its footprint would not be comparable",
        )
        val interpreter = result.findings.map { it.code }.sorted()
        val groovyEngine = RuntimeLaws.kernelEngine(modelJson)
        val groovy = codesOf(
            groovyEngine.validationSignaturesSorted(
                groovyEngine.instance(documentJson), Locale.US, null, bindings.kernelConditions,
            ),
        )
        val javaEngine = RuntimeLaws.kernelJavaEngine(modelJson)
        val java = codesOf(
            javaEngine.validationSignaturesSorted(
                javaEngine.instance(documentJson), Locale.US, null, bindings.kernelConditions,
            ),
        )
        assertEquals(groovy, interpreter, "interpreter and dynamic-Groovy disagree; footprints are not comparable")
        assertEquals(groovy, java, "dynamic-Groovy and static-Java disagree; footprints are not comparable")
        return interpreter
    }

    /** `code|type|pointer` → the code multiset, the coarsest agreement all three engines can be compared on. */
    private fun codesOf(signatures: List<String>): List<String> =
        signatures.map { it.substringBefore('|') }.sorted()

    private fun warmInterpreter(modelJson: String, documentJson: String, bindings: Bindings): Any {
        val prepared = PreparedModel.loadExpandedJson(modelJson)
        prepared.createInterpreter(bindings.configuration)
            .validateFull(prepared.documents.readDocument(documentJson))
        return prepared
    }

    private fun warmGroovy(modelJson: String, documentJson: String, bindings: Bindings): Any {
        val engine = RuntimeLaws.kernelEngine(modelJson)
        // The first validation is what triggers the Groovy generation the engine's own code cache then holds.
        engine.validationSignaturesSorted(engine.instance(documentJson), Locale.US, null, bindings.kernelConditions)
        return engine
    }

    private fun warmJava(modelJson: String, documentJson: String, bindings: Bindings): Any {
        val engine = RuntimeLaws.kernelJavaEngine(modelJson)     // codegen + in-memory compile are eager here
        engine.validationSignaturesSorted(engine.instance(documentJson), Locale.US, null, bindings.kernelConditions)
        return engine
    }

    private fun report(label: String, arm: String, codes: Int, retained: Retained) {
        println(
            "%-39s %-11s %9d %8d %10d %6d %5d".format(
                label, arm, retained.heap, retained.metaspace, retained.total, retained.classes, codes,
            ),
        )
    }

    /**
     * Retained cost per instance: build [hold] of them, settle, divide. Holding several amortizes the measurement's
     * own noise and any one-time class-loading cost that a single instance cannot separate from the model's own.
     */
    private fun measure(hold: Int, build: () -> Any): Retained {
        // Two priming builds, not one: the first loads the engine's classes, the second lets anything the first
        // build left collectable (a discarded generated classloader) actually be reclaimed before the baseline.
        build()
        build()
        val baseline = settled()
        val held = ArrayList<Any>(hold)
        repeat(hold) { held += build() }
        val after = settled()
        check(held.size == hold)                    // every instance stays strongly reachable across the sample
        return Retained(
            heap = (after.heap - baseline.heap) / hold,
            metaspace = (after.metaspace - baseline.metaspace) / hold,
            classes = (after.classes - baseline.classes) / hold,
        )
    }

    private fun settled(): Retained {
        val runtime = Runtime.getRuntime()
        var heap = Long.MAX_VALUE
        var metaspace = Long.MAX_VALUE
        repeat(SETTLE_PASSES) {
            System.gc()
            Thread.sleep(SETTLE_PAUSE_MILLIS)
            heap = minOf(heap, runtime.totalMemory() - runtime.freeMemory())
            metaspace = minOf(metaspace, metaspaceUsedBytes())
        }
        return Retained(heap, metaspace, ManagementFactory.getClassLoadingMXBean().loadedClassCount.toLong())
    }

    /**
     * HotSpot accounts class metadata in two DISJOINT pools — Compressed Class Space is not part of Metaspace — and
     * a code-generating engine occupies both, invisibly to `Runtime.totalMemory() - freeMemory()`.
     *
     * The JIT code cache is deliberately NOT counted, and that was measured rather than assumed: attributing it per
     * instance produced negative deltas and charged an interpreter that generates no code 67 kB per model. It tracks
     * how hot code got, not how many models are resident, and it is globally bounded and JIT-evicted — so it is a
     * property of the workload, not of a cache entry. Do not re-add it.
     */
    private fun metaspaceUsedBytes(): Long =
        ManagementFactory.getMemoryPoolMXBeans()
            .filter { it.name == "Metaspace" || it.name == "Compressed Class Space" }
            .sumOf { it.usage.used }

    private fun repoRoot(): Path {
        var candidate: Path? = Path.of("").toAbsolutePath()
        while (candidate != null && !Files.isDirectory(candidate.resolve("docs"))) {
            candidate = candidate.parent
        }
        return checkNotNull(candidate) { "could not locate the repository root" }
    }

    private data class Retained(val heap: Long, val metaspace: Long, val classes: Long) {
        /** The number a service budgets: both pools a resident model occupies, not just the heap. */
        val total: Long get() = heap + metaspace
    }

    /** Both engines' customization bindings, built together so neither can be configured without the other. */
    private class Bindings(
        val configuration: InterpreterConfiguration,
        val kernelConditions: Map<String, ICustomCondition>,
    )

    private companion object {
        const val DEFAULT_HOLD = 12
        const val SETTLE_PASSES = 8
        const val SETTLE_PAUSE_MILLIS = 50L

        /** A constant callback keeps the measured work identical on both sides; its verdict is irrelevant here. */
        const val CONSTANT_CONDITION = false

        /** The DSL spelling a model uses to reference a host callback: `CustomCondition <Name>`. */
        val CUSTOM_CONDITION_REFERENCE = Regex("""CustomCondition\s+([A-Za-z_][A-Za-z0-9_]*)""")


        /** The profile of a real-world model's shape; identifier-free by construction (MODEL-PROFILE-SPEC). */
        const val REALISTIC_SHAPE_PROFILE = "/footprint/realistic-shape.profile.json"

        /** Pinned so the derived model is byte-identical run to run (the spec's fixed-seed synthesis guarantee). */
        const val SYNTHESIS_SEED = 42L

        /** The same shape matrix `ModelFootprintReportTest` uses, so the two reports' rows line up. */
        val DEFAULT_MODELS = listOf(
            "examples/models/typedefs.dm.json",
            "examples/models/order-aggregates.dm.json",
            "corpus/models/order-stock-low.json",
            "interpreter/src/commonTest/resources/performance/many-rules-48.dm.json",
            "interpreter/src/commonTest/resources/performance/many-rules-192.dm.json",
        )
    }
}
