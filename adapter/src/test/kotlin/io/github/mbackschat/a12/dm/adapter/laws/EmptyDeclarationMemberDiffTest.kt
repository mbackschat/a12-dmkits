package io.github.mbackschat.a12.dm.adapter.laws

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import io.github.mbackschat.a12.dm.interpreter.DmInterpreter
import io.github.mbackschat.a12.dm.interpreter.eval.newDocument
import io.github.mbackschat.a12.dm.interpreter.load.ModelLoader
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * What an EMPTY declaration member does at RUNTIME, on the kernel and on the interpreter (KERNEL-FINDINGS KF658).
 *
 * Kernel 31.1.1 reads an empty string member as the absent one, because its expand copy omits it; that is measured on
 * the admission side. Each row here puts one member's empty and absent forms to the formal checks with a value the
 * member would decide: a pattern that would refuse the value, a bound the value lies outside, a date format the value
 * does not follow, and a range separator the value does or does not carry. The interpreter loads each model through
 * its own loader, so a member it reads differently from the kernel shows as a different code set.
 *
 * Own domain (an insurance policy).
 */
class EmptyDeclarationMemberDiffTest {

    private data class Row(val label: String, val field: String, val member: String, val type: String, val value: String)

    @Test
    fun anEmptyMemberDecidesAValueAsTheAbsentOneDoes() {
        val rows = listOf(
            Row("a pattern, a lower-case value", "Code", "pattern", "StringType", "ab"),
            Row("a minimum, a value below zero", "Premium", "minValue", "NumberType", "-5"),
            Row("a maximum, a value above the declared one", "Premium", "maxValue", "NumberType", "99999"),
            Row("a date format, an ISO value", "Start", "format", "DateType", "2024-01-31"),
            Row("a date format, a German value", "Start", "format", "DateType", "31.01.2024"),
            Row("a range separator, a slashed value", "Cover", "rangeSeparator", "DateRangeType", "2024-01-01/2024-02-01"),
            Row("a range separator, an unseparated value", "Cover", "rangeSeparator", "DateRangeType", "2024-01-012024-02-01"),
            Row("a month range's separator, a slashed value", "Months", "rangeSeparator", "DateRangeType", "01/02"),
            Row("a month range's separator, an unseparated value", "Months", "rangeSeparator", "DateRangeType", "0102"),
        )
        val observed = rows.associate { row ->
            val empty = edit(row) { it.put(row.member, "") }
            val absent = edit(row) { it.remove(row.member) }
            row.label to listOf(kernel(empty, row), interpreter(empty, row), kernel(absent, row), interpreter(absent, row))
        }
        assertEquals(
            observed.mapValues { (_, codes) -> listOf(codes[0], codes[0], codes[0], codes[0]) },
            observed,
            "(row → kernel empty, interpreter empty, kernel absent, interpreter absent)",
        )
    }

    private fun kernel(json: String, row: Row): Set<String> =
        RuntimeLaws.firedCodes(json, RuntimeLaws.doc().group(POLICY, 1).field("$POLICY/${row.field}", row.value, 1, 1))

    private fun interpreter(json: String, row: Row): Set<String> {
        val definition = ModelLoader.load(json)
        return DmInterpreter(definition)
            .validateFull(definition.newDocument().group(POLICY, 1).field("$POLICY/${row.field}", row.value, 1, 1), "en_US")
            .map { it.code }.toSet()
    }

    private fun edit(row: Row, change: (ObjectNode) -> Unit): String {
        val tree = MAPPER.readTree(BASE)
        val field = tree.at("/content/modelRoot/rootGroups/0/Group/elements").single { it["name"].asText() == row.field }
        val body = field.at("/Field/fieldType/${row.type}") as ObjectNode
        // A pattern message needs a pattern, so the pattern rows carry none on either form.
        if (row.member == "pattern") body.remove("errorMessage")
        change(body)
        return MAPPER.writeValueAsString(tree)
    }

    private companion object {
        const val POLICY = "/Policy"
        val MAPPER = ObjectMapper()
        val BASE: String = DocumentModelDraft.named("policy-runtime").locale("en_US")
            .rootGroup("Policy") { p ->
                p.stringField("Code") { s -> s.pattern("[A-Z]+", "Capitals only.") }
                    .numberField("Premium") { n -> n.min("0").max("9999") }
                    .dateField("Start") { d -> d.format("yyyy-MM-dd") }
                    .dateRangeField("Cover") { r -> r.format("yyyy-MM-dd").separator("/") }
                    .dateRangeField("Months") { r -> r.format("MM").separator("/") }
            }
            .build().toJson()
    }
}
