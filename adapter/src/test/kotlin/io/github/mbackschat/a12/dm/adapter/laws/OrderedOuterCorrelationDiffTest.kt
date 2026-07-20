package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.interpreter.DmInterpreter
import io.github.mbackschat.a12.dm.interpreter.eval.Document
import io.github.mbackschat.a12.dm.interpreter.eval.newDocument
import io.github.mbackschat.a12.dm.interpreter.load.ModelLoader
import io.github.mbackschat.a12.dm.interpreter.model.MsgType
import io.github.mbackschat.a12.dm.edit.EditSession
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import io.github.mbackschat.a12.dm.rulekit.dsl.Condition
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl
import io.github.mbackschat.a12.dm.rulekit.dsl.FieldListRef
import io.github.mbackschat.a12.dm.rulekit.dsl.NumberRef
import io.github.mbackschat.a12.dm.rulekit.dsl.Rule
import io.github.mbackschat.a12.dm.rulekit.dsl.Severity
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * IF159 — a single comparator-only `$` self-correlation is served by the ordered tally plan, without changing
 * the kernel's row-wise three-valued semantics. One typed rulekit rule per comparison/polarity is fed to all
 * three engines. The cases deliberately distinguish duplicate boundaries, no-witness/all-equal inputs, physical
 * self-exclusion, malformed comparison and payload cells, and both `AtLeastOneFieldFilled`/`NoFieldFilled`.
 */
class OrderedOuterCorrelationDiffTest {

    private val base = DocumentModelDraft.named("order").locale("en_US")
        .rootGroup("Order") { order ->
            order.group("Items") { items ->
                items.repeatable(MAX)
                    .numberField("Count") { it.fractionDigits(0, 0) }
                    .numberField("Payload") { it.fractionDigits(0, 0) }
            }
        }.build().toJson()
    private val edit = EditSession.read(base)
    private val items = edit.group(ITEMS)
    private val count = items.num("Count")
    private val payloads = edit.group(ORDER).flatten("Items*/Payload")

    private val rules = Comparison.entries.flatMap { comparison ->
        Quantifier.entries.map { quantifier -> rule(comparison, quantifier) }
    }
    private val kernelModel = rules.fold(base) { json, rule -> TypedRules.inject(json, rule) }
    private val model = ModelLoader.load(kernelModel)
    private val interpreter = DmInterpreter(model)
    private val groovy = RuntimeLaws.kernelEngine(kernelModel)
    private val java = RuntimeLaws.kernelJavaEngine(kernelModel)

    @Test
    fun duplicateAndBoundaryWitnessesAgreeAcrossAllComparatorsAndPolarities() {
        agree(
            "duplicate and boundary witnesses",
            rows("1", "2", "2", "3"),
            expected(
                Comparison.LT to Counts(any = 3, none = 1),
                Comparison.LE to Counts(any = 3, none = 1),
                Comparison.GT to Counts(any = 3, none = 1),
                Comparison.GE to Counts(any = 3, none = 1),
                Comparison.EQ to Counts(any = 2, none = 2),
                Comparison.NE to Counts(any = 4, none = 0),
            ),
        )
    }

    @Test
    fun allEqualNoWitnessCasesAgreeAcrossBothPolarities() {
        agree(
            "all equal: strict and not-equal have no witness",
            rows("5", "5", "5"),
            expected(
                Comparison.LT to Counts(any = 0, none = 3),
                Comparison.LE to Counts(any = 3, none = 0),
                Comparison.GT to Counts(any = 0, none = 3),
                Comparison.GE to Counts(any = 3, none = 0),
                Comparison.EQ to Counts(any = 3, none = 0),
                Comparison.NE to Counts(any = 0, none = 3),
            ),
        )
    }

    @Test
    fun oneRowProvesPhysicalSelfExclusionForEveryPlan() {
        agree(
            "one physical row cannot witness itself",
            rows("5"),
            expected(*Comparison.entries.map { it to Counts(any = 0, none = 1) }.toTypedArray()),
        )
    }

    @Test
    fun malformedAndEmptyCellsPreserveUnknownRatherThanBecomingFalse() {
        agree(
            "invalid payload contributes UNKNOWN and invalid Count cannot join",
            listOf(
                ItemRow.builder().count("1").payload("bad").build(),
                ItemRow.builder().count("2").emptyPayload().build(),
                ItemRow.builder().count("3").payload("7").build(),
                ItemRow.builder().count("bad").payload("7").build(),
            ),
            expected(
                Comparison.LT to Counts(any = 0, none = 1),
                Comparison.LE to Counts(any = 0, none = 1),
                Comparison.GT to Counts(any = 2, none = 1),
                Comparison.GE to Counts(any = 2, none = 1),
                Comparison.EQ to Counts(any = 0, none = 3),
                Comparison.NE to Counts(any = 2, none = 0),
            ),
        )
    }

