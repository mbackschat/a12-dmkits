package io.github.mbackschat.a12.dm.interpreter

import io.github.mbackschat.a12.dm.interpreter.model.FieldDef
import io.github.mbackschat.a12.dm.interpreter.model.Kind
import io.github.mbackschat.a12.dm.interpreter.model.ModelDef
import io.github.mbackschat.a12.dm.interpreter.model.RuleDef
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The value-independent support report is the same preparation boundary execution consumes. These cases are
 * deliberately structural: no document value can make the declined shape safe, so sampling a convenient row is
 * not an eligibility proof.
 */
class PreparedPlanSupportTest {

    @Test
    fun reportsEveryRepetitionNotUniqueStructuralDeclineBeforeEvaluation() {
        val model = ModelDef(
            fields = listOf(
                FieldDef("/Order/Items/Key", Kind.STRING),
                FieldDef("/Order/Items/Other", Kind.STRING),
                FieldDef("/Order/Items/Details/Value", Kind.NUMBER),
            ),
            rules = listOf(
                rule(
                    "RepetitionNotUnique(Items/Key) And RepetitionNotUnique(Items/Other)",
                    "../Items/Key",
                ),
                rule(
                    "RepetitionNotUnique(Items*)",
                    "../Items/Key",
                    code = "GROUP_KEY",
                ),
                rule(
                    "RepetitionNotUnique(Items/Key) And [Items/Details/Value] > 0",
                    "../Items/Key",
                    code = "DEEP_SCOPE",
                ),
            ),
            repeatableGroups = setOf("/Order/Items", "/Order/Items/Details"),
            declaredReps = mapOf("/Order/Items" to 10, "/Order/Items/Details" to 10),
        )

        val reasons = DmInterpreter(model).supportAudit().map { it.reason }

        assertTrue(reasons.any { "more than one RepetitionNotUnique" in it }, "$reasons")
        assertTrue(reasons.any { "plain field" in it }, "$reasons")
        assertTrue(reasons.any { "deeper than its key group" in it }, "$reasons")
    }

    @Test
    fun reportsParallelJoinStructureBeforeEvaluation() {
        val noIndex = parallelModel(
            condition = "[A/Value] > [B/Value]",
            indexFields = emptySet(),
        )
        val mismatchedIndex = parallelModel(
            condition = "[A/Value] > [B/Value]",
            indexFields = setOf("/Plan/A/AKey", "/Plan/B/BKey"),
            extraFields = listOf(
                FieldDef("/Plan/A/AKey", Kind.STRING),
                FieldDef("/Plan/B/BKey", Kind.STRING),
            ),
        )

        val noIndexReasons = DmInterpreter(noIndex).supportAudit().map { it.reason }
        val mismatchedReasons = DmInterpreter(mismatchedIndex).supportAudit().map { it.reason }

        assertTrue(noIndexReasons.any { "has no index field" in it }, "$noIndexReasons")
        assertTrue(mismatchedReasons.any { "share one index field name" in it }, "$mismatchedReasons")
    }

    @Test
    fun reportsParallelDocumentViewsThatExecutionCannotProvide() {
        val indexed = setOf("/Plan/A/Key", "/Plan/B/Key")
        val aggregate = parallelModel(
            condition = "[A/Value] > [B/Value] And AtLeastOneFieldFilled(A*/Value)",
            indexFields = indexed,
        )
        val nonJoinedGroupFill = parallelModel(
            condition = "[A/Value] > [B/Value] And GroupFilled(Other)",
            indexFields = indexed,
            extraFields = listOf(FieldDef("/Plan/Other/Flag", Kind.STRING)),
        )

        val aggregateReasons = DmInterpreter(aggregate).supportAudit().map { it.reason }
        val groupFillReasons = DmInterpreter(nonJoinedGroupFill).supportAudit().map { it.reason }

        assertTrue(aggregateReasons.any { "aggregate/filter" in it && "parallel iteration" in it }, "$aggregateReasons")
        assertTrue(groupFillReasons.any { "non-parallel group" in it }, "$groupFillReasons")
    }

    private fun parallelModel(
        condition: String,
        indexFields: Set<String>,
        extraFields: List<FieldDef> = emptyList(),
    ) = ModelDef(
        fields = listOf(
            FieldDef("/Plan/A/Key", Kind.STRING),
            FieldDef("/Plan/A/Value", Kind.NUMBER),
            FieldDef("/Plan/B/Key", Kind.STRING),
            FieldDef("/Plan/B/Value", Kind.NUMBER),
        ) + extraFields,
        rules = listOf(RuleDef("/Plan/PAR", "/Plan", condition, "PAR", "../A/Value")),
        repeatableGroups = setOf("/Plan/A", "/Plan/B"),
        declaredReps = mapOf("/Plan/A" to 10, "/Plan/B" to 10),
        indexFields = indexFields,
    )

    private fun rule(condition: String, errorPath: String, code: String = "RNU") =
        RuleDef("/Order/$code", "/Order", condition, code, errorPath)
}
