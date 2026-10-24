package io.github.mbackschat.a12.dm.adapter.laws

import com.fasterxml.jackson.databind.ObjectMapper
import com.networknt.schema.JsonSchemaFactory
import com.networknt.schema.SpecVersion
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.EnumSet
import java.util.Locale
import java.util.Optional
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * The compute exposure's guarantees that hold WITHOUT a kernel — the shape half of the acceptance, with the
 * kernel-backed half in [KernelProbeComputeTest]. Three of them cannot be measured against the real kernel at
 * all, which is why they live here:
 *
 *  - **byte compatibility** is a claim about the producer as it was BEFORE this change, so its oracle is the
 *    committed output of that producer, not a fresh run of this one;
 *  - a **strategy disagreement over computations** is not something the kernel supplies on demand (zero
 *    java-static splits are recorded across both real corpora), so it is constructed at the layer that would
 *    destroy it;
 *  - a **computed empty string** is not a state kernel 30.8.1 reaches — a computation whose final result is
 *    empty text stores nothing and is reported CLEARED (IF123) — yet the representation still has to keep it
 *    apart from a clear, because an implementation that collapsed them would be lossy the day it could.
 */
class KernelProbeComputeShapeTest {

    private val mapper = ObjectMapper()

    // ---- acceptance case 1: byte compatibility ---------------------------------------------------

    /**
     * A request that does not ask for compute renders exactly what it rendered before compute existed.
     *
     * The expected bytes are not hand-written: they were produced by the PRE-change renderer over this exact
     * value and committed as `kernel-probe/byte-compatibility-artifact.json`, so this compares two producers
     * rather than a producer against a belief about it. Consumers pin these bytes by SHA-256, so the hash is
     * asserted too — that is the form the guarantee actually travels in.
     */
    @Test
    fun anArtifactWithoutComputeRendersTheBytesTheProducerAlwaysDid() {
        val rendered = KernelProbeArtifact(header(), listOf(baselineRow())).toJson()

        assertEquals(
            baselineBytes(), rendered,
            "the no-compute artifact must be byte-identical to the pre-change output",
        )
        // The hash is the form the guarantee travels in, not a second oracle: it necessarily agrees with the
        // line above. It is asserted so the pinned digest is written down where a consumer can compare it.
        assertEquals("9eefc3a1d8395822553d727adf45dc0142f2665b2e57e50358d7f2379d4789e9", sha256(rendered))
    }

    // ---- acceptance case 5: an engine split over computations survives ---------------------------

    /**
     * Two strategies that agree on every message but differ on ONE computed outcome are reported as a
     * disagreement, with both blocks intact.
     *
     * This is the assertion that fails an `enginesAgree` derived from the message channel alone — which is what
     * the flag meant before compute was observable, and would have quietly reported `true` for a row whose own
     * content shows a split.
     */
    @Test
    fun aComputationOnlyDisagreementIsReportedAsADisagreement() {
        val row = KernelProbeArtifact.Row(
            "split",
            listOf(
                engine(KernelRuntimeProbe.GROOVY_DYNAMIC, valued("Order/LineTotal", "50.00")),
                engine(KernelRuntimeProbe.JAVA_STATIC, cleared("Order/LineTotal")),
            ),
        )
        assertFalse(row.enginesAgree(), "identical messages must not read as agreement when the outcomes differ")

        val engines = mapper.readTree(artifact(row).toJson()).path("rows")[0].path("engines")
        assertEquals(2, engines.size(), "both strategies keep their own block — never merged into one")
        assertEquals(
            listOf(
                KernelRuntimeProbe.GROOVY_DYNAMIC to "value=50.00",
                KernelRuntimeProbe.JAVA_STATIC to "cleared",
            ),
            engines.map { engine ->
                val outcome = engine.path("computations").path("outcomes")[0]
                engine.path("engine").asText() to
                    if (outcome.path("cleared").asBoolean()) "cleared" else "value=${outcome.path("value").asText()}"
            },
            "…and each block keeps its OWN outcome: a first-wins implementation loses the second",
        )
    }

