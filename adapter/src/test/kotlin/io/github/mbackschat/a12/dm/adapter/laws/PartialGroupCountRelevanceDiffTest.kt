package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.adapter.laws.RuntimeLaws.Relevant
import io.github.mbackschat.a12.dm.edit.EditSession
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl
import io.github.mbackschat.a12.dm.interpreter.PreparedModel
import io.github.mbackschat.a12.dm.interpreter.RelevantCoordinate
import java.util.Locale
import io.github.mbackschat.a12.dm.interpreter.DmInterpreter
import io.github.mbackschat.a12.dm.interpreter.eval.newDocument
import io.github.mbackschat.a12.dm.interpreter.load.ModelLoader
import io.github.mbackschat.a12.dm.interpreter.model.RelevantEntity
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import io.github.mbackschat.a12.dm.modelkit.FieldKind
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Differential — when partial validation can decide `NumberOfFilledGroups`, which A12 answers on the counted
 * GROUP rather than on the fields it contains (KERNEL-FINDINGS KF464, INTERPRETER-FINDINGS IF339).
 *
 * Each row varies one thing against a neighbour: which field is covered concretely and in how many rows, which
 * row a partial coverage leaves out, and which row holds a malformed `Weight`. The readings a reader could hold
 * split on them. Asking the fields one by one, as the field-expanding `NumberOfFilledFields` beside the counts
 * does, refuses a group whose own identifiers are settled. Counting the held rows alone misses that A12 also
 * inspects the FIRST declared row and every declared row the document lacks. Gating every row on its errors
 * refuses a count A12 gives: only the first row has the strict error gate, while later presence must still be decided. The fixed `Label` beside the
 * fixed `Stamp` carries the first-row rule to an operand with no star; A12 refuses a lone fixed operand
 * (`MVK_PARAMSIZE_INVALIDGN`), so the count takes both. The mixed count over `Batches*` and `Label` is decided
 * only when both operands are, measured with each side undecided in turn.
 *
 * Every carrier compares with the value full validation gives it, so it fires exactly when it is decidable; the
 * full-validation reading runs beside each row as the liveness control.
 */
class PartialGroupCountRelevanceDiffTest {

    @Test
    fun laterPresenceMustRemainDecidedAfterTheStrictFirstInstanceGate() {
        val base = DocumentModelDraft.named("seal-count-availability").locale("en_US")
            .rootGroup("Item") { item ->
                item.stringField("Title") { }.group("Batches") { batch ->
                    batch.repeatable(2).group("Seal") { seal ->
                        seal.numberField("Mass") { it.fractionDigits(0) }
                            .numberField("Volume") { it.fractionDigits(0) }
                    }
                }
            }.build().toJson()
        val session = EditSession.read(base)
        val item = session.group("/Item")
        val title = item.str("Title")
        val seals = item.flattenGroup("Batches*/Seal")
        for (n in 0..2) item.addRule {
            it.name("Count$n").code("COUNT$n").errorField(title)
                .`when`(title.filled().and(Dsl.numberOfFilledGroups(seals).eq(n.toLong())))
                .message(Dsl.en("Check seal count.")).build()
        }
        val jsonModel = session.toJson()
        assertEquals(emptyList<String>(), RuntimeLaws.consistencyMessages(jsonModel))
        val dynamic = RuntimeLaws.kernelEngine(jsonModel)
        val static = RuntimeLaws.kernelJavaEngine(jsonModel)
        val prepared = PreparedModel.loadExpandedJson(jsonModel)
        val whole = setOf(Relevant.of("/Item", 0))
        fun fields(massRow: Int, volumeRow: Int) = setOf(
            Relevant.of("/Item/Title", 0, 0),
            Relevant.of("/Item/Batches/Seal/Mass", 0, massRow, 0, 0),
            Relevant.of("/Item/Batches/Seal/Volume", 0, volumeRow, 0, 0),
        )
        data class Case(val name: String, val later: String, val selection: Set<Relevant>,
                        val full: String?, val partial: String?, val formalCount: Int = 0)
        val cases = listOf(
            Case("clean empty", "{}", whole, "COUNT1:OMISSION_ERROR", "COUNT1:OMISSION_ERROR"),
            Case("malformed only", """{"Mass":"bad"}""", whole, null, null, 1),
            Case("admitted beside malformed", """{"Mass":"bad","Volume":2}""", whole,
                "COUNT2:VALUE_ERROR", "COUNT2:VALUE_ERROR", 1),
            Case("content visible", """{"Mass":2}""", fields(0, 1), "COUNT2:VALUE_ERROR", "COUNT2:VALUE_ERROR"),
            Case("same content hidden", """{"Mass":2}""", fields(1, 0), "COUNT2:VALUE_ERROR", null),
        )
        for (case in cases) {
            val json = """{"Item":{"Title":"ready","Batches":[{"Seal":{"Mass":1}},{"Seal":${case.later}}]}}"""
            for (partial in listOf(false, true)) {
                val label = "${case.name}/partial=$partial"
                val kernel = if (partial) dynamic.partialValidationMessages(dynamic.instance(json), case.selection, null, Locale.US)
                    else dynamic.validationMessages(dynamic.instance(json), null, Locale.US)
                val java = if (partial) static.partialValidationMessages(static.instance(json), case.selection, null, Locale.US)
                    else static.validationMessages(static.instance(json), null, Locale.US)
                assertEquals(listOfNotNull(if (partial) case.partial else case.full),
                    kernel.filter { it.errorCode.startsWith("COUNT") }.map { "${it.errorCode}:${it.messageType}" },
                    "$label: count/polarity")
                assertEquals(case.formalCount, kernel.count { it.errorCode == "zahlHatUngueltigeZeichen" }, "$label: formal")
                val expected = kernel.map {
                    "${it.errorCode}|${it.messageType}|${it.errorFieldPointer.fullName()}|${it.errorFieldPointer.repetitionIndexes()}"
                }.sorted()
                assertEquals(expected, java.map {
                    "${it.errorCode}|${it.messageType}|${it.errorFieldPointer.fullName()}|${it.errorFieldPointer.repetitionIndexes()}"
                }.sorted(), "$label: static")
                val doc = prepared.documents.readDocument(json)
                val interpreter = prepared.createInterpreter()
                val report = if (partial) interpreter.validatePart(doc, case.selection.map { pointer ->
                    prepared.model.relevantPointer(pointer.path(), pointer.reps().map {
                        if (it == 0) RelevantCoordinate.All else RelevantCoordinate.Exact(it)
                    })
                }) else interpreter.validateFull(doc)
                assertEquals(emptyList<Any>(), report.unsupported, label)
                assertEquals(expected, report.findings.map {
                    "${it.code}|${it.type.name}_ERROR|${it.errorPath.fullName}|${it.errorPath.repetitionIndexes}"
                }.sorted(), "$label: interpreter")
            }
        }
    }

