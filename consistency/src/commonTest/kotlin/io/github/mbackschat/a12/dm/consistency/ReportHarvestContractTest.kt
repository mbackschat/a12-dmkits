package io.github.mbackschat.a12.dm.consistency

import io.github.mbackschat.a12.dm.consistency.syntax.ConditionRecognizer
import io.github.mbackschat.a12.dm.consistency.syntax.SyntaxEntry
import io.github.mbackschat.a12.dm.consistency.syntax.SyntaxSite
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/** R5-C retires an assertion only when its projection or relation survives the harvest. */
class ReportHarvestContractTest {
    @Test
    fun reachedSitesSelectTheFirstFailureWithoutReadingEditorSpans() {
        val row = SyntaxFixtureData.reportChecks.map { Json.parseToJsonElement(it).jsonObject }.single {
            it.getValue("id").jsonPrimitive.content ==
                "ConditionMultipleDefectTest.aRepeatOfOneCodeIsKeptForEachPlaceToFix#1"
        }
        val admitted = assertIs<Admission.Admitted>(admit(ModelSource(row.getValue("model").jsonPrimitive.content)))
        val facts = TrackedFactBase(prepare(admitted), ReadTranscript())
        val element = facts.declarations().single { it.declaredKind == DeclaredKind.RULE }
        val execution = assertNotNull(facts.conditionExecution(element))
        val references = facts.conditionReferencesOf(element)
        assertEquals(listOf("NoSuchField", "NorThisOne"), references.map { it.written })
        val contributions = references.map { reference ->
            val site = execution.recognition.sites.filterIsInstance<SyntaxSite.Completion>().single {
                it.kind == SyntaxSite.Kind.PATH_STEP && it.start == reference.span.start && it.end == reference.span.end
            }
            ConditionFinding(element.key, PathCheckSite(site, PathCheck.ADDRESS), assertNotNull(reference.target.asFinding(
                ReferenceRole.CONDITION_ENTITY, element.path, reference.member, reference.span)))
        }
        val first = contributions.first()
        val second = contributions.last()
        assertFailsWith<IllegalArgumentException> { first.copy(finding = first.finding.copy(span = null)) }
        val unpositionedPostCheck = ConditionFinding(element.key, PostParseCheck.RAW_LENGTH,
            first.finding.copy(span = null))
        assertEquals(listOf(unpositionedPostCheck), execution.selected(listOf(unpositionedPostCheck)))
        assertEquals(listOf(first), execution.selected(contributions.reversed()))
        val moved = first.copy(finding = first.finding.copy(span = second.finding.span))
        assertEquals(listOf(moved), execution.selected(listOf(second, moved)))
        assertEquals(listOf(second), execution.selected(listOf(second)))
        val sibling = first.copy(owner = first.owner.copy(amongSamePath = first.owner.amongSamePath + 1))
        assertEquals(listOf(second), execution.selected(listOf(sibling, second)))
        val sameAction = first.copy(finding = first.finding.copy(explain = "second notification from this action"))
        assertEquals(listOf(first, sameAction), execution.selected(listOf(second, first, sameAction)))
        assertEquals(emptyList(), execution.selected(emptyList()))
        val reached = assertIs<PathCheckSite>(first.site)
        assertFailsWith<IllegalArgumentException> {
            execution.selected(listOf(first.copy(site = reached.copy(step = reached.step.copy(end = reached.step.end + 1)))))
        }
        val checked = mutableListOf<String>()
        val calls = facts.callContributions(element, execution) { member, call ->
            val reference = references.single { it.written == call.operands.single().path }
            checked += reference.written
            reference.target.asFinding(ReferenceRole.CONDITION_ENTITY, element.path, member,
                TextSpan(member, call.at, call.end))
        }
        assertEquals(listOf("NoSuchField", "NorThisOne"), checked)
        assertEquals(execution.recognition.sites.filterIsInstance<SyntaxSite.Completion>()
            .filter { it.kind == SyntaxSite.Kind.CALL }, calls.map { it.site })
        assertEquals(listOf(element.key, element.key), calls.map { it.owner })
        assertEquals(listOf(calls.first()), execution.selected(calls.reversed()))
        assertEquals(emptyList(), facts.callContributions(element, execution) { _, _ -> null })
        val multiple = facts.callListContributions(element, execution) { member, call ->
            val reference = references.single { it.written == call.operands.single().path }
            val finding = assertNotNull(reference.target.asFinding(ReferenceRole.CONDITION_ENTITY,
                element.path, member, TextSpan(member, call.at, call.end)))
            listOf(finding, finding.copy(explain = "second notification from this call"))
        }
        assertEquals(4, multiple.size)
        assertEquals(listOf(calls[0].site, calls[0].site, calls[1].site, calls[1].site), multiple.map { it.site })
        assertEquals(multiple.take(2), execution.selected(multiple.drop(2) + multiple.take(2)))
        val afterParse = execution.postParse(PostParseCheck.REPEATED_UNIQUENESS) {
            listOf(first.finding, sameAction.finding)
        }
        val later = execution.postParse(PostParseCheck.RAW_LENGTH) { listOf(second.finding) }
        assertEquals(listOf(element.key, element.key), afterParse.map { it.owner })
        assertEquals(listOf(PostParseCheck.REPEATED_UNIQUENESS, PostParseCheck.REPEATED_UNIQUENESS),
            afterParse.map { it.site })
        assertEquals(afterParse, execution.selected(later + afterParse))
        assertEquals(later, execution.selected(later))
        assertEquals(listOf(first), execution.selected(later + first))
        val unfinished = ConditionText().apply {
            append(execution.carrier.text + " And", "errorCondition", authored = true)
        }
        val failed = ConditionExecution(element.key, unfinished,
            ConditionRecognizer(unfinished.text, facts.conditionLanguage).recognize(SyntaxEntry.CONDITION))
        assertNotNull(failed.recognition.failure)
        assertEquals(emptyList(), failed.postParse(PostParseCheck.RAW_LENGTH) {
            error("post-parse checks must not execute on unfinished syntax")
        })
        assertFailsWith<IllegalArgumentException> { failed.selected(later) }

    }

