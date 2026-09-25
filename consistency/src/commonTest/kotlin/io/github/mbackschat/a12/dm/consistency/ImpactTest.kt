package io.github.mbackschat.a12.dm.consistency

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * [impactOf] — which declarations a candidate change reaches, and why (slice J).
 *
 * **What it adds over [checkEdit], which is the reason it exists.** An edit report answers "did my change
 * break anything"; this answers "what do I have to re-read". Those differ exactly where a declaration is
 * REACHED by a change without becoming invalid — the ordinary case for a rename or a kind change, and the
 * one an author cannot enumerate by hand on a model of any size.
 *
 * **The load-bearing property is containment, not the affected list itself**:
 * [everyNewlyInvalidDeclarationIsAlsoReportedAsAffected] holds the dependency analysis to the check's own
 * verdict, so an analysis that MISSES a carrier fails here rather than quietly under-reporting. The check is
 * the oracle; the graph is the claim. Its twin is
 * [aDeclarationTheChangeCannotReachIsNotReported], without which reporting everything would pass.
 *
 * No claim is made about runtime behaviour or intent: a reported declaration is one whose CONSISTENCY may
 * have moved, never one whose computed values have.
 */
class ImpactTest {

    @Test
    fun theBaselineIsClean() {
        // Every row below reads `introduced` and `resolved`, so a baseline carrying findings of its own would
        // make each of them measure noise rather than the change.
        assertEquals(emptyList(), check(ModelSource(base)).findings.map { "${it.code}@${it.at}" })
    }

