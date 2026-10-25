package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.edit.EditSession
import io.github.mbackschat.a12.dm.interpreter.model.RelevantEntity
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import io.github.mbackschat.a12.dm.rulekit.dsl.Condition
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl
import io.github.mbackschat.a12.dm.rulekit.write.ModelWriter
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Partial validation dispatches each rule through ONE selector per error field, merged from every relevant pointer
 * of that field: a coordinate the pointers disagree on becomes a wildcard (a12-kernel-lean SPEC-2026-10-24-12/-13,
 * the `ordinary-partial-dispatch` and `nested-join-partial` captures). Operand reads keep the caller's set, so a
 * widened row still reads a non-relevant cell as unknown.
 *
 * The rows discriminate the merge from its rivals: `sharedPart` separates per-coordinate merging from a common
 * prefix; a joined rule iterating a level below the join runs only when the joined coordinate is a wildcard, and
 * one with no ordinary level ignores the selector's coordinates (`skuOfLine3`).
 *
 * Own domain (order picking: lines with parts, picks keyed by the same line number).
 */
class PartialDispatchSelectorDiffTest {

    private val skeleton = DocumentModelDraft.named("order-picking").locale("en_US")
        .rootGroup("Order") { r -> r
            .stringField("Email") { }
            .group("Lines") { l -> l.repeatable(4).indexField("LineNo")
                .stringField("LineNo") { }.stringField("Sku") { }.stringField("Code") { }
                .group("Parts") { p -> p.repeatable(3)
                    .stringField("PartNo") { }.numberField("Count") { it.fractionDigits(0) }.stringField("Tag") { } } }
            .group("Picks") { p -> p.repeatable(4).indexField("LineNo").stringField("LineNo") { }.stringField("Ref") { } } }
        .build().toJson()

    private val edit = EditSession.read(skeleton)
    private val order = edit.group("/Order")
    private val lines = edit.group("/Order/Lines")
    private val parts = edit.group("/Order/Lines/Parts")
    private val count = parts.num("Count")
    private val tag = parts.str("Tag")
    private val picks = edit.group("/Order/Picks")
    private val ref = picks.str("Ref")

    private val model = listOf(
        Triple("WIDE", parts.field("Count"), tag.filled().and(order.str("Email").filled().or(count.filled()))),
        Triple("EXACT", parts.field("Count"), tag.filled().and(Dsl.currentRepetition(lines.ref()).gt(Dsl.number(2L)).or(count.filled()))),
        Triple("NESTED", parts.field("Count"), count.filled().and(Dsl.noFieldFilled(parts.field("PartNo"), picks.field("Ref")))),
        Triple("NESTED_OR", parts.field("Count"), count.filled().or(tag.filled().and(Dsl.noFieldFilled(parts.field("PartNo"), picks.field("Ref"))))),
        Triple("FLAT", lines.field("Sku"), lines.str("Sku").filled().or(lines.str("Code").filled().and(ref.notFilled()))),
    ).fold(edit.toJson()) { json, (code, errorField, condition: Condition) ->
        ModelWriter.addRule(json, order.rule { it.name(code + "Rule").code(code).errorField(errorField)
            .`when`(condition).message(Dsl.en("Check the picking.")).build() })
    }

    private fun document(thirdLineSecondCount: String = "6") = """{"Order":{"Email":"e","Lines":[
        {"LineNo":"A","Sku":"s","Code":"c","Parts":[{"Count":2,"Tag":"t"},{"Count":3,"Tag":"t"},{"Tag":"t"}]},
        {"LineNo":"B","Sku":"s","Parts":[{"Count":4,"Tag":"t"},{"Count":7,"Tag":"t"}]},
        {"LineNo":"C","Sku":"s","Code":"c","Parts":[{"Count":5,"Tag":"t"},{"Count":$thirdLineSecondCount,"Tag":"t"}]}],
        "Picks":[{"LineNo":"A"},{"LineNo":"B","Ref":"r"},{"LineNo":"D","Ref":"r"}]}}"""

    private val base = listOf("/Order/Email", "/Order/Lines/Parts/Tag", "/Order/Lines/Code", "/Order/Lines/Parts/PartNo",
        "/Order/Picks/Ref", "/Order/Lines/LineNo", "/Order/Picks/LineNo").map(RelevantEntity::anyRep).toSet()

    private fun counts(vararg cells: Pair<Int, Int>) = cells.map { (line, part) ->
        RelevantEntity("/Order/Lines/Parts/Count", listOf(1, line, part, 1)) }

    private val rows: Map<String, Pair<String, Set<RelevantEntity>>> = linkedMapOf(
        "one" to (document() to base + counts(1 to 1)),
        "acrossLinesAndParts" to (document() to base + counts(1 to 1, 3 to 2)),
        "oneLine" to (document() to base + counts(1 to 1, 1 to 2)),
        "sharedPart" to (document() to base + counts(1 to 2, 3 to 2)),
        "every" to (document() to base + RelevantEntity.anyRep("/Order/Lines/Parts/Count")),
        "skuOfLine3" to (document() to base + RelevantEntity("/Order/Lines/Sku", listOf(1, 3, 1))),
        "badCount" to (document("\"x\"") to base + counts(1 to 1, 3 to 2)),
    )

