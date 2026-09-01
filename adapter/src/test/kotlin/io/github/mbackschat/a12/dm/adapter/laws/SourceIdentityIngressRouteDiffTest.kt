package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.interpreter.ComputationResult
import io.github.mbackschat.a12.dm.interpreter.PreparedModel
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import io.github.mbackschat.a12.dm.modelkit.FieldKind
import io.github.mbackschat.a12.dm.rulekit.dsl.BoolLiteral
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl
import io.github.mbackschat.a12.dm.rulekit.dsl.NumberLiteral
import io.github.mbackschat.a12.dm.rulekit.dsl.StringLiteral
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * §11 source identity — **which ingress route a document came through decides whether a source-identical
 * computed value counts as a change**, and the answer is per-KIND, not per-operator.
 *
 * A12 classifies a successful result as changed by comparing the stored and computed V2 OBJECTS. Its typed
 * ingress converts text to `String | Boolean | BigDecimal | Instant | InstantRange`; its V1 String factory
 * leaves a typed cell holding the unconverted text. So on the V1 route every NON-STRING kind is
 * unconditionally changed — the stored String can never equal a converted value — while on the typed route the
 * converted values compare and an identical source is a silent unchanged success. STRING is the polarity
 * control: its converted value IS a String, so it is unchanged on both routes.
 *
 * This is the measurement that retired `IG142`. The interpreter once answered `Kind.TIME -> false`
 * unconditionally, calibrated against a V1-String seed and mistaken for a property of Time; the interpreter's
 * only public ingress is `readDocument`, the TYPED route, so the special case made a re-apply rewrite a cell
 * the Kernel leaves alone. Time is not special, and the two arms below prove that by moving exactly one
 * variable: the same model, the same seed text, two routes.
 *
 * Own domain: an insurance claim intake whose derived cells are all seeded with what they compute to.
 */
class SourceIdentityIngressRouteDiffTest {

    private val model = run {
        val base = BoundModel.ofJson(
            DocumentModelDraft.named("claim-source-identity").locale("en_US")
                .rootGroup("Claim") { claim ->
                    claim.stringField("Segment") { }
                        .numberField("Deductible") { it.fractionDigits(2, 2) }
                        .dateField("IncidentDate") { it.format("dd.MM.yyyy") }
                        .dateTimeField("ReportedAt") { it.format("yyyy-MM-dd'T'HH:mm:ss") }
                        .timeField("SlotTime") { it.format("HH:mm:ss") }
                        .dateField("WindowStart") { it.format("dd.MM.yyyy") }
                        .dateField("WindowEnd") { it.format("dd.MM.yyyy") }
                        .dateRangeField("ReviewWindow") { it.format("dd.MM.yyyy").separator("-") }
                        .field("Escalated", FieldKind.BOOLEAN)
                        .field("Acknowledged", FieldKind.CONFIRM)
                }
                .build().toJson(),
        )
        // One computation per kind. Literals where the DSL has them; DateRange requires entity operands, so its
        // bounds are the two declared Date fields.
        val computations = listOf(
            base.computation(base.str(SEGMENT)).name("SegmentComp")
                .calc(StringLiteral("LEAD")).message(Dsl.en("Segment is derived.")).build(),
            base.computation(base.num(DEDUCTIBLE)).name("DeductibleComp")
                .calc(NumberLiteral.of("7.00")).message(Dsl.en("Deductible is derived.")).build(),
            base.computation(base.date(INCIDENT_DATE)).name("IncidentDateComp")
                .calc(Dsl.dateValue("15.06.2024").expression())
                .message(Dsl.en("Incident date is derived.")).build(),
            base.computation(base.dateTime(REPORTED_AT)).name("ReportedAtComp")
                .calc(Dsl.dateTime(Dsl.dateValue("15.06.2024"), Dsl.timeValue("10:30:45")).expression())
                .message(Dsl.en("Reported timestamp is derived.")).build(),
            base.computation(base.time(SLOT_TIME)).name("SlotTimeComp")
                .calc(Dsl.timeFromDateTime(base.dateTime(REPORTED_AT)).expression())
                .message(Dsl.en("Slot time is derived.")).build(),
            base.computation(base.bool(ESCALATED)).name("EscalatedComp")
                .calc(BoolLiteral(true)).message(Dsl.en("Escalation is derived.")).build(),
            // CONFIRM takes only True — the asymmetry is measured by
            // validate.laws.ConfirmComputationSourceKindLawsTest.
            base.computation(base.confirm(ACKNOWLEDGED)).name("AcknowledgedComp")
                .calc(BoolLiteral(true)).message(Dsl.en("Acknowledgement is derived.")).build(),
            base.computation(base.dateRange(REVIEW_WINDOW)).name("ReviewWindowComp")
                .calc(Dsl.dateRange(base.date(WINDOW_START), base.date(WINDOW_END)).expression())
                .message(Dsl.en("Review window is derived.")).build(),
        )
        base.addAll(emptyList(), computations).toJson()
    }

