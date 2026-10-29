package io.github.mbackschat.a12.dm.adapter.laws

import com.fasterxml.jackson.databind.ObjectMapper
import io.github.mbackschat.a12.dm.adapter.KernelConsistencyOracle
import io.github.mbackschat.a12.dm.consistency.syntax.ConditionLanguage
import io.github.mbackschat.a12.dm.core.ReferenceResolvers
import io.github.mbackschat.a12.dm.core.Severity
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** KF214: separate the early multi-up depth check from the later turning-point check. */
class MessageGroupParentBoundaryAgreementTest {
    data class Row(val id: String, val model: String, val classes: List<String>)

    fun rows(): List<Row> = buildList {
        val models = MessageGroupEventAgreementTest()
        for (language in ConditionLanguage.entries) {
            val rule = if (language == ConditionLanguage.EN) "RuleGroup" else "RegelKontext"
            val baseYear = if (language == ConditionLanguage.EN) "BaseYear" else "Referenzjahr"
            for (depth in 1..3) for (ups in 1..3) {
                for ((tailId, tail) in listOf("end" to "", "rule" to rule, "base-year" to baseYear,
                    "minus-name" to "-x", "lexer" to "%", "slash-rule" to "/$rule")) {
                    val code = when {
                        tailId == "lexer" -> LEXER
                        tailId == "slash-rule" && ups > 1 && ups + 1 >= depth -> ABOVE
                        tailId in listOf("rule", "base-year") -> if (ups >= depth) ABOVE else UNEXPECTED
                        ups >= depth -> ABOVE
                        tail.isNotEmpty() -> UNEXPECTED
                        else -> null
                    }
                    val argument = List(ups) { ".." }.joinToString("/") + tail
                    add(Row("${language.name.lowercase()}-depth$depth-up$ups-$tailId",
                        models.boundaryModel(language, depth, argument), listOfNotNull(code)))
                }
            }
        }
    }

    @Test
    fun parentActionsPrecedeOnlyTheTokensTheyActuallyPrecede() {
        val rows = rows()
        assertEquals(108, rows.size)
        assertEquals(rows.size, rows.map { it.id }.toSet().size)
        val oracle = KernelConsistencyOracle()
        val observed = rows.map { row -> row.id to oracle.checkConsistency(row.model, ReferenceResolvers.selfContained())
            .filter { it.severity() == Severity.ERROR }.map {
                Regex("\\[([A-Z][A-Z0-9_]+)]").find(it.message())?.groupValues?.get(1) ?: error(it.message())
            } }
        assertEquals(rows.map { it.id to it.classes }, observed, "kernel parent-action predictions")
        val stored = HarvestedModels.expand(ObjectMapper().readTree(checkNotNull(javaClass.getResource(
            "/checker-near-miss/message-group-parent-boundaries.json")).readText()))
        assertEquals(rows, stored.map { Row(it["id"].asText(), it["model"].asText(), it["classes"].map { code -> code.asText() }) })
        val portable = rows.map { it.id to rejectingCodes(it.model) }
        assertEquals(observed, portable, "separate parent semantic actions")
    }

    private companion object {
        const val LEXER = "MVK_LEXER_STANDARD_ERROR"
        const val UNEXPECTED = "MVK_UNEXPECTED_TOKEN"
        const val ABOVE = "ERROR_TEXT_PARAMETER_TOO_MANY_PARENT_GROUPS"
    }
}