    private fun observed(messages: List<MessageSignature>) =
        messages.filter { it.code in setOf("WIDE", "EXACT", "NESTED", "NESTED_OR", "FLAT") }.map { it.toString().substringBefore("|ref=") }.sorted()

    @Test
    fun a12AndTheInterpreterDispatchTheMergedSelector() {
        val mismatches = mutableListOf<String>()
        val kernel = rows.mapValues { (label, row) ->
            val (json, relevant) = row
            val dynamic = DocumentDifferential.kernelMessages(model, json, true, relevant)
            assertEquals(dynamic, DocumentDifferential.kernelMessages(model, json, false, relevant), "$label strategies")
            val interpreted = DocumentDifferential.interpreterMessages(model, json, relevant)
            if (observed(dynamic) != observed(interpreted)) mismatches += "$label kernel=${observed(dynamic)} interpreter=${observed(interpreted)}"
            observed(dynamic)
        }
        assertEquals(emptyList<String>(), mismatches)
        assertEquals(PINNED, kernel.filterKeys { it in PINNED }.mapValues { (_, signatures) -> signatures.map(::cell) })
    }

    /** The generated mandatory check reads nothing but its own cell, so on a row only the merge reaches, that
     *  cell is not relevant and reads unknown: the check stays silent whether or not the merge dispatched it, and
     *  widening cannot surface through it. `lineTwo` and `full` are the positive controls that it fires at all. */
    @Test
    fun aMergedSelectorCannotSurfaceThroughTheMandatoryCheck() {
        val required = DocumentModelDraft.named("order-lines").locale("en_US")
            .rootGroup("Order") { r -> r.group("Lines") { l -> l.repeatable(4).stringField("Sku") { it.required() }.stringField("Note") { } } }
            .build().toJson()
        val json = """{"Order":{"Lines":[{"Sku":"s"},{"Note":"n"},{"Sku":"s"}]}}"""
        for ((label, relevant) in linkedMapOf(
            "lineOneAndThree" to setOf(RelevantEntity("/Order/Lines/Sku", listOf(1, 1, 1)), RelevantEntity("/Order/Lines/Sku", listOf(1, 3, 1))),
            "lineOne" to setOf(RelevantEntity("/Order/Lines/Sku", listOf(1, 1, 1))),
            "lineTwo" to setOf(RelevantEntity("/Order/Lines/Sku", listOf(1, 2, 1))),
            "lineOneAndTwoNote" to setOf(RelevantEntity("/Order/Lines/Sku", listOf(1, 1, 1)), RelevantEntity("/Order/Lines/Sku", listOf(1, 3, 1)),
                RelevantEntity("/Order/Lines/Note", listOf(1, 2, 1))),
            "full" to null,
        )) {
            val dynamic = DocumentDifferential.kernelMessages(required, json, true, relevant)
            assertEquals(dynamic, DocumentDifferential.kernelMessages(required, json, false, relevant), "$label strategies")
            assertEquals(label in setOf("lineTwo", "full"), dynamic.any { it.code == "mandatoryField" }, label)
            assertEquals(dynamic, DocumentDifferential.interpreterMessages(required, json, relevant), label)
        }
    }

    /** `Line.Part code` of a signature, for the pin. */
    private fun cell(signature: String): String {
        val (code, _, pointer) = signature.split('|')
        val indices = Regex("""\[(\d+)]""").findAll(pointer).map { it.groupValues[1] }.drop(1).joinToString(".")
        return "$indices $code"
    }

    private companion object {
        /** The dynamic Kernel on the rows that separate the merge from its rivals. A disagreed coordinate widens
         *  dispatch (`sharedPart`: every line's second part, not every part, and a joined rule below its join keeps
         *  the part coordinate when it can fire without the error cell), a formally invalid pointer still
         *  takes part in the merge (`badCount`), a joined rule iterating below the join is silent under a concrete
         *  line (`one`), and one without that level ignores the line coordinate (`skuOfLine3`). */
        val PINNED = mapOf(
            "one" to listOf("1.1 EXACT", "1.1 WIDE"),
            "sharedPart" to listOf("1.2 EXACT", "3.2 EXACT", "1.2 NESTED_OR", "3.2 NESTED_OR", "1.2 NESTED", "3.2 NESTED",
                "1.2 WIDE", "2.2 WIDE", "3.2 WIDE"),
            "skuOfLine3" to listOf("1 FLAT", "3 FLAT"),
            "badCount" to listOf("1.1 EXACT", "3.1 EXACT", "3.2 EXACT", "1.2 NESTED_OR", "1.3 NESTED_OR", "3.1 NESTED_OR",
                "3.2 NESTED_OR", "1.1 NESTED_OR", "1.1 NESTED",
                "1.1 WIDE", "1.2 WIDE", "1.3 WIDE", "2.1 WIDE", "2.2 WIDE", "3.1 WIDE", "3.2 WIDE"),
        )
    }
}
