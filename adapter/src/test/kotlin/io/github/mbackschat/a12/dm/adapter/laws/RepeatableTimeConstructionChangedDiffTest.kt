package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.edit.EditSession
import io.github.mbackschat.a12.dm.interpreter.load.ModelLoader
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Differential — a REPEATABLE constant `Time(...)` construction uses ORDINARY row-local source equality
 * (SPEC-2026-08-27-03, reconciled from a12-kernel-lean).
 *
 * **The placement SPLIT that entry proposes does not exist, and the correction runs the other way.** Its delta
 * is confirmed here; what is refuted is the premise it inherited from OUR
 * [ComputedTimeTargetDiffTest] — that nonrepeatable construction retains an always-changed action. Measured on
 * both strategies, a re-apply over the computation's own output reports NO change at EITHER placement, while a
 * hand-seeded identical clock is reported as changed at both. So the discriminating variable is how the stored
 * cell came to be there, not where the target sits, and our always-changed claim rested on a seeded document.
 *
 * The INTERPRETER half is measured in its own module and now AGREES:
 * `ComputedTimeTargetTest.aReapplyOverTheInterpretersOwnAppliedClockRetainsNothing`. It used to emit a `VALUE`
 * action here, which was `IG142`, retired once the mechanism below was measured.
 *
 * **The mechanism is the INGRESS ROUTE, and it is measured** by
 * [SourceIdentityIngressRouteDiffTest]: A12 compares the stored and computed V2 objects, its V1 String factory
 * leaves a typed cell holding unconverted text, and so every non-STRING kind is unconditionally changed on that
 * route while the typed route compares converted values. Nothing about Time. *(An earlier note here published
 * a REFUTATION of the text-form candidate on the grounds that the carrier form `1970-01-01T09:30:45Z` is also
 * reported as changed. That control moved two variables — a `HH:mm:ss` field cannot convert that spelling
 * either — so it distinguished nothing, and the claim is withdrawn rather than merely superseded.)*
 *
 * The method rule it leaves behind is unchanged: a seeded value cannot establish source identity, so an
 * apply-fidelity claim must be built on a re-apply.
 *
 * Own-domain (an insurance portfolio whose task slots carry a constructed appointment clock).
 */
class RepeatableTimeConstructionChangedDiffTest {

    private companion object {
        const val SLOT = "/Portfolio/Policy/Tasks/SlotTime"
        /** `Time("5", "2", "9")` — the kernel pads each component, so the stored clock is this exact text. */
        const val CONSTRUCTED = "05:02:09"
    }

    private val json: String by lazy {
        val draft = DocumentModelDraft.named("appointment-slots").locale("en_US")
            .rootGroup("Portfolio") { p ->
                p.group("Policy") { policy ->
                    policy.repeatable(5).group("Tasks") { t ->
                        t.repeatable(5).timeField("SlotTime") { it.format("HH:mm:ss") }
                    }
                }
            }.build()
        val edited = EditSession.read(draft.toJson()).also { it.config().timeZone("Europe/Berlin") }
        val bound = BoundModel.ofJson(edited.toJson())
        TypedRules.inject(edited.toJson(), bound.computation(bound.time(SLOT).field()).name("SlotClock")
            .calc(Dsl.time("5", "2", "9").expression())
            .message(Dsl.en("The slot clock is 05:02:09."))
            .build())
    }

    private val def by lazy { ModelLoader.load(json) }
    private val javaEngine by lazy { RuntimeLaws.kernelJavaEngine(json) }

    /** The changed projection on BOTH strategies — they must agree, and the agreed set is the observation. */
    private fun changed(build: (RuntimeLaws.Doc) -> Unit): List<String> {
        val dynamicDoc = RuntimeLaws.doc().also(build)
        val dynamic = RuntimeLaws.resultChannels(json, dynamicDoc).withChanges().sorted()
        val staticDoc = RuntimeLaws.doc().also(build)
        val generated = javaEngine.resultChannels(javaEngine.instance(staticDoc)).withChanges().sorted()
        assertEquals(dynamic, generated, "groovy-dynamic vs java-static changed projection")
        return dynamic
    }

    /**
     * The changed projection of a SECOND compute over the APPLIED document — the only construction in which
     * "source-identical" is beyond doubt, because the stored cell IS this computation's own previous output.
     * A hand-written `05:02:09` cell is not proof of identity: Time's stored carrier is a model-zone instant, so
     * an ingestion difference would surface as a change and read exactly like an always-changed policy.
     */
    private fun changedOnReapply(build: (RuntimeLaws.Doc) -> Unit): List<String> {
        val engine = RuntimeLaws.kernelEngine(json)
        val applied = engine.computeAndApply(engine.instance(RuntimeLaws.doc().also(build)))
        val dynamic = engine.resultChannels(applied).withChanges().sorted()
        val staticApplied = javaEngine.computeAndApply(javaEngine.instance(RuntimeLaws.doc().also(build)))
        val generated = javaEngine.resultChannels(staticApplied).withChanges().sorted()
        assertEquals(dynamic, generated, "groovy-dynamic vs java-static changed projection on re-apply")
        return dynamic
    }

