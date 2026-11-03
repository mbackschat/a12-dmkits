package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.interpreter.DmInterpreter
import io.github.mbackschat.a12.dm.interpreter.PreparedModel
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
 * **Kernel 31.1.1 split the two halves (KERNEL-FINDINGS KF665).** The static verdict on the gap label still
 * follows the JVM default, but the runtime now resolves a gap wall time forward by the gap, so the gap label
 * computes `03:30`. The label with no instant at runtime is now the Julian leap day `29.02.1500T10:00:00`: its
 * calendar fallback is refused under Europe/Berlin and admitted under UTC, so a UTC default admits the model and
 * the declared zone then fails the constant on every row.
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

        /** A label Europe/Berlin cannot read at runtime: 1500 has no February 29 in the proleptic calendar, and the
         *  zone refuses the Julian fallback that UTC accepts (KERNEL-FINDINGS KF665). */
        const val JULIAN_LABEL = "29.02.1500T10:00:00"

        /** The control label — an ordinary instant in every zone under test. */
        const val ORDINARY_LABEL = "05.03.2024T12:30:00"

        const val ADMITTED = "ADMITTED"

        /** Pinned by the first run of this file; see the note at its assertion. */
        const val REFUSAL_CODE = "MVK_INVALID_COMPARE_TO_DATE"

        /** The kernel's own code for a computed target that resolved to no value, spelled rather than imported
         *  from our own constant: the claim is that our string equals the kernel's. */
        const val COMPUTED_VALUE_ERROR = "berechnungsWertFehler"

        /** The same document the [document] builder describes, as canonical A12 Document JSON — the public
         *  `readDocument` ingress, which is where a consumer meets the report IG147 was about. */
        const val FRESH_DOC = """{"Booking":{"Locator":"7KQ4ZB","Segments":[{},{}]}}"""

        /** A value the target already HOLDS, so the kernel's change projection has a clear to report and the
         *  outcome channel can be compared with nothing projected away. */
        const val SEEDED_VALUE = "2024-01-05T08:00:00"

        const val SEEDED_DOC =
            """{"Booking":{"Locator":"7KQ4ZB","Segments":[{"BoardsAt":"$SEEDED_VALUE"},{"BoardsAt":"$SEEDED_VALUE"}]}}"""
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
    private val julianModel = modelFor(JULIAN_LABEL)

    /**
     * The declared zone is the fact both halves rest on, so it is read out of the model bytes rather than
     * assumed from the builder's default. A model that quietly declared no zone would make the static half a
     * measurement of nothing and the runtime half a measurement of the host.
     */
    @Test
    fun bothModelsDeclareTheBerlinZoneTheClaimIsAbout() {
        for ((label, json) in listOf("gap" to gapModel, "ordinary" to ordinaryModel, "julian" to julianModel)) {
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
     * ordinary label computes its instant per row, the gap label the wall time one gap later, and the Julian label
     * produces no computed outcome at all and one addressed formal operand error per instantiated row.
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
        assertEquals(
            (1..ROWS).associate { row -> pointer(row) to "VALUE|2024-03-31T03:30:00" },
            gap.outcomes().associate { it.pointer() to signature(it) },
            "the gap label resolves forward by the gap, once per row",
        )
        assertEquals(emptyList<String>(), gap.formalErrorsInOperands().map { "${it.code()}@${it.field()}" },
            "and carries no operand finding")

        val julian = RuntimeLaws.computeWithFormalErrors(julianModel, document(julianModel))
        assertEquals(emptyMap<String, String>(), julian.outcomes().associate { it.pointer() to signature(it) },
            "an unresolvable wall label yields NO computed outcome — not a cleared one and not an errored one")
        assertEquals(
            (1..ROWS).map { row -> "berechnungsWertFehler@${pointer(row)}|VALUE_ERROR" },
            julian.formalErrorsInOperands().map { "${it.code()}@${it.field()}|${it.type().name}" }.sorted(),
            "the refusal rides the operand-error channel, addressed per in-capacity row",
        )
    }

    /**
     * Both kernel strategies agree on every label, and the interpreter agrees on the RESOLVABLE ones — which is
     * the half the literal-typing fix bought: before it, the interpreter stored the German text raw and flagged
     * every constant errored, whatever the zone (`adapter.laws.DateConstantTargetPolicyDiffTest` records the
     * same defect on the DATE carrier). The unresolvable label's outcome channel differs by the compute-granularity
     * projection, compared on a seeded target below.
     */
    @Test
    fun bothKernelStrategiesAgreeAndTheInterpreterMatchesTheResolvableLabels() = underDefault(UTC) {
        for ((label, model) in listOf("ordinary" to ordinaryModel, "gap" to gapModel, "julian" to julianModel)) {
            val groovy = outcomes(model)
            val engine = RuntimeLaws.kernelJavaEngine(model)
            val static = engine.compute(engine.instance(document(model)))
                .associate { "${it.path()}@${it.pointer()}" to signature(it) }
            assertEquals(groovy, static, "$label — generated Java vs normative dynamic-Groovy")
        }
        assertEquals(interpreterOutcomes(ordinaryModel), outcomes(ordinaryModel),
            "ordinary label — interpreter vs normative dynamic-Groovy")
        assertEquals(interpreterOutcomes(gapModel), outcomes(gapModel),
            "gap label — interpreter vs normative dynamic-Groovy")
    }

    /**
     * **The parity that closed IG147: an unresolvable label is a NO-VALUE operand error on both estates.**
     * The kernel cannot resolve the label to an instant at all, so there is no value to store — structurally the
     * numeric domain failure ([DivisionDomainFailureComputationDiffTest]), and this evaluator now says the same
     * thing on the same channel: one `berechnungsWertFehler` at the target per in-capacity row, and no value.
     *
     * **The discriminator that keeps this narrow, and it is measured in this very file's sibling.** A pre-1900
     * Date constant is ERRORED *retaining* its rendered attempt (`DateConstantTargetPolicyDiffTest`, KF231), so
     * "a temporal constant that fails its target check has no value" is FALSE. Only a label with no instant is
     * a no-value failure; the target-policy refusal keeps store-and-flag.
     *
     * **What the outcome channel does and does not compare here.** The kernel reports no outcome for a target it
     * never valued; report-all gives one no-value outcome per instance, which is the standing compute-granularity
     * ruling rather than a divergence — the same pair the divide-by-zero law records. The next test removes that
     * projection entirely by seeding the target.
     */
    @Test
    fun theUnresolvableLabelIsANoValueOperandErrorOnBothEstates() = underDefault(UTC) {
        val kernel = RuntimeLaws.computeWithFormalErrors(julianModel, document(julianModel))
        assertEquals(emptyMap<String, String>(), kernel.outcomes().associate { it.pointer() to signature(it) },
            "the kernel gives an unresolvable wall label no computed outcome at all")

        val expected = (1..ROWS).map { row -> "$COMPUTED_VALUE_ERROR@${pointer(row)}" }
        assertEquals(expected, kernel.formalErrorsInOperands().map { "${it.code()}@${it.field()}" }.sorted(),
            "the kernel's own operand channel, addressed per in-capacity row")
        assertEquals(expected, interpreterOperandSignatures(julianModel, FRESH_DOC),
            "the whole of IG147: our PUBLIC report must name the same code at the same cells")

        assertEquals(
            (1..ROWS).associate { row -> pointer(row) to "CLEARED|<none>" },
            interpreterResults(julianModel, FRESH_DOC),
            "report-all keeps one no-value outcome per instance — never a stored attempt",
        )
    }

    /**
     * **The same fact with the granularity projection removed.** A target that already HOLDS a value and now
     * computes none is a change the kernel's own projection reports, so both estates speak about the outcome
     * channel on this document and it is compared directly. The divide-by-zero law uses this arm for the
     * identical reason — it is what makes the parity claim a measurement rather than a projection.
     */
    @Test
    fun aSeededTargetIsClearedByBothEstatesForAnUnresolvableLabel() = underDefault(UTC) {
        val kernel = RuntimeLaws.computeWithFormalErrors(julianModel, seededDocument())
        assertEquals(
            (1..ROWS).associate { row -> pointer(row) to "CLEARED|<none>" },
            kernel.outcomes().associate { it.pointer() to signature(it) },
            "the kernel clears a seeded target whose computation resolves to no value",
        )
        assertEquals(
            (1..ROWS).associate { row -> pointer(row) to "CLEARED|<none>" },
            interpreterResults(julianModel, SEEDED_DOC),
            "interpreter vs dynamic-Groovy on the outcome channel, unprojected",
        )
    }

    /** The RESOLVABLE label on the same seeded document — the control proving the clear above is the Julian
     *  label's doing and not something the seed causes on its own. */
    @Test
    fun aSeededTargetKeepsItsComputedValueForTheResolvableLabel() = underDefault(UTC) {
        assertEquals(
            (1..ROWS).associate { row -> pointer(row) to "VALUE|2024-03-05T12:30:00" },
            interpreterResults(ordinaryModel, SEEDED_DOC),
            "the control label still computes its instant over a seeded target",
        )
        assertEquals(emptyList<String>(), interpreterOperandSignatures(ordinaryModel, FRESH_DOC),
            "and carries no operand finding, or the gap row's finding says nothing")
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

    /**
     * The operand channel on our own PUBLIC report, at `code@pointer`. It goes through `readDocument` rather
     * than the hand-built route on purpose: IG147 was a claim about what a consumer sees on `compute()`, and
     * the hand-built document is a route hybrid (IG143).
     */
    private fun interpreterOperandSignatures(model: String, documentJson: String): List<String> =
        publicReport(model, documentJson).formalErrorsInOperands
            .map { "${it.code}@${formatPointer(it.errorPath.fullName, it.errorPath.repetitionIndexes)}" }
            .sorted()

    /** The report-all outcome channel on the same public report, keyed by the kernel's own pointer spelling. */
    private fun interpreterResults(model: String, documentJson: String): Map<String, String> =
        publicReport(model, documentJson).results.associate {
            formatPointer(it.pointer.path, it.pointer.coordinates) to
                "${it.outcome.name}|${it.value ?: "<none>"}"
        }

    private fun publicReport(model: String, documentJson: String) =
        PreparedModel.loadExpandedJson(model).let { prepared ->
            prepared.createInterpreter().compute(prepared.documents.readDocument(documentJson))
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

    /** [document] with the computed target already holding [SEEDED_VALUE] at every row — the kernel's change
     *  projection reports a clear only for a cell that HELD something. */
    private fun seededDocument(): RuntimeLaws.Doc {
        val d = RuntimeLaws.doc().group(BOOKING, 1).field(LOCATOR, "7KQ4ZB", 1, 1)
        for (row in 1..ROWS) {
            d.group(SEGMENTS, 1, row)
            d.field(BOARDS_AT, SEEDED_VALUE, 1, row, 1)
        }
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