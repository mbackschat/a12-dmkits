package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.interpreter.DmInterpreter
import io.github.mbackschat.a12.dm.interpreter.eval.formatPointer
import io.github.mbackschat.a12.dm.interpreter.eval.newDocument
import io.github.mbackschat.a12.dm.interpreter.load.ModelLoader
import io.github.mbackschat.a12.dm.interpreter.model.ComputeOutcome
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.util.TimeZone

/**
 * Tri-engine differential — a DateTime CONSTANT is judged twice, against two different zones.
 * a12-kernel-lean's `SPEC-2026-09-01-02`: with the model bytes and its declared `Europe/Berlin` zone held
 * fixed, the STATIC verdict for a spring-forward gap label follows the **JVM default** zone, while the runtime
 * resolves the admitted label against the **model** zone.
 *
 * **What was already ours and what is new.** [TemporalConstantLiteralWitnessLawsTest] witnesses the DateTime
 * literal's spelling and its store (`KF223`), on a label that resolves in every zone. This file adds the label
 * that does not: `31.03.2024T02:30:00` names a wall time Europe/Berlin skips entirely — the 2024 transition
 * moves 02:00 straight to 03:00 — while under a UTC default the same text is an ordinary instant.
 *
 * **Why this test moves a JVM global, and why that is safe here.** The static half's claim is precisely that the
 * verdict depends on `TimeZone.getDefault()`, so it cannot be measured without varying it; `:adapter` disables
 * JUnit parallelism inside every fork (`junit.jupiter.execution.parallel.enabled=false`), so nothing else runs
 * while the default is swapped, and each swap is undone in a `finally`. `RuntimeLaws.consistencyMessages` builds
 * a fresh service per call, so neither arm can read a verdict another zone cached.
 *
 * **The grid is 2×2 because one cell proves nothing.** The gap label refused under Berlin is consistent with the
 * label simply being unauthorable; the ordinary label admitted under both zones, and the gap label admitted
 * under UTC, are what make the JVM default the switch and the label the subject.
 *
 * Own domain (long-distance rail reservations).
 */