    /** The negative control: identical computations agree, still in two blocks. */
    @Test
    fun identicalComputationsAgreeAndAreStillReportedPerStrategy() {
        val row = KernelProbeArtifact.Row(
            "agreed",
            listOf(
                engine(KernelRuntimeProbe.GROOVY_DYNAMIC, valued("Order/LineTotal", "50.00")),
                engine(KernelRuntimeProbe.JAVA_STATIC, valued("Order/LineTotal", "50.00")),
            ),
        )
        assertTrue(row.enginesAgree())
        assertEquals(2, mapper.readTree(artifact(row).toJson()).path("rows")[0].path("engines").size())
    }

    /** An observed row and an unobserved one are not the same observation, whatever else they share. */
    @Test
    fun anUnobservedComputeChannelDoesNotAgreeWithAnObservedOne() {
        assertFalse(
            KernelProbeArtifact.Row(
                "mixed",
                listOf(
                    engine(KernelRuntimeProbe.GROOVY_DYNAMIC, valued("Order/LineTotal", "50.00")),
                    KernelProbeArtifact.Engine.of(KernelRuntimeProbe.JAVA_STATIC)
                        .messages(listOf(message("SAME"))).build(),
                ),
            ).enginesAgree(),
            "one strategy observed and the other not is not agreement about anything",
        )
    }

    // ---- acceptance case 2, at the representation: no pair of channels collapses ------------------

    /**
     * A cleared outcome and a computed empty string are different values and render differently: the cleared one
     * omits `value` entirely, the empty-string one writes `""`. Unreachable on kernel 30.8.1 (see the class
     * note) and asserted anyway, because the collapse it forbids would be undetectable once it were reachable.
     */
    @Test
    fun aClearAndAComputedEmptyStringAreDifferentValuesAndDifferentBytes() {
        val emptyString = KernelProbeArtifact.Outcome("Order/Label", Optional.of(""), false, false)
        val clearedOut = KernelProbeArtifact.Outcome("Order/Label", Optional.empty(), true, false)

        val rendered = mapper.readTree(
            artifact(
                KernelProbeArtifact.Row(
                    "r",
                    listOf(
                        KernelProbeArtifact.Engine.of(KernelRuntimeProbe.GROOVY_DYNAMIC)
                            .computations(Optional.of(KernelProbeArtifact.Computations(
                                listOf(emptyString, clearedOut), listOf(),
                            ))).build(),
                    ),
                ),
            ).toJson(),
        ).path("rows")[0].path("engines")[0].path("computations").path("outcomes")

        val (first, second) = rendered.toList()
        assertEquals("", first.path("value").asText(), "a computed empty string writes an empty value")
        assertFalse(first.path("cleared").asBoolean())
        assertTrue(second.path("value").isMissingNode, "a clear writes no value key at all")
        assertTrue(second.path("cleared").asBoolean())
    }

    // ---- acceptance case 6, at the rendering layer ------------------------------------------------

    /** Outcomes are put in a total order over their published channels, never the kernel's channel order. */
    @Test
    fun outcomeOrderIsTotalRatherThanKernelChannelOrder() {
        val computations = KernelProbeArtifact.Computations(
            listOf(
                KernelProbeArtifact.Outcome("Order/Total", Optional.of("9.00"), false, false),
                KernelProbeArtifact.Outcome("Order/Fee", Optional.empty(), true, false),
                KernelProbeArtifact.Outcome("Order/Fee", Optional.of("1.00"), false, true),
            ),
            listOf(message("B"), message("A")),
        )

        assertEquals(
            listOf("Order/Fee" to false, "Order/Fee" to true, "Order/Total" to false),
            computations.outcomes().map { it.field() to it.cleared() },
            "sorted by address first, then by the remaining channels, so two outcomes on one field are stable",
        )
        assertEquals(
            listOf("A", "B"), computations.formalErrorsInOperands().map { it.code() },
            "the operand errors are ordered exactly like the message channel",
        )
    }

