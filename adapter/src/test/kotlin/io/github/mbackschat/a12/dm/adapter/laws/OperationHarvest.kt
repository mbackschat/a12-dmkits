package io.github.mbackschat.a12.dm.adapter.laws

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import java.security.MessageDigest
import java.util.HexFormat
import org.junit.jupiter.api.Assertions.assertEquals

/** Source identities preserve repeated inputs from distinct assertions (proposal §5, R3b-T). */
internal object OperationHarvest {
    fun checks(): List<JsonNode> = read("checks", mapOf(
        "ArithmeticOperationGatesTest.howManyDivisionsAndPowersOneExpressionMayCarry" to 14,
        "ArithmeticOperationGatesTest.theKindWalkSilencesThisContextsArityGates" to 4,
        "ArithmeticOperationGatesTest.theSameGatesAnswerInsideARuleCondition" to 4,
        "ArithmeticOperationGatesTest.whatEachOperatorAdmitsAsAnOperand" to 10,
        "BooleanComparisonAgreementTest.theGatesThisFamilyYieldsTo" to 5,
        "BooleanComparisonAgreementTest.whatABooleanDeclarationMayBeComparedTo" to 5,
        "BooleanComparisonAgreementTest.whatAConfirmDeclarationMayBeComparedTo" to 8,
        "ComparisonOperatorOperandsTest.theGateThatRefusesTwoConstants" to 4,
        "ComparisonOperatorOperandsTest.theSidesItDeliberatelyDoesNotType" to 2,
        "ComparisonOperatorOperandsTest.whatAToleranceOperatorDemands" to 5,
        "ComparisonOperatorOperandsTest.whatAnOrderingOperatorDemands" to 6,
        "EnumDisplayComparabilityTest.whatTheTextGateReadsOnTheTextSide" to 7,
        "EnumDisplayComparabilityTest.whenTwoEnumerationsMayBeCompared" to 10,
        "NumberComparisonScaleGateTest.aComputationMaySuppressTheScaleGate" to 2,
        "NumberComparisonScaleGateTest.whatANumberMayStandAgainst" to 3,
        "NumberComparisonScaleGateTest.whichScalesEqualityAdmits" to 11,
        "PatternComparisonOperandsTest.theArmYieldsToTheConstantGate" to 1,
        "PatternComparisonOperandsTest.theFourQuestionsInOrder" to 9,
        "PatternComparisonOperandsTest.whichWrittenOutPatternsTheGateAdmits" to 4,
        "StringEnumComparisonAgreementTest.theArmsThisFamilyYieldsTo" to 6,
        "StringEnumComparisonAgreementTest.whatATextOrEnumerationSideNeedsOfItsPartner" to 9,
        "StringEnumComparisonAgreementTest.whichWrittenOutValuesAnEnumerationAdmits" to 6,
        "TemporalAggregateOperandsTest.aBaseYearSupplementsTheYearComponent" to 5,
        "TemporalAggregateOperandsTest.everyPartialPrecisionIsRefusedWhateverTheFormatSpells" to 6,
        "TemporalAggregateOperandsTest.theExpansionAndTheSilences" to 6,
        "TemporalAggregateOperandsTest.theGateReadsComponentsAndNotSpelling" to 5,
        "TemporalAggregateOperandsTest.theGateReadsTheDeclarationAndNotTheKind" to 6,
        "TemporalComparisonAgreementTest.theArmsThisFamilyYieldsTo" to 3,
        "TemporalComparisonAgreementTest.whatADatePartnerOfAnotherKindDraws" to 8,
        "TemporalComparisonAgreementTest.whatADateRangeComparisonDemands" to 4,
        "TemporalComparisonAgreementTest.whichFormatsADateComparisonAdmits" to 5,
    ))

    fun laws(): List<JsonNode> = read("laws", mapOf(
        "NumberLawsTest.fieldVsFieldScaleGateAppliesToEqualityOnly" to 2,
        "NumberLawsTest.multiplicationRaisesScaleSoEqualityRejectsEvenWithMatchingFieldScales" to 2,
        "NumberLawsTest.onlyACapableSmallerScaleSideMayBePadded" to 8,
        "NumberLawsTest.orderingAStringIsATypeError" to 1,
        "NumberLawsTest.plainArithmeticCountsDivisionContributionsUntilAReset" to 4,
        "NumberLawsTest.powerExponentMustHaveNoDecimalPlaces" to 3,
        "NumberLawsTest.powerOperatorRejectsUnparenthesizedNesting" to 3,
        "NumberLawsTest.powerScaleZeroExceptionStillClearsConstantExpandability" to 4,
        "NumberLawsTest.roundingDecimalPlacesAboveFourteenIsRejected" to 1,
        "NumberLawsTest.stringFieldVsFieldEqualityIsAccepted" to 1,
        "PowerDerivedScaleWaiverLawsTest.aLiteralExponentDerivesScaleZeroOnlyFromAScaleZeroBase" to 6,
        "PowerDerivedScaleWaiverLawsTest.oneWaiverAdmitsEveryUnknownScaleOperationIntoAKnownScaleTarget" to 7,
        "PowerDerivedScaleWaiverLawsTest.theExponentGateReadsKnownScaleAndTheDerivedScaleReadsLiteralness" to 11,
    ))

    internal fun expand(root: JsonNode): List<JsonNode> {
        fun text(node: JsonNode, name: String): String {
            val value = node[name]
            require(value?.isTextual == true) { "Missing or non-text harvest $name" }
            return value.asText()
        }
        val groups = root["groups"]
        require(groups?.isArray == true) { "Missing harvest groups" }
        return groups.flatMap { group ->
            val prefix = text(group, "prefix")
            val suffix = text(group, "suffix")
            val rows = group["rows"]
            require(rows?.isArray == true) { "Missing harvest rows" }
            rows.map { row ->
                require(row is ObjectNode && !row.has("model")) { "Expected factored harvest row" }
                val model = prefix + text(row, "modelMiddle") + suffix
                val digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(model.toByteArray(Charsets.UTF_8)))
                require(digest == text(row, "modelSha256")) { "Harvest model bytes changed: ${row["id"]}" }
                row.deepCopy().apply {
                    remove(listOf("modelMiddle", "modelSha256"))
                    put("model", model)
                }
            }
        }
    }

    private fun read(name: String, sources: Map<String, Int>): List<JsonNode> {
        val rows = expand(ObjectMapper().readTree(checkNotNull(javaClass.getResource(
            "/checker-near-miss/harvested-operation-$name.json")).readText()))
        assertEquals(sources.values.sum(), rows.size)
        assertEquals(sources.flatMap { (source, count) -> (1..count).map { "$source#$it" } }.toSet(),
            rows.map { it.get("id").asText() }.toSet())
        return rows
    }
}
