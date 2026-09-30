package io.github.mbackschat.a12.dm.consistency

import io.github.mbackschat.a12.dm.consistency.syntax.ConditionLanguage
import io.github.mbackschat.a12.dm.consistency.syntax.ConditionRecognizer
import io.github.mbackschat.a12.dm.consistency.syntax.SyntaxSite
import io.github.mbackschat.a12.dm.consistency.syntax.SyntaxEntry
import io.github.mbackschat.a12.dm.consistency.syntax.shapeFailure
import io.github.mbackschat.a12.dm.consistency.syntax.shapeFailureAhead
import io.github.mbackschat.a12.dm.consistency.syntax.lexicalFailure
import io.github.mbackschat.a12.dm.consistency.syntax.referencesIn
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertIs
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class SyntaxVerdictTest {
    @Test
    fun theAheadProjectionKeepsOnlySyntaxReachedBeforeTheLexicalFailure() {
        val earlier = "FieldFilled(Total)) %"
        assertEquals(shapeFailure(earlier, ConditionLanguage.EN, SyntaxEntry.CONDITION),
            shapeFailureAhead(earlier, ConditionLanguage.EN, SyntaxEntry.CONDITION,
                checkNotNull(lexicalFailure(earlier))))
        assertTrue(shapeFailureAhead(earlier, ConditionLanguage.EN, SyntaxEntry.CONDITION,
            checkNotNull(lexicalFailure(earlier))) != null)
        val demanded = "FieldFilled(Total) And %"
        assertEquals(null, shapeFailureAhead(demanded, ConditionLanguage.EN, SyntaxEntry.CONDITION,
            checkNotNull(lexicalFailure(demanded))))
    }

    @Test
    fun computationTextMatchesTheKernelJoiner() {
        assertEquals(9, SyntaxFixtureData.computations.size)
        for (record in SyntaxFixtureData.computations) {
            val row = Json.parseToJsonElement(record).jsonObject
            val body = buildJsonObject {
                val common = row.getValue("common").jsonPrimitive.contentOrNull
                common?.let { put("commonPrecondition", it) }
                put("computationAlternatives", row.getValue("alternatives"))
            }
            assertEquals(row.getValue("joined").jsonPrimitive.content,
                computationCondition(body, "Total", ConditionLanguage.EN)?.text, record)
        }
    }

    @Test
    fun computationFailuresRetainTheirMemberAcrossGeneratedText() {
        data class Row(val common: String?, val alternatives: List<Pair<String?, String>>, val member: String,
            val source: String, val family: CheckFamily, val code: String, val token: String = "")
        val guard = "FieldFilled(../Tier)"
        val pre = "computationAlternatives[0].precondition"
        val op = "computationAlternatives[0].operation"
        val rows = listOf(
            Row(null, listOf(guard to "42.00 +"), op, "42.00 +", CheckFamily.CONDITION_COMPLETENESS, "MVK_INCOMPLETE_INPUT"),
            Row(null, listOf(guard to "42.00 +", guard to "42.00"), op, "42.00 +",
                CheckFamily.CONDITION_COMPLETENESS, "MVK_UNEXPECTED_TOKEN"),
            Row(null, listOf("FieldFilled(../Tier" to "42.00"), pre, "FieldFilled(../Tier",
                CheckFamily.CONDITION_GRAMMAR, "MVK_EXPECTED_TOKEN_NOT_FOUND"),
            Row(null, listOf("$guard And" to "42.00"), pre, "$guard And",
                CheckFamily.CONDITION_COMPLETENESS, "MVK_UNEXPECTED_TOKEN"),
            Row("$guard And", listOf(null to "42.00"), "commonPrecondition", "$guard And",
                CheckFamily.CONDITION_COMPLETENESS, "MVK_UNEXPECTED_TOKEN"),
            Row("", listOf(null to "42.00"), "commonPrecondition", "",
                CheckFamily.CONDITION_COMPLETENESS, "MVK_UNEXPECTED_TOKEN"),
            Row(null, listOf(guard to "42.00 + ]"), op, "42.00 + ]",
                CheckFamily.CONDITION_GRAMMAR, "MVK_UNEXPECTED_TOKEN", "]"),
        )
        for (language in ConditionLanguage.entries) for (row in rows) {
            fun localized(text: String) = if (language == ConditionLanguage.EN) text else
                text.replace("FieldFilled", "FeldAngegeben").replace("And", "Und")
            val model = computationSyntaxModel(row.common?.let(::localized), *row.alternatives.map { (precondition, operation) ->
                buildJsonObject {
                    precondition?.let { put("precondition", localized(it)) }
                    put("operation", localized(operation))
                }.toString()
            }.toTypedArray())
            val base = prepare(assertIs<Admission.Admitted>(admit(inLanguage(model, language)))).tracked()
            val findings = syntaxFindings(base).filterValues { it.isNotEmpty() }
            assertEquals(setOf(row.family), findings.keys, "$language $row")
            val finding = findings.getValue(row.family).single()
            assertEquals(row.code, finding.code, "$language $row")
            assertEquals("/Subscription/Billing/AlternativeProbe", finding.at)
            val source = localized(row.source)
            val start = if (row.token.isEmpty()) source.length else source.indexOf(row.token)
            assertEquals(TextSpan(row.member, start, start + row.token.length), finding.span, "$language $row")
        }
    }

    private fun inLanguage(source: ModelSource, language: ConditionLanguage): ModelSource {
        val root = Json.parseToJsonElement(source.json).jsonObject
        val content = root.getValue("content").jsonObject
        return ModelSource(JsonObject(root + ("content" to JsonObject(content + ("modelConfig" to
            buildJsonObject { put("conditionLanguage", buildJsonObject {
                put("code", if (language == ConditionLanguage.EN) "en_US" else "de_DE")
            }) })))).toString())
    }

    private fun syntaxFindings(base: TrackedFactBase) = mapOf(
        CheckFamily.CONDITION_LEXICALITY to base.everyDeclaration(TrackedFactBase::conditionLexicalFindings),
        CheckFamily.CONDITION_GRAMMAR to base.everyDeclaration(TrackedFactBase::conditionGrammarFindings),
        CheckFamily.CONDITION_COMPLETENESS to base.everyDeclaration(TrackedFactBase::conditionCompletenessFindings),
    )

    @Test
    fun preservedInputsReplayTheKernelVerdictInBothRuntimes() {
        val inputs = SyntaxFixtureData.inputs.map { Json.parseToJsonElement(it).jsonObject }
        val verdicts = SyntaxFixtureData.verdicts.map { Json.parseToJsonElement(it).jsonObject }
            .associateBy { it.getValue("id").jsonPrimitive.content }
        assertEquals(SyntaxFixtureData.verdicts.size, verdicts.size, "duplicate verdict identities")
        assertEquals(inputs.size, inputs.map { it.getValue("source") to it.getValue("row") }.toSet().size,
            "duplicate syntax input identities")
        assertEquals(77, inputs.size)
        assertEquals(inputs.map { "${it.getValue("source").jsonPrimitive.content}#${it.getValue("row")}" }.toSet(),
            verdicts.keys)
        var censored = 0
        for (input in inputs) {
            val id = "${input.getValue("source").jsonPrimitive.content}#${input.getValue("row")}"
            val expected = verdicts.getValue(id)
            val kind = expected.getValue("kind").jsonPrimitive.content
            val failure = shapeFailure(input.getValue("text").jsonPrimitive.content,
                ConditionLanguage.valueOf(input.getValue("language").jsonPrimitive.content),
                SyntaxEntry.valueOf(input.getValue("entry").jsonPrimitive.content))
            if (kind == "CHECK") {
                censored++
                // Preserve the retired tests' syntax acceptance, independently of the censored trace.
                assertEquals(null, failure, "$id: prior syntax-acceptance contract, not a kernel suffix verdict")
                continue
            }
            assertEquals(kind, failure?.kind?.name ?: "PARSED", id)
            assertEquals(expected.getValue("code").jsonPrimitive.contentOrNull, failure?.code, id)
            if (failure != null) {
                assertEquals(expected.getValue("start").jsonPrimitive.int, failure.at, id)
                assertEquals(expected.getValue("end").jsonPrimitive.int, failure.end, id)
                assertEquals(expected.getValue("found").jsonPrimitive.content, failure.found, id)
            }
        }
        assertEquals(verdicts.values.count { it.getValue("kind").jsonPrimitive.content == "CHECK" }, censored)
        assertTrue(censored < inputs.size)
    }

    @Test
    fun theFindingNamesTheMemberThatEndsEarly() {
        val input = SyntaxFixtureData.inputs.map { Json.parseToJsonElement(it).jsonObject }
            .single { it.getValue("source").jsonPrimitive.content.endsWith("theFindingNamesTheMemberThatEndsEarly") }
        val base = prepare(assertIs<Admission.Admitted>(admit(ModelSource(
            input.getValue("originalModel").jsonPrimitive.content)))).tracked()
        val finding = base.everyDeclaration(TrackedFactBase::conditionCompletenessFindings).single()
        assertEquals("/Catalog/PriceRule", finding.at)
        val text = input.getValue("text").jsonPrimitive.content
        assertEquals(TextSpan("errorCondition", text.length, text.length), finding.span)
        assertTrue(finding.explain.contains("errorCondition") && finding.explain.contains("And"))
    }

    @Test
    fun familySelectionFollowsTheVerdictInTheModelsLanguage() {
        val rows = listOf(
            Triple(ConditionLanguage.EN, "FieldFilled(", CheckFamily.CONDITION_COMPLETENESS),
            Triple(ConditionLanguage.EN, "FieldFilled", CheckFamily.CONDITION_GRAMMAR),
            Triple(ConditionLanguage.EN, "FieldFilled(Total) And", CheckFamily.CONDITION_COMPLETENESS),
            Triple(ConditionLanguage.EN, "FieldFilled(Total) Und", CheckFamily.CONDITION_GRAMMAR),
            Triple(ConditionLanguage.EN, "FieldFilled(Total) aNd", CheckFamily.CONDITION_GRAMMAR),
            Triple(ConditionLanguage.DE, "FeldAngegeben(Total) Und", CheckFamily.CONDITION_COMPLETENESS),
            Triple(ConditionLanguage.DE, "FeldAngegeben", CheckFamily.CONDITION_GRAMMAR),
            Triple(ConditionLanguage.EN, "[Total] > ٢", CheckFamily.CONDITION_LEXICALITY),
        )
        for ((language, condition, expected) in rows) {
            val source = basketModel(inBasket = """,
                {"type":"Rule","id":"syntax","name":"SyntaxProbe","Rule":{
                  "errorEntityRelPath":"../Total","errorCondition":${JsonPrimitive(condition)}}}
            """.trimIndent())
            val base = prepare(assertIs<Admission.Admitted>(admit(inLanguage(source, language)))).tracked()
            val findings = syntaxFindings(base).filterValues { it.isNotEmpty() }
            assertEquals(setOf(expected), findings.keys, "$language $condition: $findings")
            val finding = findings.getValue(expected).single()
            val verdict = checkNotNull(shapeFailure(condition, language, SyntaxEntry.CONDITION))
            assertEquals(verdict.code, finding.code)
            assertEquals(TextSpan("errorCondition", verdict.at, verdict.end), finding.span)
            assertEquals("/Basket/SyntaxProbe", finding.at)
        }
    }

    @Test
    fun anUnfinishedWalkReportsOnlyStepsThatCompleted() {
        assertEquals(listOf("Items*"), referencesIn("FieldFilled(Items*/NoSuch.)", ConditionLanguage.EN))
        assertEquals(listOf("Items*/NoSuch"), referencesIn("FieldFilled(Items*/NoSuch/)", ConditionLanguage.EN))
        assertEquals(emptyList(), referencesIn("FieldFilled(Items/NoSuch/)", ConditionLanguage.EN))
        val paths = mapOf(
            "Items*/NoSuch." to listOf("Items*"),
            "Items*/NoSuch/" to listOf("Items*", "Items*/NoSuch"),
            "Items/NoSuch/" to emptyList(),
        )
        for ((path, expected) in paths) {
            val text = "FieldFilled($path)"
            val sites = ConditionRecognizer(text, ConditionLanguage.EN).recognize(SyntaxEntry.CONDITION).sites
            assertEquals(expected, sites.filterIsInstance<SyntaxSite.Completion>()
                .filter { it.kind == SyntaxSite.Kind.PATH_STEP }.map { text.substring(it.start, it.end) }, text)
            assertIs<SyntaxSite.Failure>(sites.last(), text)
        }
    }

    @Test
    fun theTraceDistinguishesRequiredTokensFromUnfinishedChoices() {
        // KF493 / A12ParseTraceTest: these EOF failures were measured with this exact prefix.
        val prefix = "FieldFilled(Total) And "
        val rows = mapOf(
            "FieldFilled" to true,
            "Valid" to false,
            "RoundUpValue(Total," to true,
            "RangeAsNumber(Code, 1," to true,
            "ValueAsDate(Estimated," to true,
            "Max([Total]," to false,
            "AddDays(Placed," to false,
        )
        val expected = rows.mapValues { (suffix, match) -> Triple(prefix.length + suffix.length, match, "") }
        val actual = rows.mapValues { (suffix, _) ->
            shapeFailure(prefix + suffix, ConditionLanguage.EN, SyntaxEntry.CONDITION)?.let {
                Triple(it.at, it.ranOutAwaitingOneToken, it.found)
            }
        }
        assertEquals(expected, actual)
    }

    @Test
    fun bothEntriesAcceptTheLiveCatalogInItsOwnLanguage() {
        val conditions = ConditionCorpus.conditions()
        val operations = ConditionCorpus.operations()
        assertTrue(conditions.isNotEmpty())
        assertTrue(operations.isNotEmpty())
        val failures = mutableListOf<String>()
        for ((entry, rows) in listOf(SyntaxEntry.CONDITION to conditions, SyntaxEntry.OPERATION to operations)) {
            for (row in rows) {
                for ((language, text) in listOf(ConditionLanguage.EN to row.en, ConditionLanguage.DE to row.de)) {
                    shapeFailure(text, language, entry)?.let { failures += "$entry $language ${row.id}: $it" }
                }
            }
        }
        assertEquals(emptyList(), failures)
    }
}