    /**
     * Of two declarations at one path, only the first is checked: A12's converter leaves the later one out
     * (KERNEL-FINDINGS KF652). So the later one is scheduled for nothing and owns no condition, while every kept
     * declaration still reads its own target member. Kernel 30.8.1 judged both, which is what these harvested
     * twins were first built to keep apart.
     */
    @Test
    fun theLaterOfTwoDeclarationsAtOnePathOwnsNothing() {
        val identities = setOf(
            "ConditionMultipleDefectTest.twoRulesAtOnePathAreNeverPlacedAgainstEachOther#1",
            "ConditionMultipleDefectTest.twoRulesAtOnePathAreNeverPlacedAgainstEachOther#2",
            "RuleMemberWithholdingTest.aFieldSharingARulesPathKeepsItsOwnFindings#1",
        )
        val rows = SyntaxFixtureData.reportChecks.map { Json.parseToJsonElement(it).jsonObject }
            .filter { it.getValue("id").jsonPrimitive.content in identities }
        assertEquals(identities.size, rows.size)
        for (row in rows) {
            val id = row.getValue("id").jsonPrimitive.content
            val prepared = prepare(assertIs<Admission.Admitted>(admit(ModelSource(row.getValue("model").jsonPrimitive.content))))
            val later = prepared.declarationsByKey.keys.filter { it.amongSamePath > 0 }.toSet()
            assertEquals(1, later.size, "$id: the fixture must declare exactly one later twin")
            assertEquals(later, prepared.conversionDrops.dropped, id)
            val facts = TrackedFactBase(prepared, ReadTranscript())
            assertEquals(prepared.declarationsByKey.keys - later, facts.declarations().map { it.key }.toSet(), id)
            val executed = executeChecks(facts, prepared, CheckCells.NONE)
            assertEquals(emptyList(), executed.selectedConditions(facts).filter { it.owner in later }, id)
            for (cursor in facts.declarations()) {
                val element = prepared.declarationsByKey.getValue(cursor.key)
                val member = when (element.declaredKind) {
                    DeclaredKind.RULE -> "errorEntityRelPath"
                    DeclaredKind.COMPUTATION -> "computedFieldRelPath"
                    else -> null
                }
                val written = member?.let { element.body?.get(it)?.jsonPrimitive?.content }
                assertEquals(written, cursor.declaredReference()?.written, "$id: ${element.kind} ${element.path}")
            }
        }
    }