    private companion object {
        const val ITEM = "/Item"
        const val TITLE = "/Item/Title"
        const val BATCHES = "/Item/Batches"
        const val LOT = "/Item/Batches/Lot"
        const val CODE = "/Item/Batches/Seal/Code"
        const val WEIGHT = "/Item/Batches/Seal/Weight"
        const val LABEL = "/Item/Label"
        const val TEXT = "/Item/Label/Text"
        const val GRADE = "/Item/Label/Grade"
        const val STAMP = "/Item/Stamp"
        const val MARK = "/Item/Stamp/Mark"
    }

    private fun model(capacity: Int) = DocumentModelDraft.named("warehouse-partial-count").locale("en_US")
        .rootGroup("Item") { i ->
            i.field("Title", FieldKind.STRING)
                .group("Batches") { b ->
                    b.repeatable(capacity).field("Lot", FieldKind.STRING)
                        .group("Seal") { it.field("Code", FieldKind.STRING).field("Weight", FieldKind.NUMBER) }
                }
                .group("Label") { it.field("Text", FieldKind.STRING).field("Grade", FieldKind.NUMBER) }
                .group("Stamp") { it.field("Mark", FieldKind.STRING) }
        }.build().toJson().let { base ->
            carriers.entries.fold(base) { json, (code, carrier) ->
                RuntimeLaws.withRule(json, ITEM, code, "../Title", code, "FieldFilled($TITLE) And $carrier")
            }
        }

    private val carriers = linkedMapOf(
        "NFG_BATCHES" to "NumberOfFilledGroups($BATCHES*) == 2",
        "NFG_SEALS" to "NumberOfFilledGroups($BATCHES*/Seal) == 2",
        "NFF_BATCHES" to "NumberOfFilledFields($BATCHES*) == 6",
        "NFG_FIXED" to "NumberOfFilledGroups($LABEL, $STAMP) == 2",
        "NFG_MIXED" to "NumberOfFilledGroups($BATCHES*, $LABEL) == 3",
    )

    private data class Selection(val path: String, val reps: List<Int>)

    private fun everyRow(path: String) = Selection(path, List(path.count { it == '/' }) { RelevantEntity.ALL })

    private fun row(path: String, vararg reps: Int) = Selection(path, reps.toList())

