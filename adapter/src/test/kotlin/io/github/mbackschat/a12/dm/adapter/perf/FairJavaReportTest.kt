package io.github.mbackschat.a12.dm.adapter.perf

import com.fasterxml.jackson.databind.ObjectMapper
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import org.junit.jupiter.api.Test

/**
 * Local opt-in structured fair-Java report. It reuses the closed inventory and exact tri-engine preflight;
 * generated-Java construction remains outside every clock. The report is informative until the owner promotes
 * an individual ratio under IF137's six-point gate.
 */
class FairJavaReportTest {

    @Test
    fun writeConfiguredPinnedRunnerReport() {
        if (!System.getenv("FAIR_JAVA_REPORT_ENABLED").equals("true", ignoreCase = true)) return
        fun required(name: String): String =
            System.getenv(name)?.takeIf(String::isNotBlank)
                ?: error("$name is required for the fair-Java report")

        val commit = required("PERFORMANCE_COMMIT")
        require(commit.matches(Regex("[0-9a-f]{8,64}"))) { "PERFORMANCE_COMMIT must be a git identity" }
        val runner = required("PERFORMANCE_RUNNER")
        val requested = System.getenv("FAIR_JAVA_SCENARIOS")
            ?.split(',')
            ?.map(String::trim)
            ?.filter(String::isNotBlank)
            ?.toSet()
            .orEmpty()
        val cases = if (requested.isEmpty()) {
            PerfInventory.cases.filter { it.id.fairJavaCapable }
        } else {
            requested.map { id ->
                PerfInventory.select(id).single().also {
                    require(it.id.fairJavaCapable) { "$id is explicitly excluded from generated-Java evidence" }
                }
            }
        }
        require(cases.isNotEmpty()) { "the fair-Java report selected no scenarios" }

        val warmup = required("FAIR_JAVA_WARMUP").toInt()
        val iterations = required("FAIR_JAVA_ITERATIONS").toInt()
        require(warmup > 0 && iterations > 0) { "warmup and iterations must be positive" }
        val rows = cases.map { case ->
            val row = PerfHarness.runScenario(
                case.scenario(),
                case.n,
                warmup = warmup,
                iters = iterations,
                withJava = true,
            )
            val java = requireNotNull(row.java) { "${row.scenario}: generated-Java stats missing" }
            val ratio = requireNotNull(row.ratioJava) { "${row.scenario}: generated-Java ratio missing" }
            require(ratio.isFinite() && ratio > 0.0) { "${row.scenario}: invalid fair-Java ratio $ratio" }
            linkedMapOf(
                "scenario" to row.identity.id.id,
                "version" to row.identity.id.version,
                "fingerprint" to row.identity.fingerprint,
                "scale" to row.n,
                "resultCardinality" to row.firings,
                "interpreterMedianMillis" to row.interp.medianMs,
                "javaMedianMillis" to java.medianMs,
                "interpreterToJavaRatio" to ratio,
            )
        }
        val runtime = Runtime.getRuntime()
        val report = linkedMapOf(
            "protocolVersion" to 1,
            "informativeOnly" to true,
            "commit" to commit,
            "runner" to runner,
            "runtime" to linkedMapOf(
                "name" to System.getProperty("java.vm.name"),
                "version" to Runtime.version().toString(),
                "os" to System.getProperty("os.name"),
                "arch" to System.getProperty("os.arch"),
                "maxHeapBytes" to runtime.maxMemory(),
            ),
            "estimator" to linkedMapOf(
                "warmup" to warmup,
                "iterations" to iterations,
                "statistic" to "same-process median; interpreter/generated-Java within one run",
            ),
            "rows" to rows,
        )
        val output = Path.of(required("FAIR_JAVA_OUTPUT"))
        output.parent.createDirectories()
        ObjectMapper().writerWithDefaultPrettyPrinter().writeValue(output.toFile(), report)
    }
}