    /** Every computed cell already holds exactly what its computation produces. */
    private fun identicalSeed() = seed(
        segment = "LEAD", deductible = "7.00", incident = "15.06.2024",
        reported = "2024-06-15T10:30:45", slot = "10:30:45", window = "01.06.2024-30.06.2024",
        escalated = "true", acknowledged = "true",
    )

    /** The same shape with every computed cell stale — the polarity control for the empty expectations. */
    private fun staleSeed() = seed(
        segment = "BULK", deductible = "9.50", incident = "01.01.2024",
        reported = "2024-01-01T08:00:00", slot = "08:00:00", window = "01.01.2024-31.01.2024",
        // CONFIRM has no false: an UNFILLED cell is its only stale state, so it is left absent here.
        escalated = "false", acknowledged = null,
    )

    private fun seed(
        segment: String,
        deductible: String,
        incident: String,
        reported: String,
        slot: String,
        window: String,
        escalated: String,
        acknowledged: String?,
    ) = RuntimeLaws.doc().group(ROOT, 1)
        .field(SEGMENT, segment, 1, 1)
        .field(DEDUCTIBLE, deductible, 1, 1)
        .field(INCIDENT_DATE, incident, 1, 1)
        .field(REPORTED_AT, reported, 1, 1)
        .field(SLOT_TIME, slot, 1, 1)
        .field(WINDOW_START, "01.06.2024", 1, 1)
        .field(WINDOW_END, "30.06.2024", 1, 1)
        .field(REVIEW_WINDOW, window, 1, 1)
        .field(ESCALATED, escalated, 1, 1)
        .also { doc -> acknowledged?.let { doc.field(ACKNOWLEDGED, it, 1, 1) } }

    @Test
    fun theV1StringIngressLeavesEveryNonStringKindUnconditionallyChanged() {
        val (dynamic, static) = kernelChannels(identicalSeed(), typed = false)
        assertEquals(dynamic.first, static.first, "changed subset — dynamic Groovy vs generated Java")
        assertEquals(
            listOf(ACKNOWLEDGED, DEDUCTIBLE, ESCALATED, INCIDENT_DATE, REPORTED_AT, REVIEW_WINDOW, SLOT_TIME)
                .map(::pointer),
            dynamic.first,
            "on the V1 String route a typed cell holds unconverted text, so every non-STRING kind is changed "
                + "even though the seed equals the computed value; STRING is unchanged because its converted "
                + "value IS a String",
        )
        assertEquals(ALL_COMPUTED.map(::pointer), dynamic.second,
            "the control: every computation ran and succeeded, so an absent entry is equality and not a "
                + "missing result")
    }

    @Test
    fun theTypedIngressComparesConvertedValuesSoAnIdenticalSourceIsUnchanged() {
        val (dynamic, static) = kernelChannels(identicalSeed(), typed = true)
        assertEquals(dynamic.first, static.first, "changed subset — dynamic Groovy vs generated Java")
        assertEquals(emptyList<String>(), dynamic.first,
            "the ONE variable that moved is the ingress route: same model, same seed text, and now every kind "
                + "compares equal — including TIME, which is what retired IG142")
        assertEquals(ALL_COMPUTED.map(::pointer), dynamic.second,
            "the control: all six computations still ran and succeeded on the typed route")
    }

    @Test
    fun aStaleSourceIsChangedOnBothRoutesAndForEveryKind() {
        listOf(false to "V1 String", true to "typed V2").forEach { (typed, label) ->
            val (dynamic, static) = kernelChannels(staleSeed(), typed = typed)
            assertEquals(dynamic.first, static.first, "$label — dynamic Groovy vs generated Java")
            assertEquals(ALL_COMPUTED.map(::pointer), dynamic.first,
                "$label: a genuinely stale cell is changed for every kind, STRING included — without this the "
                    + "empty expectation above could be an empty channel read")
        }
    }

