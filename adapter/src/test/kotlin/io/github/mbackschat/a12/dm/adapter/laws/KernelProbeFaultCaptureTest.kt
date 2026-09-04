package io.github.mbackschat.a12.dm.adapter.laws

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.networknt.schema.JsonSchemaFactory
import com.networknt.schema.SpecVersion
import io.github.mbackschat.a12.dm.modelkit.CustomFieldBuilder
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl
import io.github.mbackschat.a12.dm.rulekit.dsl.Severity
import java.nio.file.Files
import java.nio.file.Path
import java.util.Optional
import java.util.function.Consumer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * A kernel fault is an OBSERVATION, so the run retains it: the artifact is written, the faulting strategy's
 * block carries the raise, the row's clean siblings keep their own observations, and the process still exits
 * non-zero.
 *
 * <p><b>Why.</b> a12-kernel-lean's agent hit a model whose declared `CustomFieldType` has no registered
 * validator: `validateFull` raises, and the probe used to die with the artifact unwritten — not for the
 * faulting row, not for the other rows, not for the strategy that had not run yet (their instrument note,
 * 2026-09-04). They had to quote a stack trace into a checkpoint by hand, which is the one observation this
 * instrument could not pin, and the abort collapsed a two-strategy row into a one-strategy claim because
 * `kernel-groovy-dynamic` raises before `kernel-java-static` runs.
 *
 * <p><b>The fault is captured at the (row, strategy) unit, and a block that faulted carries NO channel keys.</b>
 * That is the artifact's standing rule — an empty observation that reads as an answer is worse than no answer,
 * the same reason `observe: ["compute"]` without `validateFull` is refused — so `messages` is ABSENT rather
 * than `[]` next to a fault, and the record makes the pair unrepresentable rather than merely unwritten.
 *
 * <p><b>`enginesAgree` gains a truthful value instead of none.</b> The fault joins the compared observation, so
 * two strategies that raise identically agree and one that raises beside one that does not disagrees — which is
 * exactly the claim their run could not make.
 *
 * <p>Own domain: an insurance policy whose broker key is a custom field type. One row fills the custom cell and
 * one leaves it empty, because an empty cell never reaches the validator (measured in
 * [CustomFieldTypeSpiInvocationLawsTest]) — so the same request carries a faulting row and a clean one, which
 * is what shows the clean row surviving.
 */
class KernelProbeFaultCaptureTest {

    /** The whole point: an artifact exists, and the run still fails. */
    @Test
    fun aFaultingRowStillProducesAPinnableArtifactAndANonZeroRun() {
        val out = dir.resolve("fault-artifact.json")
        val raised = assertThrows(KernelRuntimeProbe.FaultsObserved::class.java) {
            KernelRuntimeProbe.main(arrayOf(request.toString(), out.toString()))
        }

        assertTrue(Files.exists(out), "the artifact must be written BEFORE the run fails, or nothing is pinnable")
        assertTrue(
            raised.message!!.contains(FILLED) && raised.message!!.contains(TYPE),
            "the failure must name the faulting row and the kernel's own cause — got ${raised.message}",
        )
    }

    /** Both strategies observe the faulting row, and both raise — so the row is a two-strategy observation. */
    @Test
    fun everyStrategyObservesTheFaultingRowAndTheRaiseIsPublishedWithItsCauseChain() {
        val engines = artifact.rowNamed(FILLED).path("engines")

        assertEquals(
            listOf(KernelRuntimeProbe.GROOVY_DYNAMIC, KernelRuntimeProbe.JAVA_STATIC),
            engines.map { it.path("engine").asText() },
            "the second strategy must still be observed — the abort used to eat it",
        )
        assertEquals(
            listOf("validateFull", "validateFull"),
            engines.map { it.path("fault").path("observation").asText() },
            "the fault names the entry point that raised",
        )
        assertTrue(
            engines.all { engine ->
                engine.path("fault").path("chain").any { it.path("message").asText().contains(TYPE) }
            },
            "the cause chain must reach the kernel's own reason, or the fault is not the observation — got " +
                engines.map { it.path("fault").path("chain").toString() },
        )
    }

    /** A faulted block carries no channel keys at all — absence, never an empty list that reads as an answer. */
    @Test
    fun aFaultedBlockPublishesNoChannelItCouldNotObserve() {
        val faulted = artifact.rowNamed(FILLED).path("engines")[0]

        assertEquals(
            listOf("engine", "fault"),
            faulted.fieldNames().asSequence().toList(),
            "messages/computations/partialMessages must be ABSENT beside a fault",
        )
    }

    /** The clean row is untouched: one bad row no longer costs a request its other observations. */
    @Test
    fun theCleanRowKeepsItsOwnObservationInTheSameArtifact() {
        val clean = artifact.rowNamed(EMPTY)

        assertTrue(clean.path("enginesAgree").asBoolean(), "the clean row's strategies agree as they always did")
        assertEquals(
            listOf(listOf("HOLDER_SET"), listOf("HOLDER_SET")),
            clean.path("engines").map { engine -> engine.path("messages").map { it.path("code").asText() } },
            "an empty custom cell never reaches the validator, so this row validates normally",
        )
        assertTrue(clean.path("engines").all { it.path("fault").isMissingNode }, "no fault on a clean row")
    }

    /** Agreement is derived over the fault too, so a row that raises on both strategies is a stated agreement. */
    @Test
    fun agreementIsDerivedOverTheFaultRatherThanLeftUnstated() {
        assertTrue(
            artifact.rowNamed(FILLED).path("enginesAgree").asBoolean(),
            "both strategies raised the same fault, which IS an agreement — their run could only say one faulted",
        )
    }

