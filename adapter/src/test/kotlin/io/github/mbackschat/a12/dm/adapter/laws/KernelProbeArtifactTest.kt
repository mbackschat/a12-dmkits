package io.github.mbackschat.a12.dm.adapter.laws

import com.fasterxml.jackson.databind.ObjectMapper
import com.networknt.schema.JsonSchemaFactory
import com.networknt.schema.SpecVersion
import java.util.Optional
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The kernel-probe artifact's own guarantees, checked WITHOUT a kernel — the shape half of
 * [KernelRuntimeProbe]'s acceptance, with the kernel-backed half in [KernelRuntimeProbeTest].
 *
 * Kernel-free on purpose. The property that matters most here is that a **strategy disagreement survives into
 * the artifact**, and the real kernel does not supply one on demand: the drop-in certificate records **zero**
 * java-static splits across both real corpora, and no committed conformance case carries a `divergences` entry.
 * So the disagreement is constructed at the layer that would destroy it — the artifact — where a merge, a
 * first-wins set, or an independently-authored `enginesAgree` would each fail.
 */
class KernelProbeArtifactTest {

    private val mapper = ObjectMapper()

    private fun header() = KernelProbeArtifact.Header(
        "0.0.0-test", "30.8.1", "30.8.1",
        "0000000000000000000000000000000000000000", "CLEAN", "order.dm.json", "en_US", Optional.empty(),
    )

    private fun message(code: String, fillToFix: List<String> = emptyList()) = KernelProbeArtifact.Message(
        code, "Order/Quantity", "ERROR", if (fillToFix.isEmpty()) "VALUE_ERROR" else "OMISSION_ERROR",
        "x", listOf("Order/Quantity"), fillToFix,
    )

    private fun artifact(vararg rows: KernelProbeArtifact.Row) = KernelProbeArtifact(header(), rows.toList())

    /**
     * Acceptance case 1 — a row on which the two strategies differ reports BOTH, separately, and says so.
     *
     * The three plausible wrong implementations each fail a different assertion below: merging produces one
     * block or a union, first-wins loses the second block's content, and an `enginesAgree` a caller could set
     * would let the flag contradict the blocks it summarizes.
     */
    @Test
    fun aStrategyDisagreementSurvivesWithBothObservationsIntact() {
        val row = KernelProbeArtifact.Row(
            "split",
            listOf(
                KernelProbeArtifact.Engine(KernelRuntimeProbe.GROOVY_DYNAMIC, listOf(message("ANCHOR"))),
                KernelProbeArtifact.Engine(KernelRuntimeProbe.JAVA_STATIC, listOf(message("DIVERGENT"))),
            ),
        )
        assertFalse(row.enginesAgree(), "differing message lists must not be reported as agreement")

        val rows = mapper.readTree(artifact(row).toJson()).path("rows")
        assertEquals(1, rows.size())
        val engines = rows[0].path("engines")
        assertEquals(2, engines.size(), "both strategies must keep their own block — never merged into one")
        assertFalse(rows[0].path("enginesAgree").asBoolean(), "the artifact must publish the disagreement")
        assertEquals(
            listOf(KernelRuntimeProbe.GROOVY_DYNAMIC to "ANCHOR", KernelRuntimeProbe.JAVA_STATIC to "DIVERGENT"),
            engines.map { it.path("engine").asText() to it.path("messages")[0].path("code").asText() },
            "each block keeps its OWN observation; a first-wins implementation loses the second",
        )
    }

    /** …and the negative control: identical observations are reported as agreement, still in two blocks. */
    @Test
    fun identicalObservationsAgreeAndAreStillReportedPerStrategy() {
        val row = KernelProbeArtifact.Row(
            "agreed",
            listOf(
                KernelProbeArtifact.Engine(KernelRuntimeProbe.GROOVY_DYNAMIC, listOf(message("SAME"))),
                KernelProbeArtifact.Engine(KernelRuntimeProbe.JAVA_STATIC, listOf(message("SAME"))),
            ),
        )
        assertTrue(row.enginesAgree())
        assertEquals(2, mapper.readTree(artifact(row).toJson()).path("rows")[0].path("engines").size())
    }

    /**
     * Acceptance case 4, at the rendering layer — the kernel emits messages in no guaranteed order, so the
     * artifact imposes a total one. Two engines whose observations differ only in emission order are equal.
     */
    @Test
    fun messageOrderIsTotalRatherThanKernelEmissionOrder() {
        val forward = KernelProbeArtifact.Engine(
            KernelRuntimeProbe.GROOVY_DYNAMIC, listOf(message("B"), message("A")),
        )
        val reversed = KernelProbeArtifact.Engine(
            KernelRuntimeProbe.JAVA_STATIC, listOf(message("A"), message("B")),
        )
        assertEquals(listOf("A", "B"), forward.messages().map { it.code() }, "an engine sorts on construction")
        assertTrue(
            KernelProbeArtifact.Row("r", listOf(forward, reversed)).enginesAgree(),
            "emission order alone must not read as a strategy split",
        )
    }

    /** The renderer pins its own line separator, so the same observation is the same SHA-256 anywhere. */
    @Test
    fun renderingUsesNewlinesRatherThanThePlatformSeparator() {
        val json = artifact(
            KernelProbeArtifact.Row(
                "r", listOf(KernelProbeArtifact.Engine(KernelRuntimeProbe.GROOVY_DYNAMIC, listOf(message("A")))),
            ),
        ).toJson()
        assertFalse(json.contains("\r"), "a carriage return would make the artifact platform-dependent")
        assertTrue(json.endsWith("}\n"), "the artifact ends with exactly one trailing newline")
    }

    /** The published request schema must itself accept the worked example it advertises. */
    @Test
    fun theRequestSchemaAcceptsItsOwnExample() {
        val schemaNode = mapper.readTree(
            javaClass.classLoader.getResource("kernel-probe/request.schema.json")!!.readText(),
        )
        val schema = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012).getSchema(schemaNode)
        schemaNode.path("examples").forEachIndexed { index, example ->
            assertEquals(emptySet<Any>(), schema.validate(example), "request example $index must validate")
        }
        assertTrue(schemaNode.path("examples").size() > 0, "the schema must carry a worked example to check")
    }
}
