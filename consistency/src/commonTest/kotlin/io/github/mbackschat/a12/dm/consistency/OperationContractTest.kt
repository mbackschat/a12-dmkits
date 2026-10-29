package io.github.mbackschat.a12.dm.consistency

import io.github.mbackschat.a12.dm.consistency.syntax.ConditionLanguage
import io.github.mbackschat.a12.dm.consistency.syntax.ConditionRecognizer
import io.github.mbackschat.a12.dm.consistency.syntax.OperationShape
import io.github.mbackschat.a12.dm.consistency.syntax.SyntaxEntry
import io.github.mbackschat.a12.dm.consistency.syntax.SyntaxSite
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OperationContractTest {
    @Test
    fun roundingRetainsNoResultWhenItsCompletedChildIsRefused() {
        for ((ordinal, expected) in listOf(1 to OperationType.Number(2), 3 to OperationType.Unknown)) {
            val row = SyntaxFixtureData.operationChecks.map { Json.parseToJsonElement(it).jsonObject }.single {
                it.getValue("id").jsonPrimitive.content ==
                    "ArithmeticOperationGatesTest.howManyDivisionsAndPowersOneExpressionMayCarry#$ordinal"
            }
            val prepared = prepare(assertIs<Admission.Admitted>(admit(ModelSource(row.getValue("model").jsonPrimitive.content))))
            val facts = TrackedFactBase(prepared, ReadTranscript())
            val element = facts.declarations().single { it.declaredKind == DeclaredKind.COMPUTATION }
            val target = assertNotNull(computedTargetOf(element)).substringAfterLast('/')
            val carrier = assertNotNull(computationCondition(assertNotNull(element.body), target, facts.conditionLanguage))
            val analysis = OperationAnalysis(facts, element, carrier)
            analysis.analyze()
            val rounded = analysis.recognition.operations.filterIsInstance<OperationShape.Call>()
                .single { it.call.operator == "RoundDown" }
            assertEquals(expected, analysis.value(rounded).type, "harvested row $ordinal")
            assertEquals(ordinal == 3, analysis.findings.any { it.code == "MVK_INVALID_NUMBER_FOR_EXP" })
            if (ordinal == 3) {
                val refused = analysis.contributions.single { it.finding.code == "MVK_INVALID_NUMBER_FOR_EXP" }
                assertEquals(element.key, refused.owner)
                val site = assertIs<SyntaxSite.Operation>(refused.site)
                assertEquals("^", assertIs<OperationShape.Arithmetic>(site.shape).operator.text)
                assertTrue(site in analysis.recognition.sites)
            }
        }
    }

    @Test
    fun operationCellsTrackReferencedScaleAndIgnoreUnrelatedFieldEdits() {
        val row = SyntaxFixtureData.operationLaws.map { Json.parseToJsonElement(it).jsonObject }.single {
            it.getValue("id").jsonPrimitive.content ==
                "NumberLawsTest.multiplicationRaisesScaleSoEqualityRejectsEvenWithMatchingFieldScales#1"
        }
        val original = Json.parseToJsonElement(row.getValue("model").jsonPrimitive.content)
        fun prepared(model: JsonElement) = prepare(assertIs<Admission.Admitted>(admit(ModelSource(model.toString()))))
        fun scaleEdit(value: JsonElement, fieldId: String, insideField: Boolean = false): JsonElement = when (value) {
            is JsonObject -> JsonObject(value.mapValues { (key, child) ->
                val selected = insideField || value["id"] == JsonPrimitive(fieldId)
                if (selected && key == "maxFractionalDigits") JsonPrimitive(0)
                else scaleEdit(child, fieldId, selected)
            })
            is JsonArray -> JsonArray(value.map { scaleEdit(it, fieldId, insideField) })
            else -> value
        }
        val before = prepared(original)
        val baseline = checkRun(before, null, emptyList(), CheckCells.NONE, CellRetention.KEPT)
        val key = schedule(TrackedFactBase(before, ReadTranscript())).single {
            it.key.at?.path == "/Order/ProbeRule" && CheckFamily.ARITHMETIC_OPERATION_GATES in it.key.producer.completes
        }.key
        assertEquals(listOf("MVK_INVALID_COMPARE_DEC_PLACES"),
            assertNotNull(baseline.cells.validIn(before, key)).findings.map { it.code })

        for (fieldId in listOf("/Order/UnitPrice", "/Order/Items/UnitWeight")) {
            val after = prepared(scaleEdit(original, fieldId))
            val retained = baseline.cells.validIn(after, key)
            if (fieldId == "/Order/UnitPrice") assertNull(retained)
            else assertEquals(listOf("MVK_INVALID_COMPARE_DEC_PLACES"), assertNotNull(retained).findings.map { it.code })
            val incremental = checkRun(after, null, emptyList(), baseline.cells, CellRetention.KEPT)
            assertEquals(checkPrepared(after, null, emptyList()), incremental.report)
            assertEquals(fieldId != "/Order/UnitPrice",
                incremental.report.findings.any { it.code == "MVK_INVALID_COMPARE_DEC_PLACES" })
            assertTrue(incremental.families.reused > 0)
            assertTrue(incremental.families.recomputed > 0)
        }
    }

    @Test
    fun operationCompletionRetainsPrecedenceGroupingAndCompletedChildren() {
        fun arithmetic(text: String) = ConditionRecognizer(text, ConditionLanguage.EN)
            .recognize(SyntaxEntry.OPERATION).operations.filterIsInstance<OperationShape.Arithmetic>()
        assertEquals(listOf("^", "*", "+"), arithmetic("[Qty] + [Qty] * [Qty] ^ 2").map { it.operator.text })
        val grouped = arithmetic("{[Qty] ^ 2} ^ 3").last()
        kotlin.test.assertIs<OperationShape.Group>(grouped.left)
        assertEquals(listOf("+"), arithmetic("[Qty] + [Label] +").map { it.operator.text })
        assertEquals(emptyList(), arithmetic("[Qty] + [Label] %"))
        assertEquals(listOf("+"), arithmetic("[Qty] + [Label] > %").map { it.operator.text })
    }

    @Test
    fun numericOperationsMeetEveryHarvestedKernelTarget() {
        assertEquals(53, SyntaxFixtureData.operationLaws.size)
        val differences = SyntaxFixtureData.operationLaws.mapNotNull { record ->
            val row = Json.parseToJsonElement(record).jsonObject
            val expected = row.getValue("observedClasses").jsonArray.map { it.jsonPrimitive.content }.toSet()
            val actual = checkRead(checkableSource(row.getValue("model").jsonPrimitive.content))
                .findings.filter { it.justifiesRejection }.map { it.code }.toSet()
            if (expected == actual) null else "${row.getValue("id").jsonPrimitive.content}: $expected != $actual"
        }
        assertEquals(emptyList(), differences)
    }

    @Test
    fun harvestedOperationsKeepTheirEquivalentAssertions() {
        assertEquals(183, SyntaxFixtureData.operationChecks.size)
        val differences = SyntaxFixtureData.operationChecks.mapNotNull { record ->
            val row = Json.parseToJsonElement(record).jsonObject
            val filter = row.getValue("onlyCodes").takeUnless { it == JsonNull }
                ?.jsonArray?.map { it.jsonPrimitive.content }?.toSet()
            val actual = checkRead(checkableSource(row.getValue("model").jsonPrimitive.content))
                .findings.map { it.code }.filter { filter == null || it in filter }
            // R3b-TP's separate completed-host measurements correct these three former silences.
            val key = if (row.getValue("id").jsonPrimitive.content in CORRECTED_SILENCES) "completedKernelClasses" else "classes"
            val expected = row.getValue(key).jsonArray.map { it.jsonPrimitive.content }
            if (expected == actual) null else "${row.getValue("id").jsonPrimitive.content}: $expected != $actual"
        }
        assertEquals(emptyList(), differences)
    }

    private companion object {
        val CORRECTED_SILENCES = setOf(
            "ComparisonOperatorOperandsTest.theSidesItDeliberatelyDoesNotType#1",
            "ComparisonOperatorOperandsTest.theSidesItDeliberatelyDoesNotType#2",
            "TemporalAggregateOperandsTest.theExpansionAndTheSilences#5",
        )
    }
}