    private fun rule(comparison: Comparison, quantifier: Quantifier): Rule {
        val selfExcluded = Dsl.currentRepetition(items.ref())
            .ne(Dsl.currentRepetition(items.ref(), true))
        val filtered = payloads.having(selfExcluded.and(comparison.condition(count, count.outer())))
        return items.rule {
            it.name("R_${comparison.name}_${quantifier.name}")
                .code(code(comparison, quantifier))
                .errorField(count.field())
                .`when`(count.filled().and(quantifier.condition(filtered)))
                .severity(Severity.ERROR)
                .message(Dsl.en("comparison witness"))
                .build()
        }
    }

    private fun agree(label: String, rows: List<ItemRow>, expected: Map<String, Int>) {
        val kernelDoc = RuntimeLaws.doc().group(ORDER, 1)
        val interpreterDoc = model.newDocument().group(ORDER, 1)
        rows.forEachIndexed { index, row ->
            val repetition = index + 1
            kernelDoc.group(ITEMS, 1, repetition)
            interpreterDoc.group(ITEMS, 1, repetition)
            row.place(kernelDoc, interpreterDoc, repetition)
        }

        val groovyInstance = groovy.instance(kernelDoc)
        val javaInstance = java.instance(kernelDoc)
        val groovySignatures = groovy.validationSignaturesSorted(groovyInstance)
        val javaSignatures = java.validationSignaturesSorted(javaInstance)
        val interpreterResult = interpreter.validate(interpreterDoc)
        val interpreterSignatures = interpreterResult.messages.map {
            "${it.code}|${if (it.type == MsgType.OMISSION) "OMISSION_ERROR" else "VALUE_ERROR"}|${it.errorPath}"
        }.sorted()

        assertEquals(emptyList<Any>(), interpreterResult.unsupported, "$label — every rule must stay supported")
        assertEquals(groovySignatures, javaSignatures, "$label — generated Java must equal dynamic Groovy")
        assertEquals(groovySignatures, interpreterSignatures, "$label — interpreter must equal dynamic Groovy")
        assertEquals(expected, ruleCounts(groovySignatures), "$label — expected witness/no-witness boundary")
    }

    private fun ruleCounts(signatures: List<String>): Map<String, Int> =
        signatures.map { it.substringBefore('|') }
            .filter { code -> rules.any { it.code() == code } }
            .groupingBy { it }
            .eachCount()

    private fun rows(vararg counts: String): List<ItemRow> =
        counts.map { ItemRow.builder().count(it).payload("7").build() }

    private fun expected(vararg counts: Pair<Comparison, Counts>): Map<String, Int> = buildMap {
        counts.forEach { (comparison, tally) ->
            if (tally.any > 0) put(code(comparison, Quantifier.ANY), tally.any)
            if (tally.none > 0) put(code(comparison, Quantifier.NONE), tally.none)
        }
    }

    private fun code(comparison: Comparison, quantifier: Quantifier) = "${comparison.name}_${quantifier.name}"

    private enum class Comparison {
        LT, LE, GT, GE, EQ, NE;

        fun condition(inner: NumberRef, outer: NumberRef): Condition = when (this) {
            LT -> inner.lt(outer)
            LE -> inner.le(outer)
            GT -> inner.gt(outer)
            GE -> inner.ge(outer)
            EQ -> inner.eq(outer)
            NE -> inner.ne(outer)
        }
    }

    private enum class Quantifier {
        ANY, NONE;

        fun condition(fields: FieldListRef): Condition = when (this) {
            ANY -> Dsl.atLeastOneFieldFilled(fields)
            NONE -> Dsl.noFieldFilled(fields)
        }
    }

    private data class Counts(val any: Int, val none: Int)

    private sealed interface Cell {
        data class Value(val text: String) : Cell
        data object Empty : Cell
    }

    private class ItemRow private constructor(
        private val count: Cell,
        private val payload: Cell,
    ) {
        fun place(kernel: RuntimeLaws.Doc, interpreter: Document, repetition: Int) {
            place(count, COUNT, kernel, interpreter, repetition)
            place(payload, PAYLOAD, kernel, interpreter, repetition)
        }

        private fun place(
            cell: Cell,
            path: String,
            kernel: RuntimeLaws.Doc,
            interpreter: Document,
            repetition: Int,
        ) {
            when (cell) {
                is Cell.Value -> {
                    kernel.field(path, cell.text, 1, repetition, 1)
                    interpreter.field(path, cell.text, 1, repetition, 1)
                }
                Cell.Empty -> {
                    kernel.emptyField(path, 1, repetition, 1)
                    interpreter.emptyField(path, 1, repetition, 1)
                }
            }
        }

        class Builder {
            private var count: Cell? = null
            private var payload: Cell? = null

            fun count(value: String) = apply { count = Cell.Value(value) }
            fun payload(value: String) = apply { payload = Cell.Value(value) }
            fun emptyPayload() = apply { payload = Cell.Empty }

            fun build() = ItemRow(
                checkNotNull(count) { "Count is required" },
                checkNotNull(payload) { "Payload is required" },
            )
        }

        companion object {
            fun builder() = Builder()
        }
    }

    private companion object {
        const val MAX = 100
        const val ORDER = "/Order"
        const val ITEMS = "/Order/Items"
        const val COUNT = "/Order/Items/Count"
        const val PAYLOAD = "/Order/Items/Payload"
    }
}
