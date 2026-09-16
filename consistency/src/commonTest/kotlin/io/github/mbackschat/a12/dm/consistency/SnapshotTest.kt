package io.github.mbackschat.a12.dm.consistency

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * [ConsistencySnapshot] — an analysis a caller updates explicitly instead of redoing (slice L-a).
 *
 * **Two properties, and they pull against each other on purpose.** Every row in
 * [anUpdatedSnapshotAgreesWithAFreshCheckOnEveryChangeKind] holds the snapshot to a FRESH full check, so
 * reuse can never change an answer; the counter rows hold it to actually reusing something, so an
 * implementation that quietly rechecks everything fails. Either property alone is trivially satisfiable —
 * a full recheck passes the first, and a stale cache passes the second.
 *
 * **The counters are measurements, not literals.** [Reuse.sourcesReused] compares each admitted parse against
 * the previous snapshot's by OBJECT IDENTITY, so a re-admission that produces an equal-but-new parse counts
 * as no reuse; the two draft flags compare the fact base and baseline an answer was computed from against the
 * snapshot's own. A counter assembled from a formula would agree with itself however much work was redone.
 */
class SnapshotTest {

    @Test
    fun aFreshSnapshotReportsExactlyWhatCheckingTheSameSourcesReports() {
        val snapshot = snapshotOf(listOf(ModelSource(order()), ModelSource(catalog())), "orders")
        val fresh = ConsistencyWorkspace
            .fromSources(listOf(ModelSource(order()), ModelSource(catalog()))).check("orders")

        assertEquals(fresh, snapshot.report, "a snapshot is not a second checker")
        assertEquals(0, snapshot.reuse.sourcesReused, "nothing preceded it, so nothing was reused")
        assertEquals(2, snapshot.reuse.sourcesAdmitted)
    }

    /**
     * The equivalence table: every change kind the slice names, incremental against fresh. A reuse rule that
     * is wrong for one kind of change fails here rather than in a consumer.
     */
    @Test
    fun anUpdatedSnapshotAgreesWithAFreshCheckOnEveryChangeKind() {
        val updates = mapOf(
            "a rule's condition" to order(condition = "FieldFilled(NoSuchField)"),
            "a field's kind" to order(quantityType = "StringType"),
            "the model config" to order(config = """"modelConfig":{"fieldRefByShortNameAllowed":true},"""),
            "a type definition" to order(typeDefinitions = """[{"id":"Other","name":"Other","fieldType":{"type":"NumberType"}}]"""),
            "the include's own mount" to order(mountAlias = "nosuchmodel"),
        )
        val actual = updates.mapValues { (_, updated) ->
            snapshot().withSource(ModelSource(updated)).report
        }
        val expected = updates.mapValues { (_, updated) ->
            ConsistencyWorkspace.fromSources(listOf(ModelSource(updated), ModelSource(catalog())))
                .check("orders")
        }

        assertEquals(expected, actual, "an updated snapshot must be indistinguishable from a fresh check")
    }

    @Test
    fun aRemovedSourceAgreesWithAFreshCheckWithoutIt() {
        val incremental = snapshot().withoutSource("catalog")
        val fresh = ConsistencyWorkspace.fromSources(listOf(ModelSource(order()))).check("orders")

        assertEquals(fresh, incremental.report, "removing the provider leaves the mount unexpanded, both ways")
        assertTrue(incremental.report.gaps.any { it.at == "/Order/ShipTo" },
            "and that is visible rather than silent: ${incremental.report.gaps}")
        assertEquals(1, incremental.reuse.sourcesReused, "the entry model's own parse was not redone")
        assertEquals(0, incremental.reuse.sourcesAdmitted)
    }

    @Test
    fun updatingOneSourceReadmitsThatSourceAndReusesTheOthers() {
        val updated = snapshot().withSource(ModelSource(order(condition = "FieldFilled(NoSuchField)")))

        assertEquals(1, updated.reuse.sourcesAdmitted, "exactly the source that changed")
        assertEquals(1, updated.reuse.sourcesReused, "and the one that did not")
        // The entry model changed, so neither of these could be reused — the row exists so the two counters
        // above cannot be read as "everything is always reused".
        assertFalse(updated.reuse.preparationReused)
        assertFalse(updated.reuse.baselineReportReused)
    }

    /**
     * The reuse that makes the slice worth landing: an editor asks per keystroke, and the baseline side
     * cannot have changed. `checkDraft` pays one admission, one preparation and TWO checks each time; a
     * snapshot pays one check and nothing else.
     */
    @Test
    fun aDraftReusesTheFactBaseAndTheBaselineReportEntirely() {
        val result = snapshot().draft("/Order/CodeRequired", "errorCondition", "FieldFilled(NoSuchField)")

        assertEquals(0, result.reuse.sourcesAdmitted, "nothing is re-read to answer a keystroke")
        assertEquals(2, result.reuse.sourcesReused)
        assertTrue(result.reuse.preparationReused, "the fact base is the snapshot's own")
        assertTrue(result.reuse.baselineReportReused, "and so is the side the draft is attributed against")
    }