    @Test
    fun aRenamedFieldNamesTheDeclarationsThatReferenceItAndNoOthers() {
        val report = impactOf(ModelSource(base), ModelSource(changed(""""name":"Code"""", """"name":"Ref"""")))

        assertEquals(listOf("/Order/Code"), report.delta.removed)
        assertEquals(listOf("/Order/Ref"), report.delta.added)
        assertEquals(setOf("/Order/CodeRequired", "/Order/Lines/LineLabel"), report.affected.toSet(),
            "the rule reports on it, and the line label reads it by short name: ${report.impacts}")
        // And the rename genuinely breaks it, which is the half the check establishes.
        assertTrue(report.edit.introduced.isNotEmpty(), "a dangling reference is a finding: ${report.edit}")
    }

    @Test
    fun aRemovedFieldNamesItsReaders() {
        val report = impactOf(ModelSource(base), ModelSource(without(""""name":"Fee"""")))

        assertTrue("/Order/Fee" !in report.delta.removed, "Fee is declared inside Lines")
        assertEquals(listOf("/Order/Lines/Fee"), report.delta.removed)
        assertEquals(setOf("/Order/FeePresent", "/Order/NetTotal", "/Order/GrossTotal"), report.affected.toSet(),
            "both direct readers, and GrossTotal through the field NetTotal computes: ${report.impacts}")
    }

    @Test
    fun aFieldKindChangeReachesTheDeclarationsThatReadIt() {
        val report = impactOf(ModelSource(base),
            ModelSource(changed(""""id":"f-fee","name":"Fee","Field":{"fieldType":{"type":"NumberType"}}""",
                """"id":"f-fee","name":"Fee","Field":{"fieldType":{"type":"StringType"}}""")))

        assertEquals(listOf("/Order/Lines/Fee"), report.delta.modified,
            "the field is neither added nor removed — its declaration changed")
        assertTrue("/Order/NetTotal" in report.affected, "it sums that field: ${report.impacts}")
        // The kind change is what A12 refuses, so the check must see it too — otherwise this row would pass
        // on a model where nothing moved at all.
        assertTrue(report.edit.introduced.isNotEmpty(), "summing text is not a model A12 builds")
    }

    @Test
    fun aGroupScopeChangeReachesTheDeclarationsAddressingThroughIt() {
        val report = impactOf(ModelSource(base), ModelSource(changed(""""repeatability":20,""", "")))

        assertEquals(listOf("/Order/Lines"), report.delta.modified,
            "the group's own declaration changed; its children did not")
        // Nothing textually references `/Order/Lines`: these three address THROUGH it, which is why an
        // element dependency has to reach a referent's ancestors and not only the referent.
        assertEquals(
            setOf("/Order/FeePresent", "/Order/NetTotal", "/Order/GrossTotal", "/Order/Lines/LineLabel"),
            report.affected.toSet(),
        )
    }

    @Test
    fun aTypeDefinitionChangeReachesTheFieldsBoundToIt() {
        val report = impactOf(ModelSource(base), ModelSource(changed(""""id":"Money"""", """"id":"Amount"""")))

        assertEquals(listOf("Money"), report.delta.typeDefinitionsRemoved)
        assertEquals(listOf("Amount"), report.delta.typeDefinitionsAdded)
        assertEquals(listOf("/Order/Tier"), report.affected,
            "the field binds that definition by id — a referent that is not an element at all")
        assertTrue(report.edit.introduced.isNotEmpty(), "and the binding no longer resolves")
    }

    /**
     * The row a path-keyed analysis cannot see, and the purest case for this whole operation: a declaration
     * is reached, nothing it names is touched, and BOTH models are acceptable — so neither the check nor the
     * edit report says anything at all.
     */
    @Test
    fun aBareNameTheChangeCouldREBINDIsReachedWithoutReferencingIt() {
        // `LineLabel` sits in `Lines` and reads the short name `Code`, which the declaring group does not
        // own — so the model-wide fallback is what binds it, to `/Order/Code`. Declaring a `Code` inside
        // `Lines` takes that reference over through the LOCAL tier, touching nothing it currently names.
        val report = impactOf(ModelSource(base), ModelSource(changed(
            """{"type":"Field","id":"f-fee",""",
            """{"type":"Field","id":"f-code2","name":"Code","Field":{"fieldType":{"type":"StringType"}}},
               {"type":"Field","id":"f-fee",""")))

        assertEquals(listOf("/Order/Lines/Code"), report.delta.added)
        assertEquals(emptyList(), report.delta.modified, "nothing the computation resolves to was touched")
        assertEquals(emptyList(), report.edit.introduced, "and nothing broke — both models are acceptable")
        assertEquals(listOf("/Order/Lines/LineLabel"), report.affected,
            "the name space moved under a bare reference: ${report.impacts}")
        assertEquals(ImpactReason.RESOLVES_A_MOVED_SHORT_NAME,
            report.impacts.single { it.at == "/Order/Lines/LineLabel" }.reason)
    }

    @Test
    fun aDeclarationTheChangeCannotReachIsNotReported() {
        // The twin of every row above: `CodeRequired` reads only `Code`, so a change to the line item's fee
        // must not reach it. Without this, an analysis reporting every declaration would pass them all.
        val report = impactOf(ModelSource(base), ModelSource(changed(""""name":"Fee"""", """"name":"LineFee"""")))

        assertTrue("/Order/CodeRequired" !in report.affected, "it has no path to Fee: ${report.impacts}")
        assertTrue("/Order/Tier" !in report.affected, "and a type binding is not a reference to a field")
    }

    /**
     * The containment property: the dependency analysis may over-report, never under-report.
     *
     * Over-reporting costs a reader time; under-reporting means an author was told a declaration was
     * unaffected and it broke. So every declaration the CHECK finds newly invalid must appear in the
     * affected list — measured across every change this file makes rather than on one of them, because a
     * single carrier would leave the other carriers' misses invisible.
     */
    @Test
    fun everyNewlyInvalidDeclarationIsAlsoReportedAsAffected() {
        val changes = mapOf(
            "rename a field" to changed(""""name":"Code"""", """"name":"Ref""""),
            "remove a field" to without(""""name":"Fee""""),
            "change a field's kind" to changed(""""id":"f-fee","name":"Fee","Field":{"fieldType":{"type":"NumberType"}}""",
                """"id":"f-fee","name":"Fee","Field":{"fieldType":{"type":"StringType"}}"""),
            "rescope a group" to changed(""""repeatability":20,""", ""),
            "rename a type definition" to changed(""""id":"Money"""", """"id":"Amount""""),
            "remove a computed field" to without(""""name":"Net""""),
        )
        val uncovered = changes.mapValues { (_, edited) ->
            val report = impactOf(ModelSource(base), ModelSource(edited))
            val invalid = report.edit.introduced.mapNotNull { it.at }.toSet()
            (invalid - report.affected.toSet()).sorted()
        }.filterValues { it.isNotEmpty() }

        assertEquals(emptyMap(), uncovered,
            "a declaration the check found newly invalid was reported as unaffected")
    }

    @Test
    fun aDependencyTheAnalysisCannotFollowIsDisclosedAsIncomplete() {
        // A baseline reference that resolves to nothing is a dependency with no referent, so a change at
        // that position cannot be followed. Saying nothing would present a partial graph as a complete one.
        val report = impactOf(ModelSource(changed("FieldFilled(Code)", "FieldFilled(NoSuchField)")),
            ModelSource(changed("FieldFilled(Code)", "FieldFilled(NoSuchFieldEither)")))

        val declined = report.gaps.filter { it.at == "/Order/CodeRequired" }
        assertEquals(1, declined.size,
            "the unresolved reference is named as a limit of the analysis: ${report.gaps}")
        assertTrue(declined.single().reason.contains("NoSuchField"), declined.single().reason)
        // The control that makes the row about UNRESOLVED dependencies rather than about dependencies: every
        // reference this declaration carries resolves, so nothing about it may be declined.
        assertEquals(emptyList(), report.gaps.filter { it.at == "/Order/NetTotal" },
            "a declaration whose every dependency resolves is not a limit of anything: ${report.gaps}")
    }

    @Test
    fun anIncludeThisSourceDoesNotCarryLeavesTheAnalysisIncomplete() {
        // A12 recognises a mount by a group that declares an alias and NO elements of its own, so the mount
        // is its own group rather than a member added to an existing one.
        val mounting = changed(""""type":"Rule","id":"r-code",""",
            """"type":"Group","id":"g-ship","name":"ShipTo","Group":{"modelAlias":"catalog"}},
               {"type":"Rule","id":"r-code",""")
        val report = impactOf(ModelSource(mounting), ModelSource(mounting))

        assertEquals(emptyList(), report.impacts, "an unexpanded mount is not a fact base to analyse")
        assertTrue(report.gaps.any { it.at == "/Order/ShipTo" },
            "and the reason names the mount: ${report.gaps}")
    }

    @Test
    fun anUnreadableSourceRefusesToAnalyseRatherThanReportNothing() {
        val report = impactOf(ModelSource(base), ModelSource("{\"header\":"))

        assertEquals(emptyList(), report.impacts)
        assertTrue(report.gaps.any { it.at == null }, "the refusal is stated: ${report.gaps}")
        // The edit half still rides along, so the caller keeps what WAS established.
        assertTrue(report.edit.edited.diagnostics.isNotEmpty())
    }

    /** The canonical model, changed one substitution at a time so each row moves exactly one thing. */
    private val base = """
        {"header":{"id":"orders","locales":[{"code":"en_US"}]},
         "content":{"typeDefinitions":[{"id":"Money","name":"Money","fieldType":{"type":"NumberType"}}],
          "modelConfig":{"fieldRefByShortNameAllowed":true},
          "modelRoot":{"rootGroups":[
           {"type":"Group","id":"g0","name":"Order","Group":{"elements":[
             {"type":"Field","id":"f-code","name":"Code","Field":{"fieldType":{"type":"StringType"}}},
             {"type":"Field","id":"f-net","name":"Net","Field":{"fieldType":{"type":"NumberType"}}},
             {"type":"Field","id":"f-total","name":"Total","Field":{"fieldType":{"type":"NumberType"}}},
             {"type":"Field","id":"f-tier","name":"Tier","Field":{"fieldType":{
               "type":"TypeDefType","TypeDefType":{"typeDefinitionId":"Money"}}}},
             {"type":"Group","id":"g-lines","name":"Lines","Group":{"repeatability":20,"elements":[
               {"type":"Field","id":"f-fee","name":"Fee","Field":{"fieldType":{"type":"NumberType"}}},
               {"type":"Field","id":"f-label","name":"Label","Field":{"fieldType":{"type":"StringType"}}},
               {"type":"Computation","id":"c-label","name":"LineLabel","Computation":{
                 "computedFieldRelPath":"../Label","computationAlternatives":[{"operation":"[Code]"}],
                 "errorMessage":[{"locale":"en_US","text":"the line label"}]}}
             ]}},
             {"type":"Rule","id":"r-code","name":"CodeRequired","Rule":{
               "errorCondition":"FieldFilled(Code)","errorEntityRelPath":"../Code",
               "errorCode":"C1","errorMessage":[{"locale":"en_US","text":"the order code"}]}},
             {"type":"Rule","id":"r-fee","name":"FeePresent","Rule":{
               "errorCondition":"AtLeastOneFieldFilled(Lines*/Fee)","errorEntityRelPath":"../Lines/Fee",
               "errorCode":"C2","errorMessage":[{"locale":"en_US","text":"a line fee"}]}},
             {"type":"Computation","id":"c-net","name":"NetTotal","Computation":{
               "computedFieldRelPath":"../Net","computationAlternatives":[{"operation":"Sum(Lines*/Fee)"}],
               "errorMessage":[{"locale":"en_US","text":"the net total"}]}},
             {"type":"Computation","id":"c-total","name":"GrossTotal","Computation":{
               "computedFieldRelPath":"../Total","computationAlternatives":[{"operation":"[Net]"}],
               "errorMessage":[{"locale":"en_US","text":"the gross total"}]}}
           ]}}
          ]}}}
    """.trimIndent()

    /** [base] with [from] replaced by [to], refusing a substitution that would silently match nothing. */
    private fun changed(from: String, to: String): String {
        assertEquals(1, base.occurrencesOf(from), "the fixture must carry `$from` exactly once")
        return base.replace(from, to)
    }

    /** [base] with the declaration carrying [marker] deleted, brackets balanced. */
    private fun without(marker: String): String {
        assertEquals(1, base.occurrencesOf(marker), "the fixture must carry `$marker` exactly once")
        val start = base.lastIndexOf('{', base.indexOf(marker))
        var depth = 0
        var end = start
        while (end < base.length) {
            if (base[end] == '{') depth++
            if (base[end] == '}') {
                depth--
                if (depth == 0) break
            }
            end++
        }
        val after = base.substring(end + 1).trimStart()
        // The declaration's own separator goes with it, whichever side carries one.
        return if (after.startsWith(",")) {
            base.substring(0, start) + base.substring(end + 1).trimStart().drop(1)
        } else {
            base.substring(0, start).trimEnd().removeSuffix(",") + base.substring(end + 1)
        }
    }

    private fun String.occurrencesOf(needle: String) = (length - replace(needle, "").length) / needle.length
}