    @Test
    fun originalReportAssertionsKeepTheirClaimKinds() {
        val rows = SyntaxFixtureData.reportChecks.map { Json.parseToJsonElement(it).jsonObject }
        assertEquals(313, rows.size)
        assertEquals(SOURCES.flatMap { (source, count) -> (1..count).map { "$source#$it" } }.toSet(),
            rows.map { it.getValue("id").jsonPrimitive.content }.toSet())
        assertEquals(setOf(
            // The field `Gift-Note` also draws the field-name sentence A12 states beside its path class (KF580).
            "CalculationMemberWithholdingTest.aDefectElsewhereWithholdsOnlyTheCycle#15",
            "CalculationMemberWithholdingTest.aDefectElsewhereWithholdsOnlyTheCycle#16",
            "CalculationMemberWithholdingTest.aDefectElsewhereWithholdsOnlyTheCycle#17",
            // A matched-scale self-read of a NUMBER target is reached before the rounding stage, as A12's own
            // `CalculationMemberWithholdingLawsTest` row pins; the original froze the checker's silence (KF181).
            "CalculationMemberWithholdingTest.theCalculationModuleAsksItsStagesInOrder#12",
            "ConditionMultipleDefectTest.aRepeatOfOneCodeIsKeptForEachPlaceToFix#1",
            "ConditionMultipleDefectTest.twoRulesAtOnePathAreNeverPlacedAgainstEachOther#2",
            "DispatchOrderTest.aPerDeclarationFamilyReportsInDeclarationOrderAndFamiliesStayProducerMajor#1",
        ), rows.filter { "replacement" in it }.map { it.getValue("id").jsonPrimitive.content }.toSet())
        val reports = rows.associate { row -> row.getValue("id").jsonPrimitive.content to
            checkRead(checkableSource(row.getValue("model").jsonPrimitive.content)).findings }
        val failures = mutableListOf<String>()
        for (row in rows) {
            try {
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
            val expected = row["replacement"]?.jsonObject ?: row
            expected["span"]?.jsonObject?.let { span ->
                assertEquals(listOf(TextSpan(span.getValue("member").jsonPrimitive.content,
                    span.getValue("start").jsonPrimitive.int, span.getValue("end").jsonPrimitive.int)),
                    findings.map { it.span }, id)
            }
            when (Assertion.valueOf(row.getValue("assertion").jsonPrimitive.content)) {
                Assertion.ORDERED_CODES -> assertEquals(expected.strings("expected"), codes, id)
                Assertion.SORTED_CODES -> assertEquals(expected.strings("expected"), codes.sorted(), id)
                Assertion.CODE_SET -> assertEquals(expected.strings("expected"), codes.distinct().sorted(), id)
                Assertion.ORDERED_SITES -> assertEquals(expected.getValue("expected"), buildJsonArray {
                    for (finding in findings) add(buildJsonObject {
                        put("code", finding.code)
                        put("at", finding.at?.let(::JsonPrimitive) ?: JsonNull)
                    })
                }, id)
                Assertion.MEMBERSHIP -> {
                    assertTrue(codes.containsAll(expected.strings("contains")), "$id: required finding absent: $codes")
                    assertTrue(expected.strings("absent").none { it in codes }, "$id: withheld finding present: $codes")
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
            } catch (failure: AssertionError) {
                failures += failure.message.orEmpty()
            }
        }
        assertEquals(emptyList(), failures, "frozen report assertion failures")
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
            "DispatchOrderTest.aPerDeclarationFamilyReportsInDeclarationOrderAndFamiliesStayProducerMajor" to 1
        )
    }
}
