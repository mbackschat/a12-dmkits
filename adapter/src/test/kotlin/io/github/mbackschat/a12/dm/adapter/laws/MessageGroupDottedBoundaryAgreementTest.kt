package io.github.mbackschat.a12.dm.adapter.laws

import com.fasterxml.jackson.databind.ObjectMapper
import io.github.mbackschat.a12.dm.adapter.KernelConsistencyOracle
import io.github.mbackschat.a12.dm.consistency.syntax.ConditionLanguage
import io.github.mbackschat.a12.dm.core.ReferenceResolvers
import io.github.mbackschat.a12.dm.core.Severity
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** KF214: reserved dotted suffixes stay outside the bounded group-argument reader. */
class MessageGroupDottedBoundaryAgreementTest {
    data class Row(val id: String, val model: String, val classes: List<String>, val portableClasses: List<String>)

    fun rows(): List<Row> = buildList {
        val models = MessageGroupEventAgreementTest()
        for (language in ConditionLanguage.entries) {
            val allowedAtEnd = listOf(if (language == ConditionLanguage.EN) "RootGroup" else "LfdNr", "Zeile", "Usb", "index")
            val reserved = listOf("Vordruckzeile") + when (language) {
                ConditionLanguage.EN -> listOf("RuleGroup", "For", "BaseYear", "value")
                ConditionLanguage.DE -> listOf("RegelKontext", "Fuer", "Referenzjahr", "Wert")
            }
            // `Vordruckname` left the grammar on kernel 31.1.1 and is an ordinary name like `Archive` (KF657); it keeps
            // its place in the grid, which the stored corpus replays in order.
            val ordinary = listOf("Vordruckname", "Archive")
            val words = allowedAtEnd + "Vordruckname" + reserved + "Archive"
            for (word in words) for (quoted in listOf(false, true)) {
                val name = if (quoted) "'$word'" else word
                val code = if (!quoted && word in reserved) "MVK_UNEXPECTED_TOKEN" else "INVALID_GROUP"
                val portable = if (!quoted && word !in ordinary) emptyList() else listOf(code)
                add(Row("${language.name.lowercase()}-$word-${if (quoted) "quoted" else "bare"}",
                    models.boundaryModel(language, 1, "Loans.$name"), listOf(code), portable))
            }
        }
    }

    @Test
    fun reservedDottedSuffixesRetainMeasuredSilence() {
        val rows = rows()
        assertEquals(44, rows.size)
        assertEquals(rows.size, rows.map { it.id }.toSet().size)
        val oracle = KernelConsistencyOracle()
        val observed = rows.map { row -> row.id to oracle.checkConsistency(row.model, ReferenceResolvers.selfContained())
            .filter { it.severity() == Severity.ERROR }.map {
                Regex("\\[([A-Z][A-Z0-9_]+)]").find(it.message())?.groupValues?.get(1) ?: error(it.message())
            } }
        assertEquals(rows.map { it.id to it.classes }, observed, "kernel dotted-suffix predictions")
        val stored = HarvestedModels.expand(ObjectMapper().readTree(checkNotNull(javaClass.getResource(
            "/checker-near-miss/message-group-dotted-boundaries.json")).readText()))
        assertEquals(rows, stored.map { Row(it["id"].asText(), it["model"].asText(), it["classes"].map { code -> code.asText() },
            it["portableClasses"].map { code -> code.asText() }) })
        val portable = rows.map { it.id to rejectingCodes(it.model) }
        assertEquals(rows.map { it.id to it.portableClasses }, portable, "bounded dotted-name support")
    }
}