    private val batchFields = listOf(LOT, CODE, WEIGHT).map(::everyRow)
    private val fixedFields = listOf(TEXT, GRADE, MARK).map(::everyRow)

    /**
     * `Lot` named concretely in all four declared rows: the `Code` and `Weight` wildcards, cut back to the
     * group, encompass those rows, so both counts are decidable. `NumberOfFilledFields` asks `Lot` itself and
     * stays undecidable.
     */
    @Test
    fun aFieldNamedConcretelyInEveryDeclaredRowIsEncompassedByTheGroupsWildcards() = assertFiredUnder(
        4,
        (1..4).map { row(LOT, 0, it, 0) } + listOf(CODE, WEIGHT).map(::everyRow),
        setOf("NFG_BATCHES", "NFG_SEALS"),
    )

    /** The same with the concrete rows on `Code`, a field of the `Seal` count's own group. */
    @Test
    fun aSealFieldNamedConcretelyInEveryDeclaredRowIsEncompassedAsWell() = assertFiredUnder(
        4,
        (1..4).map { row(CODE, 0, it, 0, 0) } + listOf(LOT, WEIGHT).map(::everyRow),
        setOf("NFG_BATCHES", "NFG_SEALS"),
    )

    /**
     * `Code` and `Weight` named concretely in both declared rows: every `Seal` is wholly covered, yet no
     * identifier wildcards the starred level, so the `Seal` count is undecidable. The `Lot` wildcard keeps the
     * batch count's identifiers settled.
     */
    @Test
    fun everyDeclaredRowNamedOnlyConcretelyLeavesTheExtentUnknown() = assertFiredUnder(
        2,
        listOf(everyRow(LOT)) + listOf(CODE, WEIGHT).flatMap { f -> (1..2).map { row(f, 0, it, 0, 0) } },
        setOf("NFG_BATCHES"),
    )

    /** The same with `Lot` named concretely too: now neither count's identifiers wildcard `Batches`. */
    @Test
    fun everyFieldNamedOnlyConcretelyLeavesBothExtentsUnknown() = assertFiredUnder(
        2,
        (1..2).flatMap { listOf(row(LOT, 0, it, 0), row(CODE, 0, it, 0, 0), row(WEIGHT, 0, it, 0, 0)) },
        setOf(),
    )

    /**
     * `Lot` named in the two held rows only: rows 3 and 4 are absent and not wholly covered, so the batch
     * count is undecidable although every held row is decided. The `Seal` count's fields stay wildcarded.
     */
    @Test
    fun anAbsentDeclaredRowThatIsNotWhollyCoveredLeavesTheCountUndecidable() = assertFiredUnder(
        4,
        listOf(row(LOT, 0, 1, 0), row(LOT, 0, 2, 0)) + listOf(CODE, WEIGHT).map(::everyRow),
        setOf("NFG_SEALS"),
    )

    /**
     * `Lot` named in rows 1 and 3: held row 2 is partly covered yet decided by its content, absent row 3 is
     * wholly covered, and absent row 4 is not, which alone leaves the batch count undecidable.
     */
    @Test
    fun anUncoveredAbsentRowIsNotAnsweredByAHeldRowOfTheSameCoverage() = assertFiredUnder(
        4,
        listOf(row(LOT, 0, 1, 0), row(LOT, 0, 3, 0)) + listOf(CODE, WEIGHT).map(::everyRow),
        setOf("NFG_SEALS"),
    )

    /**
     * `Seal` covered in row 1 only. The batch count still decides: row 2 is partly covered but holds admitted
     * content, which is all a held row needs. The `Seal` count's own identifiers stay concrete.
     */
    @Test
    fun aPartlyCoveredHeldRowCountsByItsAdmittedContent() = assertFiredUnder(
        2,
        listOf(everyRow(LOT), row(CODE, 0, 1, 0, 0), row(WEIGHT, 0, 1, 0, 0)),
        setOf("NFG_BATCHES"),
    )

    /** The coverage above moved to row 2: now the FIRST row is the partly covered one, and nothing decides. */
    @Test
    fun aPartlyCoveredFirstRowLeavesTheCountUndecidable() = assertFiredUnder(
        2,
        listOf(everyRow(LOT), row(CODE, 0, 2, 0, 0), row(WEIGHT, 0, 2, 0, 0)),
        setOf(),
    )

    /** A malformed `Weight` in row 1 leaves both counts undecidable, although the row holds admitted content. */
    @Test
    fun anErroneousFirstRowLeavesTheCountUndecidable() = assertFiredUnder(
        2,
        batchFields,
        setOf(),
        weights = "heavy" to "2",
        fullExpected = setOf("NFG_BATCHES", "NFG_SEALS", "NFG_FIXED", "NFG_MIXED"),
    )