    /**
     * **The discriminating half, and it has to be CONSTRUCTED.** The row above agrees whether or not the fault
     * joins the comparison, because both strategies raise identically — so it cannot tell a fault-aware
     * agreement from one that ignores the channel. Only an ASYMMETRIC row can, and the real kernel does not
     * supply one on demand: a strategy that faults where the other does not would itself be a split finding.
     * So it is built at the layer that would destroy it, exactly as [KernelProbeComputeShapeTest] builds the
     * constructed compute split. The clean side deliberately carries NO messages, which is the strictest form:
     * with the fault dropped from the comparison both blocks read as "no messages" and the row would claim
     * agreement. *(Written after a mutant removing `fault` from the compared observation survived the row
     * above, 2026-09-04.)*
     */
    @Test
    fun aFaultOnOneStrategyOnlyIsADISAGREEMENT() {
        val fault = KernelProbeArtifact.Fault(
            "validateFull",
            listOf(KernelProbeArtifact.Fault.Raise("java.lang.IllegalStateException", Optional.of("raised"))),
        )
        val asymmetric = KernelProbeArtifact.Row(
            "one-faulted",
            listOf(
                KernelProbeArtifact.Engine.faulted(KernelRuntimeProbe.GROOVY_DYNAMIC, fault),
                KernelProbeArtifact.Engine(KernelRuntimeProbe.JAVA_STATIC, emptyList()),
            ),
        )
        val symmetric = KernelProbeArtifact.Row(
            "both-faulted",
            listOf(
                KernelProbeArtifact.Engine.faulted(KernelRuntimeProbe.GROOVY_DYNAMIC, fault),
                KernelProbeArtifact.Engine.faulted(KernelRuntimeProbe.JAVA_STATIC, fault),
            ),
        )

        assertEquals(
            listOf(false, true),
            listOf(asymmetric.enginesAgree(), symmetric.enginesAgree()),
            "a raise on one strategy only is a split; the same raise on both is an agreement",
        )
    }

    /** A faulted block may not also carry a channel — the illegal pair is refused, not merely unwritten. */
    @Test
    fun aFaultBesideAChannelIsUnrepresentable() {
        val fault = KernelProbeArtifact.Fault(
            "compute",
            listOf(KernelProbeArtifact.Fault.Raise("java.lang.IllegalStateException", Optional.empty())),
        )
        val message = KernelProbeArtifact.Message(
            "CODE", "Policy/Holder", "ERROR", "VALUE_ERROR", "text", emptyList(), emptyList(),
        )

        assertThrows(IllegalArgumentException::class.java) {
            KernelProbeArtifact.Engine(
                KernelRuntimeProbe.GROOVY_DYNAMIC, listOf(message),
                Optional.empty(), Optional.empty(), Optional.of(fault),
            )
        }
    }

    /** The published shape stays the published shape: the fault-carrying artifact validates against it. */
    @Test
    fun theFaultCarryingArtifactConformsToTheArtifactSchema() {
        val schema = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012).getSchema(
            KernelProbeFaultCaptureTest::class.java.classLoader
                .getResource("kernel-probe/artifact.schema.json")!!.toURI(),
        )
        val problems = schema.validate(artifact).map { it.message }
        assertEquals(emptyList<String>(), problems, "a fault-carrying artifact must satisfy the published schema")
        assertFalse(artifact.path("rows").isEmpty, "…over a non-empty artifact, or the check is vacuous")
    }

    private fun JsonNode.rowNamed(name: String): JsonNode =
        path("rows").single { it.path("name").asText() == name }

    companion object {

        private val MAPPER = ObjectMapper()

        private const val FILLED = "broker-key-filled"
        private const val EMPTY = "broker-key-empty"

        private const val TYPE = "BrokerKey"
        private const val ROOT = "/Policy"
        private const val HOLDER = "$ROOT/Holder"
        private const val KEY = "$ROOT/BrokerKey"

        @JvmStatic
        @TempDir
        lateinit var dir: Path

        /**
         * A policy whose broker key is a custom field type nothing registers, plus a rule reading the plain
         * holder field so the clean row has an observation of its own to keep.
         */
        private val model: String by lazy {
            val base = DocumentModelDraft.named("probe-fault-capture").locale("en_US")
                .rootGroup("Policy") { policy ->
                    policy.stringField("Holder", Consumer { })
                    policy.customField(
                        "BrokerKey",
                        Consumer<CustomFieldBuilder> { it.type(TYPE).minLength(4).maxLength(12) },
                    )
                }.build().toJson()
            val bound = BoundModel.ofJson(base)
            TypedRules.inject(
                base,
                bound.rule(bound.group(ROOT)).name("HolderRecorded").errorField(bound.field(HOLDER))
                    .`when`(bound.field(HOLDER).filled()).code("HOLDER_SET").severity(Severity.ERROR)
                    .message(Dsl.en("The policy holder is recorded.")).build(),
            )
        }

        private val ROWS = """
            { "name": "$FILLED", "documentInline": { "Policy": {
                "Holder": "L. Weber", "BrokerKey": "BRK-0042" } } },
            { "name": "$EMPTY", "documentInline": { "Policy": { "Holder": "L. Weber" } } }
        """

        private fun write(name: String, content: String): Path = Files.writeString(dir.resolve(name), content)

        private val request: Path by lazy {
            write(
                "fault.request.json",
                """{ "model": "${write("fault.model.json", model).fileName}",
                     "observe": ["validateFull"], "rows": [ ${ROWS.trimIndent()} ] }""",
            )
        }

        /** The artifact the faulting run wrote — read back after the expected failure. */
        private val artifact: JsonNode by lazy {
            val out = dir.resolve("shared-fault-artifact.json")
            runCatching { KernelRuntimeProbe.main(arrayOf(request.toString(), out.toString())) }
            MAPPER.readTree(Files.readString(out))
        }
    }
}
