package io.github.mbackschat.a12.dm.consistency

import io.github.mbackschat.a12.dm.consistency.syntax.ConditionLanguage
import io.github.mbackschat.a12.dm.consistency.syntax.ReferenceSpan
import io.github.mbackschat.a12.dm.consistency.syntax.operandOf
import io.github.mbackschat.a12.dm.consistency.syntax.referenceSpansIn
import io.github.mbackschat.a12.dm.consistency.syntax.referencesIn
import io.github.mbackschat.a12.dm.consistency.syntax.semanticIndexKeysIn
import io.github.mbackschat.a12.dm.consistency.syntax.tokenize
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class NumericNameContractTest {
    @Test
    fun numericNamesKeepTheirSpellingAndOccurrence() {
        assertEquals(listOf(ReferenceSpan("05", 12, 14)),
            referenceSpansIn("FieldFilled(05)", ConditionLanguage.EN))
        assertEquals(listOf(ReferenceSpan("5", 12, 13)),
            referenceSpansIn("FieldFilled(5)", ConditionLanguage.EN))
    }

    @Test
    fun numericKeysInFragmentsRemainDistinctFromLiteralsAndOtherSlots() {
        val language = ConditionLanguage.EN
        val fragment = "Items/Count For 5"
        assertEquals(listOf("Items/Count", "5"), referencesIn(fragment, language))
        assertEquals(listOf(ReferenceSpan("5", 16, 17)), semanticIndexKeysIn(fragment, language, "For"))
        assertEquals(emptyList(), referencesIn("5", language))
        assertEquals(emptyList(), referencesIn("CustomCondition 5", language))
        assertEquals(listOf("Total"), referencesIn("RoundUpValue(Total, 5) > 0", language))
        assertNull(operandOf(tokenize("5"), language, "5").path)
    }

    @Test
    fun harvestedNumericKeysKeepTheirOrderedCodeLists() {
        assertEquals(18, SyntaxFixtureData.numericKeys.size)
        for (record in SyntaxFixtureData.numericKeys) {
            val row = Json.parseToJsonElement(record).jsonObject
            val actual = checkRead(checkableSource(row.getValue("model").jsonPrimitive.content)).findings
                .filter { it.justifiesRejection }.map { it.code }
            assertEquals(row.getValue("classes").jsonArray.map { it.jsonPrimitive.content }, actual,
                row.getValue("id").jsonPrimitive.content)
        }
    }
}
