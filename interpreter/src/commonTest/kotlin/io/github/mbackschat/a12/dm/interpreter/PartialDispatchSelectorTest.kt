package io.github.mbackschat.a12.dm.interpreter

import io.github.mbackschat.a12.dm.interpreter.eval.readDocumentJson
import io.github.mbackschat.a12.dm.interpreter.model.FieldDef
import io.github.mbackschat.a12.dm.interpreter.model.Kind
import io.github.mbackschat.a12.dm.interpreter.model.ModelDef
import io.github.mbackschat.a12.dm.interpreter.model.RelevantEntity
import io.github.mbackschat.a12.dm.interpreter.model.RuleDef
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Multiplatform (JVM + JS) lock for IF439: a partial call runs each rule through its error field's merged selector,
 * a coordinate the relevant pointers disagree on becoming a wildcard, and a joined rule iterating a level below
 * its join runs only when the joined coordinate is a wildcard. The Kernel answers are
 * `adapter.laws.PartialDispatchSelectorDiffTest`'s.
 */
class PartialDispatchSelectorTest {

    private val model = ModelDef(
        fields = listOf(
            FieldDef("/Order/Email", Kind.STRING),
            FieldDef("/Order/Lines/LineNo", Kind.STRING),
            FieldDef("/Order/Lines/Parts/PartNo", Kind.STRING),
            FieldDef("/Order/Lines/Parts/Count", Kind.NUMBER, scale = 0),
            FieldDef("/Order/Lines/Parts/Tag", Kind.STRING),
            FieldDef("/Order/Picks/LineNo", Kind.STRING),
            FieldDef("/Order/Picks/Ref", Kind.STRING),
        ),
        rules = listOf(
            RuleDef("/Order/WideRule", "/Order",
                "FieldFilled(Lines/Parts/Tag) And (FieldFilled(Email) Or FieldFilled(Lines/Parts/Count))", "WIDE", "../Lines/Parts/Count"),
            RuleDef("/Order/NestedRule", "/Order",
                "FieldFilled(Lines/Parts/Count) And NoFieldFilled(Lines/Parts/PartNo, Picks/Ref)", "NESTED", "../Lines/Parts/Count"),
        ),
        groupPaths = setOf("/Order", "/Order/Lines", "/Order/Lines/Parts", "/Order/Picks"),
        declaredReps = mapOf("/Order/Lines" to 4, "/Order/Lines/Parts" to 3, "/Order/Picks" to 4),
        repeatableGroups = setOf("/Order/Lines", "/Order/Lines/Parts", "/Order/Picks"),
        indexFields = setOf("/Order/Lines/LineNo", "/Order/Picks/LineNo"),
    )

    private val doc = model.readDocumentJson("""{"Order":{"Email":"e","Lines":[
        {"LineNo":"A","Parts":[{"Count":2,"Tag":"t"},{"Count":3,"Tag":"t"},{"Tag":"t"}]},
        {"LineNo":"B","Parts":[{"Count":4,"Tag":"t"},{"Count":7,"Tag":"t"}]},
        {"LineNo":"C","Parts":[{"Count":5,"Tag":"t"},{"Count":6,"Tag":"t"}]}],
        "Picks":[{"LineNo":"A"},{"LineNo":"B","Ref":"r"},{"LineNo":"D","Ref":"r"}]}}""")

    private val base = listOf("/Order/Email", "/Order/Lines/Parts/Tag", "/Order/Lines/Parts/PartNo", "/Order/Picks/Ref",
        "/Order/Lines/LineNo", "/Order/Picks/LineNo").map(RelevantEntity::anyRep).toSet()

    /** `Line.Part code` of each message, given the relevant Count cells as (line, part). */
    private fun fired(vararg counts: Pair<Int, Int>): List<String> {
        val relevant = base + counts.map { (line, part) -> RelevantEntity("/Order/Lines/Parts/Count", listOf(1, line, part, 1)) }
        val report = DmInterpreter(model).validatePart(doc, relevant)
        assertEquals(emptyList(), report.unsupported)
        return report.messages.map { m -> m.errorPointer.repetitionIndexes.drop(1).take(2).joinToString(".") + " " + m.code }.sorted()
    }

    @Test fun aSingleCellDispatchesItsOwnRowAndSilencesTheRuleBelowTheJoin() =
        assertEquals(listOf("1.1 WIDE"), fired(1 to 1))

    @Test fun twoCellsSharingAPartWidenOnlyTheLineCoordinate() =
        assertEquals(listOf("1.2 NESTED", "1.2 WIDE", "2.2 WIDE", "3.2 NESTED", "3.2 WIDE"), fired(1 to 2, 3 to 2))

    @Test fun twoCellsInOneLineWidenThatLinesPartsAndKeepTheJoinedRuleSilent() =
        assertEquals(listOf("1.1 WIDE", "1.2 WIDE", "1.3 WIDE"), fired(1 to 1, 1 to 2))
}