    @Test
    fun theInterpretersPublicTransportMatchesTheTypedRoute() {
        val prepared = PreparedModel.loadExpandedJson(model)
        val interpreter = prepared.createInterpreter()

        val identical = interpreter.compute(prepared.documents.readDocument(canonicalJson(identicalSeed())))
        assertEquals(ALL_COMPUTED.map(::leafName), identical.results.map(::leaf).sorted(),
            "the interpreter reports all six results")
        assertEquals(emptyList<String>(), identical.actions.map(::leaf).sorted(),
            "and retains no action for any of them — the typed route's answer")

        val stale = interpreter.compute(prepared.documents.readDocument(canonicalJson(staleSeed())))
        assertEquals(ALL_COMPUTED.map(::leafName), stale.actions.map(::leaf).sorted(),
            "the polarity control: a stale document retains every kind")
    }

    @Test
    fun aReapplyOverTheInterpretersOwnOutputRetainsNothingForAnyKind() {
        val prepared = PreparedModel.loadExpandedJson(model)
        val interpreter = prepared.createInterpreter()
        val stale = prepared.documents.readDocument(canonicalJson(staleSeed()))
        val applied = interpreter.compute(stale).applyTo(stale)

        val second = interpreter.compute(applied)
        assertEquals(ALL_COMPUTED.map(::leafName), second.results.map(::leaf).sorted(),
            "the second pass ran every computation")
        assertEquals(emptyList<String>(), second.actions.map(::leaf).sorted(),
            "a cell holding this computation's own previous output is source-identical for every kind")
    }

    /** `(changed, clean)` from both kernel strategies, on the chosen ingress route. */
    private fun kernelChannels(
        doc: RuntimeLaws.Doc,
        typed: Boolean,
    ): Pair<Pair<List<String>, List<String>>, Pair<List<String>, List<String>>> {
        val dynamic = RuntimeLaws.kernelEngine(model)
        val java = RuntimeLaws.kernelJavaEngine(model)
        val dynamicChannels =
            if (typed) dynamic.resultChannels(RuntimeLaws.documentV2(model, canonicalJson(doc)))
            else dynamic.resultChannels(dynamic.instance(doc))
        val staticChannels =
            if (typed) java.resultChannels(RuntimeLaws.documentV2(model, canonicalJson(doc)))
            else java.resultChannels(java.instance(doc))
        return Pair(
            dynamicChannels.withChanges().sorted() to dynamicChannels.withoutErrors().sorted(),
            staticChannels.withChanges().sorted() to staticChannels.withoutErrors().sorted(),
        )
    }

    private fun canonicalJson(doc: RuntimeLaws.Doc) = RuntimeLaws.canonicalDocumentJson(model, doc)

    private fun pointer(path: String) = "$ROOT[1]/${path.substringAfterLast('/')}"

    private fun leafName(path: String) = path.substringAfterLast('/')

    private fun leaf(result: ComputationResult) = result.pointer.path.substringAfterLast('/')

    private companion object {
        const val ROOT = "/Claim"
        const val SEGMENT = "$ROOT/Segment"
        const val DEDUCTIBLE = "$ROOT/Deductible"
        const val INCIDENT_DATE = "$ROOT/IncidentDate"
        const val REPORTED_AT = "$ROOT/ReportedAt"
        const val SLOT_TIME = "$ROOT/SlotTime"
        const val WINDOW_START = "$ROOT/WindowStart"
        const val WINDOW_END = "$ROOT/WindowEnd"
        const val REVIEW_WINDOW = "$ROOT/ReviewWindow"
        const val ESCALATED = "$ROOT/Escalated"
        const val ACKNOWLEDGED = "$ROOT/Acknowledged"

        /** Sorted by leaf name, matching the channels' own ordering. */
        val ALL_COMPUTED = listOf(
            ACKNOWLEDGED, DEDUCTIBLE, ESCALATED, INCIDENT_DATE, REPORTED_AT, REVIEW_WINDOW, SEGMENT, SLOT_TIME,
        )
    }
}