    /** The successful channel of the same second pass — the control that the re-apply actually ran. */
    private fun ranCleanlyOnReapply(build: (RuntimeLaws.Doc) -> Unit): List<String> {
        val engine = RuntimeLaws.kernelEngine(json)
        val applied = engine.computeAndApply(engine.instance(RuntimeLaws.doc().also(build)))
        return engine.resultChannels(applied).withoutErrors().sorted()
    }

    private fun RuntimeLaws.Doc.task(policy: Int, row: Int, slot: String?): RuntimeLaws.Doc {
        group("/Portfolio/Policy", 1, policy)
        group("/Portfolio/Policy/Tasks", 1, policy, row)
        slot?.let { field(SLOT, it, 1, policy, row, 1) }
        return this
    }

    private fun slotAt(policy: Int, row: Int) = "/Portfolio[1]/Policy[$policy]/Tasks[$row]/SlotTime"

    // ── the correction: a SOURCE-IDENTICAL repeatable row is ABSENT from the changed projection ─────────────

    @Test fun aReapplyOverTheConstructedRowsReportsNoChange() {
        assertEquals(listOf(slotAt(1, 1), slotAt(2, 1)), ranCleanlyOnReapply { d ->
            d.task(1, 1, null).task(2, 1, null)
        }, "the control: the second pass RAN both computations and both succeeded, so an empty changed set "
                + "below is equality and not an absent result")
        assertEquals(emptyList<String>(), changedOnReapply { d ->
            d.task(1, 1, null).task(2, 1, null)
        }, "genuinely source-identical rows under two parents report NO change — ordinary row-local equality")
    }

    /**
     * A hand-SEEDED identical clock IS reported as changed, on both placements. Kept as the companion
     * observation rather than deleted: it is the shape a reader will write first, and the pair is what shows
     * that a seeded value cannot establish source identity. The variable separating the two is unmeasured.
     */
    @Test fun aHandSeededIdenticalClockIsReportedAsChanged() =
        assertEquals(listOf(slotAt(1, 1), slotAt(2, 1)), changed { d ->
            d.task(1, 1, CONSTRUCTED).task(2, 1, CONSTRUCTED)
        }, "a seeded cell holding 05:02:09 is still reported as changed")

    @Test fun aStaleSiblingRowIsReportedAtItsOwnAddress() =
        assertEquals(listOf(slotAt(1, 1), slotAt(1, 2)), changed { d ->
            d.task(1, 1, CONSTRUCTED).task(1, 2, "07:15:00")
        }, "each row is addressed independently; the seeded row is reported for the reason above")

    @Test fun anAbsentTargetRowCarriesAChange() =
        assertEquals(listOf(slotAt(1, 1)), changed { d ->
            d.task(1, 1, null)
        }, "an absent target cell is a change")

    @Test fun aPresentEmptyTargetRowCarriesAChange() =
        assertEquals(listOf(slotAt(1, 1)), changed { d ->
            d.task(1, 1, "")
        }, "a present-empty target cell is a change")

    @Test fun noPhysicalTargetRowProducesNoChange() =
        assertEquals(emptyList<String>(), changed { d ->
            d.group("/Portfolio/Policy", 1, 1)
        }, "a policy with no task row produces no changed outcome")

    // ── the generated comparison fires only at the stale FILLED target ──────────────────────────────────────

    private fun fires(build: (RuntimeLaws.Doc) -> Unit): List<String> {
        val doc = RuntimeLaws.doc().also(build)
        val groovy = RuntimeLaws.messages(json, doc).filter { it.code() == "SlotClock" }
            .map { it.field() }.sorted()
        val doc2 = RuntimeLaws.doc().also(build)
        val generated = javaEngine.messagesModern(doc2, null, java.util.Locale.US)
            .filter { it.code() == "SlotClock" }.map { it.field() }.sorted()
        assertEquals(groovy, generated, "groovy-dynamic vs java-static generated-comparison fire set")
        return groovy
    }

    @Test fun theGeneratedComparisonFiresOnlyAtTheStaleFilledTarget() =
        assertEquals(listOf(slotAt(1, 2)), fires { d ->
            d.task(1, 1, CONSTRUCTED).task(1, 2, "07:15:00")
        }, "the row that agrees is silent; the stale filled row fires at its own address")
}