// One instance per CLASS: these files hold their model JSON, the loaded definition and a kernel Java
// engine as fields, and this Gradle task also carries a clock-reading assertion. Per-method lifecycle
// would redo codegen and model loading on every case for no gain (CLAUDE.md: a new test must not
// perturb a timing floor sharing its task).
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DateTimeConstantZoneSplitDiffTest {

    private companion object {
        const val ISO_DT = "yyyy-MM-dd'T'HH:mm:ss"
        const val BOOKING = "/Booking"
        const val LOCATOR = "/Booking/Locator"
        const val SEGMENTS = "/Booking/Segments"
        const val BOARDS_AT = "/Booking/Segments/BoardsAt"
        const val ROWS = 2

        /** A wall time Europe/Berlin has no instant for: 2024-03-31 goes 02:00 → 03:00. */
        const val GAP_LABEL = "31.03.2024T02:30:00"

        /** The control label — an ordinary instant in every zone under test. */
        const val ORDINARY_LABEL = "05.03.2024T12:30:00"

        const val ADMITTED = "ADMITTED"

        /** Pinned by the first run of this file; see the note at its assertion. */
        const val REFUSAL_CODE = "MVK_INVALID_COMPARE_TO_DATE"
        val UTC: TimeZone = TimeZone.getTimeZone("UTC")
        val BERLIN: TimeZone = TimeZone.getTimeZone("Europe/Berlin")
    }

    /** One model per literal, identical but for the constant — so the literal is the only model-side variable. */
    private fun modelFor(literal: String): String {
        val base = DocumentModelDraft.named("rail-booking-$literal").locale("en_US")
            .rootGroup("Booking") { booking ->
                booking.stringField("Locator") { }
                booking.group("Segments") { segments ->
                    segments.repeatable(ROWS)
                    segments.dateTimeField("BoardsAt") { it.format(ISO_DT) }
                    segments.stringField("Coach") { }
                }
            }.build().toJson()
        val bound = BoundModel.ofJson(base)
        return TypedRules.inject(
            base,
            bound.computation(bound.dateTime(BOARDS_AT).field()).name("BoardingComp")
                .calc(Dsl.dateTimeValue(literal).expression())
                .message(Dsl.en("The scheduled boarding time for this segment.")).build(),
        )
    }

    private val gapModel = modelFor(GAP_LABEL)
    private val ordinaryModel = modelFor(ORDINARY_LABEL)

    /**
     * The declared zone is the fact both halves rest on, so it is read out of the model bytes rather than
     * assumed from the builder's default. A model that quietly declared no zone would make the static half a
     * measurement of nothing and the runtime half a measurement of the host.
     */
    @Test
    fun bothModelsDeclareTheBerlinZoneTheClaimIsAbout() {
        for ((label, json) in listOf("gap" to gapModel, "ordinary" to ordinaryModel)) {
            assertTrue(Regex("\"timeZone\"\\s*:\\s*\"Europe/Berlin\"").containsMatchIn(json),
                "$label model must declare Europe/Berlin, or neither half of this file measures a zone split")
        }
    }

    @Test
    fun theStaticVerdictForAGapLabelFollowsTheJvmDefaultZoneAndNotTheDeclaredOne() {
        val actual = linkedMapOf(
            "gap label · UTC default" to verdictUnder(UTC, gapModel),
            "gap label · Berlin default" to verdictUnder(BERLIN, gapModel),
            "ordinary label · UTC default" to verdictUnder(UTC, ordinaryModel),
            "ordinary label · Berlin default" to verdictUnder(BERLIN, ordinaryModel),
        )
        assertEquals(ADMITTED, actual["ordinary label · UTC default"],
            "the control label must be admitted, or the refusal below is not about the gap")
        assertEquals(ADMITTED, actual["ordinary label · Berlin default"],
            "the control label must be admitted under the declared zone too")
        assertEquals(ADMITTED, actual["gap label · UTC default"],
            "under a UTC default the gap label names an ordinary instant and is admitted")
        // Pinned rather than asserted as "not ADMITTED": a bare inequality would also pass if the model became
        // unloadable for an unrelated reason, which is the one way this row could report the claim while
        // measuring something else.
        assertEquals(REFUSAL_CODE, actual["gap label · Berlin default"],
            "the whole claim: with the model bytes fixed, a Berlin JVM default refuses the label a UTC one admits")
    }

    /**
     * Under the admitting JVM default the model loads, and the runtime then applies the DECLARED zone: the
     * ordinary label computes its instant per row while the gap label produces no computed outcome at all and
     * one addressed formal operand error per instantiated row.
     */
    @Test
    fun underAnAdmittingJvmDefaultTheRuntimeStillJudgesTheLabelAgainstTheDeclaredZone() = underDefault(UTC) {
        val ordinary = RuntimeLaws.computeWithFormalErrors(ordinaryModel, document(ordinaryModel))
        assertEquals(
            (1..ROWS).associate { row -> pointer(row) to "VALUE|2024-03-05T12:30:00" },
            ordinary.outcomes().associate { it.pointer() to signature(it) },
            "the ordinary label stores in the target's declared format, once per row",
        )
        assertEquals(emptyList<String>(), ordinary.formalErrorsInOperands().map { "${it.code()}@${it.field()}" },
            "the control must carry no operand finding, or the gap row's finding says nothing")

        val gap = RuntimeLaws.computeWithFormalErrors(gapModel, document(gapModel))
        assertEquals(emptyMap<String, String>(), gap.outcomes().associate { it.pointer() to signature(it) },
            "an unresolvable wall label yields NO computed outcome — not a cleared one and not an errored one")
        assertEquals(
            (1..ROWS).map { row -> "berechnungsWertFehler@${pointer(row)}|VALUE_ERROR" },
            gap.formalErrorsInOperands().map { "${it.code()}@${it.field()}|${it.type().name}" }.sorted(),
            "the refusal rides the operand-error channel, addressed per in-capacity row",
        )
    }

    /**
     * Both kernel strategies agree on both labels, and the interpreter agrees on the RESOLVABLE one — which is
     * the half the literal-typing fix bought: before it, the interpreter stored the German text raw and flagged
     * every constant errored, whatever the zone (`adapter.laws.DateConstantTargetPolicyDiffTest` records the
     * same defect on the DATE carrier).
     */
    @Test
    fun bothKernelStrategiesAgreeAndTheInterpreterMatchesTheResolvableLabel() = underDefault(UTC) {
        for ((label, model) in listOf("ordinary" to ordinaryModel, "gap" to gapModel)) {
            val groovy = outcomes(model)
            val engine = RuntimeLaws.kernelJavaEngine(model)
            val static = engine.compute(engine.instance(document(model)))
                .associate { "${it.path()}@${it.pointer()}" to signature(it) }
            assertEquals(groovy, static, "$label — generated Java vs normative dynamic-Groovy")
        }
        assertEquals(interpreterOutcomes(ordinaryModel), outcomes(ordinaryModel),
            "ordinary label — interpreter vs normative dynamic-Groovy")
    }

    /**
     * **A characterized divergence, asserted rather than excluded.** For the UNRESOLVABLE label the two estates
     * classify the same fact differently: the kernel produces NO computed outcome and routes the finding to the
     * operand channel, while the interpreter reports the target as ERRORED carrying the canonicalized attempt.
     * Both agree the cell has no valid value; they disagree about which channel says so, and that is visible to
     * a consumer reading `compute()`.
     *
     * It is pinned here — including the exact interpreter shape — so the divergence cannot widen unnoticed and
     * so the row is not silently dropped from the tri-engine comparison above. Closing it is a decision about
     * our public compute contract (whether `compute()` grows an operand-error channel or reclassifies this
     * case), which is why it is filed rather than patched: `INTERPRETER-GAPS` owns it.
     */
    @Test
    fun theUnresolvableLabelIsClassifiedDifferentlyByTheTwoEstates() = underDefault(UTC) {
        assertEquals(emptyMap<String, String>(), outcomes(gapModel),
            "the kernel reports no computed outcome at all for an unresolvable wall label")
        assertEquals(
            (1..ROWS).associate { row -> "$BOARDS_AT@${pointer(row)}" to "ERRORED|2024-03-31T02:30:00" },
            interpreterOutcomes(gapModel),
            "the interpreter's classification, pinned so the divergence cannot widen unnoticed",
        )
    }

    // ---- the instruments ----

    private fun outcomes(model: String): Map<String, String> =
        RuntimeLaws.compute(model, document(model))
            .associate { "${it.path()}@${it.pointer()}" to signature(it) }

    private fun interpreterOutcomes(model: String): Map<String, String> {
        val definition = ModelLoader.load(model)
        return DmInterpreter(definition).compute(interpreterDocument(definition))
            .associate { "${it.path}@${formatPointer(it.path, it.indices)}" to signature(it) }
    }

    /** `ADMITTED`, or the distinct MVK codes the consistency oracle reported, with [zone] as the JVM default. */
    private fun verdictUnder(zone: TimeZone, model: String): String = underDefault(zone) {
        val notifications = RuntimeLaws.consistencyMessages(model)
        if (notifications.isEmpty()) ADMITTED
        else notifications.mapNotNull { CODE.find(it)?.groupValues?.get(1) }.distinct().sorted()
            .joinToString(", ").ifEmpty { notifications.joinToString(", ") }
    }

    /** Runs [body] with [zone] as the JVM default, restoring the previous default whatever happens. */
    private fun <T> underDefault(zone: TimeZone, body: () -> T): T {
        val previous = TimeZone.getDefault()
        TimeZone.setDefault(zone)
        try {
            return body()
        } finally {
            TimeZone.setDefault(previous)
        }
    }

    private fun signature(c: RuntimeLaws.Computed): String {
        val bucket = if (c.cleared()) "CLEARED" else if (c.errored()) "ERRORED" else "VALUE"
        return "$bucket|${c.value().orElse("<none>")}"
    }

    private fun signature(c: io.github.mbackschat.a12.dm.interpreter.model.Computed): String {
        val bucket = when (c.outcome) {
            ComputeOutcome.CLEARED -> "CLEARED"
            ComputeOutcome.ERRORED -> "ERRORED"
            ComputeOutcome.VALUE -> "VALUE"
        }
        return "$bucket|${c.value ?: "<none>"}"
    }

    private fun pointer(row: Int) = "/Booking[1]/Segments[$row]/BoardsAt"

    private fun document(model: String): RuntimeLaws.Doc {
        val d = RuntimeLaws.doc().group(BOOKING, 1).field(LOCATOR, "7KQ4ZB", 1, 1)
        for (row in 1..ROWS) d.group(SEGMENTS, 1, row)
        return d
    }

    private fun interpreterDocument(
        definition: io.github.mbackschat.a12.dm.interpreter.model.ModelDef,
    ): io.github.mbackschat.a12.dm.interpreter.eval.Document {
        val d = definition.newDocument().group(BOOKING, 1).field(LOCATOR, "7KQ4ZB", 1, 1)
        for (row in 1..ROWS) d.group(SEGMENTS, 1, row)
        return d
    }
}

private val CODE = Regex("\\[(MVK_[A-Z_0-9]+)]")