    /**
     * The one tie the other ordering cases cannot reach, and the reason the comparator carries a value-PRESENCE
     * arm: two outcomes identical in `field`, `cleared` and `errored`, separated only by whether a value is
     * there at all. Without that arm the comparator ties, `sorted()` is stable, and the artifact's bytes fall
     * back to the kernel's channel emission order — the one order a SHA-256 pin may not depend on.
     *
     * Fed in both orders, because a comparator that ties would otherwise pass whichever order happens to be
     * written first.
     */
    @Test
    fun anAbsentValueAndAComputedEmptyStringDoNotTieInTheOutcomeOrder() {
        val absent = KernelProbeArtifact.Outcome("Order/Fee", Optional.empty(), false, false)
        val empty = KernelProbeArtifact.Outcome("Order/Fee", Optional.of(""), false, false)

        assertEquals(
            listOf(false, true),
            KernelProbeArtifact.Computations(listOf(empty, absent), listOf()).outcomes()
                .map { it.value().isPresent },
            "the absent value sorts ahead of the present empty one",
        )
        assertEquals(
            KernelProbeArtifact.Computations(listOf(empty, absent), listOf()).outcomes(),
            KernelProbeArtifact.Computations(listOf(absent, empty), listOf()).outcomes(),
            "…and the result is the same whichever order the kernel emitted them in, which is the whole point",
        )
    }

    // ---- the request key -------------------------------------------------------------------------

    /**
     * `observe` defaults to validation alone — the shape every request had before compute was observable, which
     * is what makes an unchanged request produce an unchanged artifact.
     */
    @Test
    fun observeDefaultsToValidationAloneAndAdmitsCompute() {
        assertEquals(
            setOf(KernelRuntimeProbe.Observation.VALIDATE_FULL), parse("").observe(),
            "an omitted key must not start observing anything new",
        )
        assertEquals(
            setOf(KernelRuntimeProbe.Observation.VALIDATE_FULL, KernelRuntimeProbe.Observation.COMPUTE),
            parse(""" "observe": ["validateFull", "compute"], """).observe(),
        )
    }

    /**
     * Dropping `validateFull` is refused rather than honoured, an unknown entry is named back, and an explicit
     * empty array is a mistake rather than the default.
     *
     * **Each refusal is asserted through BOTH gates** — the published schema a consumer validates against, and
     * the producer that actually runs — because the two are written independently and nothing else compares
     * them. A schema that advertised a constraint the producer did not enforce, or the reverse, is exactly the
     * "advertised but unenforced" defect; here the schema's `contains` / `minItems` and the parser's own checks
     * are held to the same three inputs.
     */
    @Test
    fun theSchemaAndTheProducerRefuseTheSameObserveMistakes() {
        val schema = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012).getSchema(requestSchema())

        fun requestJson(observe: String) = mapper.readTree(
            """{ "model": "m.dm.json", $observe "rows": [ { "name": "r", "documentInline": {} } ] }""",
        )
        listOf(
            """ "observe": ["compute"], """ to "dropping validateFull",
            """ "observe": [], """ to "an explicit empty array",
            """ "observe": ["validateFull", "validateWhole"], """ to "an unknown observation",
        ).forEach { (observe, what) ->
            assertTrue(schema.validate(requestJson(observe)).isNotEmpty(), "the schema must refuse $what")
            assertThrows(IllegalArgumentException::class.java, { parse(observe) }, "the producer must refuse $what")
        }