    /** The malformed `Weight` moved to row 2: a later row counts by its admitted content, errors or not. */
    @Test
    fun anErroneousLaterRowCountsByItsAdmittedContent() = assertFiredUnder(
        2,
        batchFields,
        setOf("NFG_BATCHES", "NFG_SEALS"),
        weights = "1" to "heavy",
        fullExpected = setOf("NFG_BATCHES", "NFG_SEALS", "NFG_FIXED", "NFG_MIXED"),
    )

    /**
     * A fixed group is its own first instance: a malformed `Grade` beside an admitted `Text` leaves `Label`
     * uncounted, and with it the count over both fixed groups and the mixed count, whose starred `Batches`
     * operand alone is decided.
     */
    @Test
    fun anErroneousFixedGroupLeavesItsCountUndecidable() = assertFiredUnder(
        2,
        batchFields + fixedFields,
        setOf("NFG_BATCHES", "NFG_SEALS", "NFF_BATCHES"),
        grade = "heavy",
    )

    /**
     * The mixed count's other side: the fixed `Label` is decided and the starred `Batches` is not, because its
     * first row is only partly covered. One undecided operand leaves the whole list undecided.
     */
    @Test
    fun anUndecidedStarredOperandLeavesAMixedCountUndecidable() = assertFiredUnder(
        2,
        listOf(everyRow(LOT), row(CODE, 0, 2, 0, 0), row(WEIGHT, 0, 2, 0, 0)) + fixedFields,
        setOf("NFG_FIXED"),
    )

    /** The control for the row above: the same coverage with a well-formed `Grade` decides every carrier. */
    @Test
    fun aWhollyCoveredCleanDocumentDecidesEveryCarrier() =
        assertFiredUnder(2, batchFields + fixedFields, carriers.keys)

    /**
     * Asserts the carriers fired under full validation, then under [coverage] by dynamic Groovy, static Java
     * and the interpreter. Two batches each hold a `Lot`, a `Code` and a `Weight` from [weights]; the `Label`
     * holds a `Text` and [grade], and the `Stamp` a `Mark`.
     */
    private fun assertFiredUnder(
        capacity: Int,
        coverage: List<Selection>,
        expected: Set<String>,
        weights: Pair<String, String> = "1" to "2",
        grade: String = "3",
        fullExpected: Set<String> = carriers.keys,
    ) {
        val model = model(capacity)
        val definition = ModelLoader.load(model)
        val javaEngine = RuntimeLaws.kernelJavaEngine(model)
        fun document() = Both(RuntimeLaws.doc(), definition.newDocument()).group(ITEM, 1).field(TITLE, "crate", 1, 1)
            .group(BATCHES, 1, 1).field(LOT, "L1", 1, 1, 1).field(CODE, "S1", 1, 1, 1, 1).field(WEIGHT, weights.first, 1, 1, 1, 1)
            .group(BATCHES, 1, 2).field(LOT, "L2", 1, 2, 1).field(CODE, "S2", 1, 2, 1, 1).field(WEIGHT, weights.second, 1, 2, 1, 1)
            .group(LABEL, 1, 1).field(TEXT, "fragile", 1, 1, 1).field(GRADE, grade, 1, 1, 1)
            .group(STAMP, 1, 1).field(MARK, "checked", 1, 1, 1)
        val selections = coverage + everyRow(TITLE)
        val kRelevant = selections.map { Relevant.of(it.path, *it.reps.toIntArray()) }.toSet()
        val iRelevant = selections.map { RelevantEntity(it.path, it.reps) }.toSet()
        val d = document()
        val full = RuntimeLaws.messages(model, document().kDoc).map { it.code() }.filter { it in carriers }.toSortedSet()
        val dynamic = RuntimeLaws.messagesPart(model, d.kDoc, kRelevant).map { it.code() }.filter { it in carriers }.toSortedSet()
        val static = javaEngine.messagesPart(document().kDoc, kRelevant).map { it.code() }.filter { it in carriers }.toSortedSet()
        val interpreter = DmInterpreter(definition).validatePart(d.iDoc, iRelevant).messages.map { it.code }
            .filter { it in carriers }.toSortedSet()
        assertEquals(
            mapOf("full" to fullExpected.toSortedSet(), "dynamic" to expected.toSortedSet(),
                "static" to expected.toSortedSet(), "interpreter" to expected.toSortedSet()),
            mapOf("full" to full, "dynamic" to dynamic, "static" to static, "interpreter" to interpreter),
        )
    }
}
