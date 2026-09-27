package io.github.mbackschat.a12.dm.consistency

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/** R5-C retires an assertion only when its projection or relation survives the harvest. */
class ReportHarvestContractTest {
    @Test
    fun originalReportAssertionsKeepTheirClaimKinds() {
        val rows = SyntaxFixtureData.reportChecks.map { Json.parseToJsonElement(it).jsonObject }
        assertEquals(316, rows.size)
        assertEquals(SOURCES.flatMap { (source, count) -> (1..count).map { "$source#$it" } }.toSet(),
            rows.map { it.getValue("id").jsonPrimitive.content }.toSet())
        val reports = rows.associate { row -> row.getValue("id").jsonPrimitive.content to
            checkRead(ModelSource(row.getValue("model").jsonPrimitive.content)).findings }
        for (row in rows) {
            val id = row.getValue("id").jsonPrimitive.content
            fun selected(identity: String): List<Finding> = reports.getValue(identity).filter { finding ->
                (!row.containsKey("at") || finding.at == row.getValue("at").jsonPrimitive.content) &&
                    (row["a12Only"]?.jsonPrimitive?.boolean != true || finding.category == FindingCategory.A12_CONSTRAINT) &&
                    (row["rejectingOnly"]?.jsonPrimitive?.boolean != true || finding.justifiesRejection) &&
                    (!row.containsKey("onlyCodes") || finding.code in row.strings("onlyCodes")) &&
                    finding.code !in row.strings("excludeCodes")
            }
            val findings = selected(id)
            val codes = findings.map { it.code }
            when (Assertion.valueOf(row.getValue("assertion").jsonPrimitive.content)) {
                Assertion.ORDERED_CODES -> assertEquals(row.strings("expected"), codes, id)
                Assertion.SORTED_CODES -> assertEquals(row.strings("expected"), codes.sorted(), id)
                Assertion.CODE_SET -> assertEquals(row.strings("expected"), codes.distinct().sorted(), id)
                Assertion.ORDERED_SITES -> assertEquals(row.getValue("expected"), buildJsonArray {
                    for (finding in findings) add(buildJsonObject {
                        put("code", finding.code)
                        put("at", finding.at?.let(::JsonPrimitive) ?: JsonNull)
                    })
                }, id)
                Assertion.MEMBERSHIP -> {
                    assertTrue(codes.containsAll(row.strings("contains")), "$id: required finding absent: $codes")
                    assertTrue(row.strings("absent").none { it in codes }, "$id: withheld finding present: $codes")
                }
                Assertion.MESSAGE_WITHHOLDING -> {
                    assertEquals(row.getValue("rejected").jsonPrimitive.boolean,
                        findings.any { it.justifiesRejection && it.code !in MESSAGE_CODES }, id)
                    assertEquals(row.strings("messages"), codes.filter { it in MESSAGE_CODES }.sorted(), id)
                }
                Assertion.NONEMPTY -> assertTrue(codes.isNotEmpty(), "$id: condition-alone control is vacuous")
                Assertion.MESSAGE_RELATION -> {
                    val base = selected(row.getValue("base").jsonPrimitive.content).map { it.code }
                    assertTrue(base.isNotEmpty(), "$id: condition-alone control is vacuous")
                    assertEquals((base + row.strings("addCodes")).distinct().sorted(), codes.distinct().sorted(), id)
                }
            }
        }
    }

    private fun JsonObject.strings(key: String): List<String> =
        this[key]?.jsonArray?.map { it.jsonPrimitive.content }.orEmpty()

    private enum class Assertion {
        ORDERED_CODES, SORTED_CODES, CODE_SET, ORDERED_SITES, MEMBERSHIP,
        MESSAGE_WITHHOLDING, NONEMPTY, MESSAGE_RELATION,
    }

    private companion object {
        val MESSAGE_CODES = setOf("INVALID_CHARACTERS", "INVALID_FIELD")
        val SOURCES = mapOf(
            "ConditionMultipleDefectTest.aSyntaxClassIsWithheldBehindAnUnresolvableName" to 7,
            "ConditionMultipleDefectTest.everyConditionTextClassIsWithheldBehindAnUnresolvableName" to 16,
            "ConditionMultipleDefectTest.anAppConditionIsWithheldBehindAnyOtherDefectInItsText" to 3,
            "ConditionMultipleDefectTest.anUnresolvableNameInOneMemberWithholdsTheSyntaxClassOfTheOther" to 2,
            "ConditionMultipleDefectTest.aClassCarryingItsSpanIsPlacedAgainstTheBadName" to 19,
            "ConditionMultipleDefectTest.twoRulesAtOnePathAreNeverPlacedAgainstEachOther" to 2,
            "ConditionMultipleDefectTest.aRepeatOfOneCodeIsKeptForEachPlaceToFix" to 1,
            "ConditionMultipleDefectTest.aFilteredOperandsDefectsAnswerInTheOrderA12ReadsThem" to 13,
            "RuleMemberWithholdingTest.aBrokenErrorTargetWithholdsEveryOtherVerdictOnTheRule" to 7,
            "RuleMemberWithholdingTest.anAboveRootErrorTargetStillReportsItsOwnFinding" to 1,
            "RuleMemberWithholdingTest.aFieldSharingARulesPathKeepsItsOwnFindings" to 1,
            "SuppressionPreambleWithholdingTest.aSuppressionsClassIsRaisedAheadOfEveryClassTheTextsCarry" to 15,
            "CalculationModuleWithholdingTest.aParseTimeClassWithholdsTheCalculationModulesClasses" to 7,
            "ComputationJoinedConditionTest.aComputationIsJudgedAsTheComparisonItIsLoweredTo" to 9,
            "ComputationJoinedConditionTest.aComputationsMembersAnswerInTheOrderA12JoinsThem" to 2,
            "UnsoundDslDrawsARejectionTest.anUnsoundConditionIsRefusedAndItsMessageWithheld" to 42,
            "UnsoundDslDrawsARejectionTest.anUnsoundOperationIsRefusedAndItsMessageWithheld" to 9,
            "MessageBehindARefusedDslTest.aRefusedConditionWithholdsTheMessageWhateverItsClass" to 10,
            "MessageBehindARefusedDslTest.aClassAboutTheNameOrTheCodeLeavesTheMessageStanding" to 4,
            "MessageBehindARefusedDslTest.aRefusedOperationWithholdsTheComputationsMessage" to 4,
            "MessageBehindARefusedDslTest.theBaseYearAndNoTextClassesAreWithheldToo" to 8,
            "MessageBehindARefusedDslTest.everyRefusedConditionShapeKeepsOnlyThePresenceClass" to 59,
            "MessageBehindARefusedDslTest.aMessagelessComputationKeepsThePresenceClass" to 6,
            "CalculationMemberWithholdingTest.aTextDefectWithholdsItsOwnComputationsMemberClasses" to 25,
            "CalculationMemberWithholdingTest.aDefectElsewhereWithholdsOnlyTheCycle" to 17,
            "CalculationMemberWithholdingTest.theCalculationModuleAsksItsStagesInOrder" to 13,
            "CalculationMemberWithholdingTest.theErrorTextClassesStandBesideTheCalculationModule" to 10,
            "CalculationMemberWithholdingTest.aCircleThroughAFilteredComputationIsNoCycle" to 3,
            "DispatchOrderTest.aPerDeclarationFamilyReportsInDeclarationOrderAndFamiliesStayProducerMajor" to 1
        )
    }
}