        // …and the refusals say why, rather than merely failing.
        val dropped = assertThrows(IllegalArgumentException::class.java) { parse(""" "observe": ["compute"], """) }
        assertTrue(
            "validateFull" in dropped.message.orEmpty() && "nothing fired" in dropped.message.orEmpty(),
            "the refusal must name the key and its reason: ${dropped.message}",
        )
        val unknown = assertThrows(IllegalArgumentException::class.java) {
            parse(""" "observe": ["validateFull", "validateWhole"], """)
        }
        assertTrue(
            "validateWhole" in unknown.message.orEmpty() && "compute" in unknown.message.orEmpty(),
            "an unknown observation must be named back, with the ones that exist: ${unknown.message}",
        )
    }

    /**
     * The `validateFull` invariant lives on the public [KernelRuntimeProbe.Request] itself, not only on the
     * file parser — otherwise a caller building a request in memory could produce the very artifact the refusal
     * exists to prevent, and the enforcement locus would not cover the type that advertises it.
     */
    @Test
    fun theRequestTypeRefusesAnObservationSetWithoutValidation() {
        listOf(
            EnumSet.of(KernelRuntimeProbe.Observation.COMPUTE),
            EnumSet.noneOf(KernelRuntimeProbe.Observation::class.java),
        ).forEach { observe ->
            val refused = assertThrows(IllegalArgumentException::class.java) {
                KernelRuntimeProbe.Request(
                    "m.dm.json", "{}", Locale.US, Optional.empty(), observe,
                    listOf(KernelRuntimeProbe.Row("r", "{}")),
                )
            }
            assertTrue(
                "validateFull" in refused.message.orEmpty(),
                "the domain rule must speak, not EnumSet.copyOf's 'Collection is empty': ${refused.message}",
            )
        }
    }

    /** The request schema must DEMONSTRATE the compute observation it advertises; validity is
     *  [KernelProbeArtifactTest.theRequestSchemaAcceptsItsOwnExample]'s job for every example. */
    @Test
    fun theRequestSchemaDemonstratesTheComputeObservation() {
        assertTrue(
            requestSchema().path("examples")
                .any { example -> example.path("observe").any { it.asText() == "compute" } },
            "an advertised key with no worked example is how a consumer learns it from prose instead",
        )
    }

    // ---- fixtures --------------------------------------------------------------------------------

    @TempDir
    lateinit var dir: Path

    private fun requestSchema() = mapper.readTree(
        javaClass.classLoader.getResource("kernel-probe/request.schema.json")!!.readText(),
    )

    private fun parse(observe: String): KernelRuntimeProbe.Request {
        Files.writeString(dir.resolve("m.dm.json"), "{}")
        val request = Files.writeString(
            dir.resolve("r.request.json"),
            """{ "model": "m.dm.json", $observe "rows": [ { "name": "r", "documentInline": {} } ] }""",
        )
        return KernelRuntimeProbe.parse(request)
    }

    private fun header() = KernelProbeArtifact.Header.builder()
        .dmtoolVersion("0.0.0-test").kernelBuiltAgainst("30.8.1").kernelRuntime("30.8.1")
        .sourceRevision("0000000000000000000000000000000000000000").sourceState("CLEAN").model("order.dm.json").locale("en_US").clock(Optional.of("2026-08-11T00:00:00Z"))
        .build()

    /** The value the pre-change producer rendered into the committed baseline — reproduced exactly. */
    private fun baselineRow(): KernelProbeArtifact.Row {
        val omission = KernelProbeArtifact.Message(
            "POLOMISSION", "Order/Quantity", "ERROR", "OMISSION_ERROR", "Quantity is missing.",
            listOf("Order/Quantity", "Order/BackorderQuantity"),
            listOf("Order/Quantity", "Order/BackorderQuantity"),
        )
        val value = KernelProbeArtifact.Message(
            "POLVALUE", "/Order[1]/Items[0]/Count", "ERROR", "VALUE_ERROR", "Count is too large.",
            listOf("/Order[1]/Items[0]/Count"), listOf(),
        )
        return KernelProbeArtifact.Row(
            "filled-and-unfilled",
            listOf(
                KernelProbeArtifact.Engine.of(KernelRuntimeProbe.GROOVY_DYNAMIC)
                    .messages(listOf(value, omission)).build(),
                KernelProbeArtifact.Engine.of(KernelRuntimeProbe.JAVA_STATIC)
                    .messages(listOf(omission, value)).build(),
            ),
        )
    }

    private fun baselineBytes(): String = javaClass.classLoader
        .getResource("kernel-probe/byte-compatibility-artifact.json")!!.readText()

    private fun sha256(text: String): String = MessageDigest.getInstance("SHA-256")
        .digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

    private fun message(code: String) = KernelProbeArtifact.Message(
        code, "Order/Quantity", "ERROR", "VALUE_ERROR", "x", listOf("Order/Quantity"), listOf(),
    )

    private fun valued(field: String, value: String) = KernelProbeArtifact.Computations(
        listOf(KernelProbeArtifact.Outcome(field, Optional.of(value), false, false)), listOf(),
    )

    private fun cleared(field: String) = KernelProbeArtifact.Computations(
        listOf(KernelProbeArtifact.Outcome(field, Optional.empty(), true, false)), listOf(),
    )

    private fun engine(name: String, computations: KernelProbeArtifact.Computations) =
        KernelProbeArtifact.Engine.of(name).messages(listOf(message("SAME")))
            .computations(Optional.of(computations)).build()

    private fun artifact(vararg rows: KernelProbeArtifact.Row) =
        KernelProbeArtifact(header(), rows.toList())
}
