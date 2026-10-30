package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.edit.EditSession
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Which index fields a joined message references when it reads a joined group STARRED (KERNEL-FINDINGS KF539,
 * a12-kernel-lean SPEC-2026-10-30-07): a starred read adds its own fields at coordinate `0` and no index field,
 * filtered or not, while a row-bound read beside it still brings its group's index field.
 *
 * `Needs` and `Cover` are both keyed by `Team`, so a rule or computation in `Needs` that reads `Cover` row-bound runs
 * once per team. The validation rule stars `Needs` itself; the three computations' generated rules star `Needs`, star
 * it under a filter, and star `Cover`. One document carries matched teams, a team only `Needs` has and one only
 * `Cover` has, every target seeded wrong so each generated rule fires. Both strategies and the interpreter are
 * compared on every message's references; the reference lists are pinned from dynamic Groovy. Own domain (a claims
 * department's staffing roster).
 */
class JoinedStarredReferenceDiffTest {

    private val model: String = run {
        val session = EditSession.read(DocumentModelDraft.named("claims-roster").locale("en_US")
            .rootGroup("Roster") { r ->
                r.stringField("RosterRef") { }
                    .group("Needs") { n ->
                        n.repeatable(10).indexField("Team").stringField("Team") { }.stringField("Label") { }
                        listOf("Base", "Headcount", "Planned", "Spare", "Offered").forEach { n.numberField(it) { f -> f.fractionDigits(0) } }
                    }
                    .group("Cover") { c ->
                        c.repeatable(10).indexField("Team").stringField("Team") { }.numberField("Headcount") { it.fractionDigits(0) }
                    }
            }.build().toJson())
        val roster = session.group("/Roster")
        val needs = session.group("/Roster/Needs")
        val covered = session.group("/Roster/Cover").num("Headcount")
        needs.addRule {
            it.name("DuplicateLabel").code("DUPLICATE_LABEL").errorField(needs.num("Headcount"))
                .`when`(Dsl.fieldValuesNotUnique(roster.flatten("Needs*/Label")).and(covered.lt(needs.num("Headcount"))))
                .message(Dsl.en("Two needs share a label.")).build()
        }
        fun compute(target: String, calc: io.github.mbackschat.a12.dm.rulekit.dsl.NumberExpr) =
            needs.addComputation(needs.num(target)) { it.name("${target}Comp").calc(calc).message(Dsl.en("Check $target.")).build() }
        compute("Planned", Dsl.sum(roster.flatten("Needs*/Base")).plus(covered))
        compute("Spare", Dsl.sum(roster.flatten("Needs*/Base").having(needs.num("Headcount").gt(1))).plus(covered))
        compute("Offered", Dsl.sum(roster.flatten("Cover*/Headcount")).plus(covered))
        session.toJson()
    }

    private val document = """{"Roster":{"RosterRef":"R-1","Needs":[""" +
        need("A", 2, 3, "night") + "," + need("B", 1, 1, "night") + "," + need("C", 4, 2, "day") +
        """],"Cover":[{"Team":"B","Headcount":1},{"Team":"A","Headcount":2},{"Team":"D","Headcount":5}]}}"""

    private fun need(team: String, base: Int, headcount: Int, label: String) =
        """{"Team":"$team","Base":$base,"Headcount":$headcount,"Label":"$label","Planned":0,"Spare":0,"Offered":0}"""

    @Test
    fun a12AndThePortableCheckerAdmitTheModel() {
        assertEquals(emptyList<String>(), MessageParameterProbes.kernel(model))
        assertEquals(emptyList<String>(), rejectingCodes(model))
    }

    @Test
    fun allThreeEnginesAgreeOnEveryReference() = DocumentDifferential.assertAgree(model, mapOf("roster" to document))

    @Test
    fun aStarredReadOfAJoinedGroupBringsNoIndexField() {
        val starred = messages().flatMap { m -> m.referenced.filter { "[0]" in it } }.toSortedSet()
        assertEquals(sortedSetOf("/Roster[1]/Cover[0]/Headcount", "/Roster[1]/Needs[0]/Base",
            "/Roster[1]/Needs[0]/Headcount", "/Roster[1]/Needs[0]/Label"), starred)
    }

    /**
     * Each message's references at the matched teams A (`Needs[1]`, `Cover[2]`) and B (`Needs[2]`, `Cover[1]`) and the
     * team C only `Needs` has, whose `Cover` reads carry the `-5` sentinel: every row-bound read comes with its group's
     * `Team`, and every starred read is at `0` without one. The validation rule stays silent for B, whose cover equals
     * its headcount.
     */
    @Test
    fun theRowBoundReadBesideItStillBringsItsIndexField() {
        assertEquals(PINNED, messages().associate { "${it.code} ${it.pointer.removePrefix("/Roster[1]/")}" to
            it.referenced.map { ref -> ref.removePrefix("/Roster[1]/") } })
    }

    private fun messages() = DocumentDifferential.kernel(model, document, dynamic = true).messages

    private companion object {
        val PINNED = mapOf(
            "DUPLICATE_LABEL Needs[1]/Headcount" to listOf("Cover[2]/Headcount", "Cover[2]/Team", "Needs[0]/Label", "Needs[1]/Headcount", "Needs[1]/Team"),
            "DUPLICATE_LABEL Needs[3]/Headcount" to listOf("Cover[-5]/Headcount", "Cover[-5]/Team", "Needs[0]/Label", "Needs[3]/Headcount", "Needs[3]/Team"),
            "OfferedComp Needs[1]/Offered" to listOf("Cover[0]/Headcount", "Cover[2]/Headcount", "Cover[2]/Team", "Needs[1]/Offered", "Needs[1]/Team"),
            "OfferedComp Needs[2]/Offered" to listOf("Cover[0]/Headcount", "Cover[1]/Headcount", "Cover[1]/Team", "Needs[2]/Offered", "Needs[2]/Team"),
            "OfferedComp Needs[3]/Offered" to listOf("Cover[-5]/Headcount", "Cover[-5]/Team", "Cover[0]/Headcount", "Needs[3]/Offered", "Needs[3]/Team"),
            "PlannedComp Needs[1]/Planned" to listOf("Cover[2]/Headcount", "Cover[2]/Team", "Needs[0]/Base", "Needs[1]/Planned", "Needs[1]/Team"),
            "PlannedComp Needs[2]/Planned" to listOf("Cover[1]/Headcount", "Cover[1]/Team", "Needs[0]/Base", "Needs[2]/Planned", "Needs[2]/Team"),
            "PlannedComp Needs[3]/Planned" to listOf("Cover[-5]/Headcount", "Cover[-5]/Team", "Needs[0]/Base", "Needs[3]/Planned", "Needs[3]/Team"),
            "SpareComp Needs[1]/Spare" to listOf("Cover[2]/Headcount", "Cover[2]/Team", "Needs[0]/Base", "Needs[0]/Headcount", "Needs[1]/Spare", "Needs[1]/Team"),
            "SpareComp Needs[2]/Spare" to listOf("Cover[1]/Headcount", "Cover[1]/Team", "Needs[0]/Base", "Needs[0]/Headcount", "Needs[2]/Spare", "Needs[2]/Team"),
            "SpareComp Needs[3]/Spare" to listOf("Cover[-5]/Headcount", "Cover[-5]/Team", "Needs[0]/Base", "Needs[0]/Headcount", "Needs[3]/Spare", "Needs[3]/Team"),
        )
    }
}