    /**
     * The fact base a draft is typed into is the MOUNTED model, not the entry source alone. Nothing else
     * measures that: the equivalence row below deliberately drops the mount to compare like with like, and
     * the reuse rows only count. A snapshot prepared without its providers passes every other row here.
     */
    @Test
    fun aDraftResolvesAgainstTheMountedModelRatherThanTheEntrySourceAlone() {
        // `FieldFilled(Code)` stays in both texts: the rule reports on `/Order/Code`, and a condition that
        // stopped naming it would draw `MVK_ERROR_FIELD_NOT_REFERENCED` — a real finding from another family,
        // which would mask the one this row is about.
        val resolving = snapshot()
            .draft("/Order/CodeRequired", "errorCondition", "FieldFilled(ShipTo/Zip) And FieldFilled(Code)")
        val missing = snapshot()
            .draft("/Order/CodeRequired", "errorCondition", "FieldFilled(ShipTo/Nope) And FieldFilled(Code)")

        assertEquals(emptyList(), resolving.edit.introduced,
            "`Zip` is declared only in the mounted provider: ${resolving.edit.introduced}")
        assertEquals(listOf("MVK_INVALID_ENTITY"), missing.edit.introduced.map { it.code },
            "and the positive control rejects, so the row above does not pass by silence")
    }

    @Test
    fun aDraftFromASnapshotSaysExactlyWhatTheStatelessDraftSays() {
        // No mount on either side. With one, the stateless call cannot expand it and its reference families
        // are SUSPENDED — the fact base being incomplete — so the two would disagree for a reason that has
        // nothing to do with snapshots. That difference is the unexpanded-mount contract, not a divergence.
        val unmounted = order(mount = "")
        val stateless = checkDraft(ModelSource(unmounted), "/Order/CodeRequired", "errorCondition",
            "FieldFilled(NoSuchField)")
        val fromSnapshot = snapshotOf(listOf(ModelSource(unmounted)), "orders")
            .draft("/Order/CodeRequired", "errorCondition", "FieldFilled(NoSuchField)").edit

        assertEquals(stateless, fromSnapshot, "a snapshot's draft is the stateless draft, exactly")
    }

    @Test
    fun theOldSnapshotIsUnchangedByAnUpdateAndByADraft() {
        val original = snapshot()
        val before = original.report

        original.withSource(ModelSource(order(condition = "FieldFilled(NoSuchField)")))
        original.draft("/Order/CodeRequired", "errorCondition", "FieldFilled(AlsoMissing)")

        assertEquals(before, original.report, "a snapshot is a value, so nothing may write into it")
        assertEquals(emptyList(), original.report.findings, "and the model it describes is still sound")
    }

    @Test
    fun aSourceThatCannotBeAdmittedIsDisclosedRatherThanSilentlyDropped() {
        val updated = snapshot().withSource(ModelSource("{\"header\":", "typo.dm.json"))

        assertEquals(0, updated.reuse.sourcesAdmitted, "nothing was admitted, because nothing could be read")
        assertEquals(2, updated.reuse.sourcesReused, "so both previous parses still stand")
        assertTrue(updated.report.diagnostics.any { it.diagnosticLabel == "typo.dm.json" },
            "the unreadable source is named: ${updated.report.diagnostics}")
    }

    @Test
    fun anUnknownEntryIsAnInconclusiveReportRatherThanAFailure() {
        val snapshot = snapshotOf(listOf(ModelSource(order())), "nosuchmodel")

        assertEquals(ConsistencyStatus.Inconclusive, snapshot.report.status)
        assertTrue(snapshot.report.diagnostics.any { it.code == DiagnosticCode.SOURCE_NOT_IN_WORKSPACE })
        // A draft needs a fact base, and there is none — refused, not raised.
        val result = snapshot.draft("/Order/CodeRequired", "errorCondition", "FieldFilled(Code)")
        assertEquals(emptyList(), result.edit.introduced)
        assertTrue(result.edit.gaps.isNotEmpty(), "the refusal is stated: ${result.edit.gaps}")
    }

    private fun snapshot() = snapshotOf(listOf(ModelSource(order()), ModelSource(catalog())), "orders")

    private fun order(
        condition: String = "FieldFilled(Code)",
        quantityType: String = "NumberType",
        config: String = "",
        typeDefinitions: String = """[{"id":"Money","name":"Money","fieldType":{"type":"NumberType"}}]""",
        mountAlias: String = "catalog",
        mount: String = """{"type":"Group","id":"g-ship","name":"ShipTo","Group":{"modelAlias":"catalog"}},""",
    ) = """
        {"header":{"id":"orders","locales":[{"code":"en_US"}],
          "modelReferences":[{"alias":"catalog","reference":"$mountAlias"}]},
         "content":{"typeDefinitions":$typeDefinitions,$config
          "modelRoot":{"rootGroups":[
           {"type":"Group","id":"g0","name":"Order","Group":{"elements":[
             {"type":"Field","id":"f-code","name":"Code","Field":{"fieldType":{"type":"StringType"}}},
             {"type":"Field","id":"f-qty","name":"Quantity","Field":{"fieldType":{"type":"$quantityType"}}},
             {"type":"Field","id":"f-tier","name":"Tier","Field":{"fieldType":{
               "type":"TypeDefType","TypeDefType":{"typeDefinitionId":"Money"}}}},
             $mount
             {"type":"Rule","id":"r-code","name":"CodeRequired","Rule":{
               "errorCondition":"$condition","errorEntityRelPath":"../Code",
               "errorCode":"C1","errorMessage":[{"locale":"en_US","text":"the order code"}]}}
           ]}}
          ]}}}
    """.trimIndent()

    private fun catalog() = """
        {"header":{"id":"catalog","locales":[{"code":"en_US"}]},
         "content":{"modelRoot":{"rootGroups":[
          {"type":"Group","id":"cg0","name":"Address","Group":{"elements":[
            {"type":"Field","id":"cf0","name":"Zip","Field":{"fieldType":{"type":"StringType"}}}
          ]}}
         ]}}}
    """.trimIndent()
}
