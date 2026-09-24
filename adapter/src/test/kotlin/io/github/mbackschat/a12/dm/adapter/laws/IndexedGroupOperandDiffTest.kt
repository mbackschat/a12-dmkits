package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.interpreter.DmInterpreter
import io.github.mbackschat.a12.dm.interpreter.eval.Document
import io.github.mbackschat.a12.dm.interpreter.eval.newDocument
import io.github.mbackschat.a12.dm.interpreter.load.ModelLoader
import io.github.mbackschat.a12.dm.interpreter.model.ComputeOutcome
import io.github.mbackschat.a12.dm.interpreter.model.MsgType
import io.github.mbackschat.a12.dm.interpreter.model.RelevantEntity
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import java.util.Locale
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Differential + tri-check (groovy-dynamic == java-static == interpreter) for an index-selected GROUP operand,
 * `Packages For "P1"`, and for an index-selected FIELD in the same group-list quantifiers. Every row first asserts that the kernel admits its model, because several carriers here lie
 * outside the admission grid `validate.laws.SemanticIndexGroupOperandCarrierLawsTest` holds for the group-list
 * quantifiers.
 *
 * The shape is authorable through `Dsl.entityListPredicate` and the group-list factories, and DEV-API-GAPS DG68
 * would make it easier to write, so the evaluators are measured first: a wrong verdict here would be silent
 * (API-FINDINGS on DG33). Conditions are text, because the typed spelling is what DG68 is about to change.
 *
 * Each row compares the `errorPath|type` signature of the rule's messages, not a boolean, because a message of
 * the wrong polarity would otherwise read as agreement.
 */
class IndexedGroupOperandDiffTest {

    private companion object {
        const val REFERENCE = "/Order/Reference"
        const val WANTED = "/Order/Wanted"
        const val PACKAGES = "/Order/Packages"
        const val CODE = "/Order/Packages/Code"
        const val CARRIER = "/Order/Packages/Carrier"
        const val LENGTH = "/Order/Packages/Dims/Length"
        const val WIDTH = "/Order/Packages/Dims/Width"
        const val RULE_CODE = "IDX"

        /** `P1` filled throughout (Length 3, Width 4) and `P2` holding its key alone; no row is keyed `P3`. */
        val standard = listOf(Pkg("P1", "DHL", "3", "4"), Pkg("P2"))

        /** Two rows share the key `P1`, so the index names no single row. */
        val duplicated = listOf(Pkg("P1", "DHL"), Pkg("P1"), Pkg("P2", "UPS"))
    }

    private val model = DocumentModelDraft.named("parcels").locale("en_US")
        .rootGroup("Order") { g ->
            g.stringField("Reference") { }
            g.stringField("Wanted") { }
            g.numberField("Flag") { it.fractionDigits(0, 0) }
            g.group("Packages") { p ->
                p.repeatable(5).indexField("Code")
                    .stringField("Code") { }
                    .stringField("Carrier") { }
                    .group("Dims") { d ->
                        d.numberField("Length") { it.fractionDigits(0, 0) }
                            .numberField("Width") { it.fractionDigits(0, 0) }
                    }
            }
        }.build().toJson()

    /** One package row: its key, and the cells filled beside it. */
    private data class Pkg(val code: String?, val carrier: String? = null, val length: String? = null,
        val width: String? = null)

    /**
     * One grid row. [errorField] is where the rule attaches, because A12 re-anchors an error inside the indexed
     * group to the selected row. [guarded] conjoins `FieldFilled(errorField)`, an ordinary reference that makes
     * the rule iterate the error field's rows instead. [relevant] switches to partial validation over those
     * `path` to `reps` entries, `0` wildcarding a level. [declined] expects the interpreter to refuse the rule.
     */
    private data class Row(val condition: String, val fires: Boolean, val packages: List<Pkg> = standard,
        val wanted: String? = "P3", val errorField: String = REFERENCE, val guarded: Boolean = true,
        val relevant: Map<String, List<Int>>? = null, val declined: Boolean = false)

    private class Both(val kDoc: RuntimeLaws.Doc, val iDoc: Document) {
        fun group(path: String, vararg reps: Int): Both {
            kDoc.group(path, *reps); iDoc.group(path, *reps); return this
        }

        fun field(path: String, v: String, vararg reps: Int): Both {
            kDoc.field(path, v, *reps); iDoc.field(path, v, *reps); return this
        }
    }

    private fun build(b: Both, row: Row) {
        b.group("/Order", 1).field(REFERENCE, "ORD-1", 1, 1)
        row.wanted?.let { b.field(WANTED, it, 1, 1) }
        row.packages.forEachIndexed { i, p ->
            val r = i + 1
            b.group(PACKAGES, 1, r)
            p.code?.let { b.field(CODE, it, 1, r, 1) }
            p.carrier?.let { b.field(CARRIER, it, 1, r, 1) }
            p.length?.let { b.field(LENGTH, it, 1, r, 1, 1) }
            p.width?.let { b.field(WIDTH, it, 1, r, 1, 1) }
        }
    }

    private fun assertAdmitted(kModel: String) = assertEquals(emptyList<String>(),
        RuntimeLaws.consistencyMessages(kModel).filter { it.startsWith("ERROR") }, "the kernel admits the model")

    private fun signature(messages: List<RuntimeLaws.Msg>): String =
        messages.filter { it.code() == RULE_CODE }.map { "${it.field()}|${it.type().name}" }.sorted().toString()

    /** One row's three signatures, the interpreter's written as UNSUPPORTED when it declines the rule. */
    private fun verdicts(row: Row): String {
        val group = row.errorField.substringBeforeLast('/')
        val condition = if (row.guarded) "FieldFilled(${row.errorField}) And ${row.condition}" else row.condition
        val kModel = RuntimeLaws.withRule(model, group, "R", "../" + row.errorField.substringAfterLast('/'),
            RULE_CODE, condition)
        assertAdmitted(kModel)
        val def = ModelLoader.load(kModel)

        val dynamic = Both(RuntimeLaws.doc(), def.newDocument()).also { build(it, row) }
        val kernelRelevant = row.relevant?.mapTo(HashSet()) { (path, reps) ->
            RuntimeLaws.Relevant.of(path, *reps.toIntArray())
        }
        val groovy = signature(if (kernelRelevant == null) RuntimeLaws.messages(kModel, dynamic.kDoc)
            else RuntimeLaws.messagesPart(kModel, dynamic.kDoc, kernelRelevant))

        // the engines consume a document destructively, so the static arm gets its own instance
        val staticDoc = Both(RuntimeLaws.doc(), def.newDocument()).also { build(it, row) }.kDoc
        val engine = RuntimeLaws.kernelJavaEngine(kModel)
        val javaStatic = signature(if (kernelRelevant == null) engine.messagesModern(staticDoc, null, Locale.US)
            else engine.messagesPart(staticDoc, kernelRelevant))

        val interpreterRelevant = row.relevant?.mapTo(HashSet()) { (path, reps) -> RelevantEntity(path, reps) }
        val outcome = if (interpreterRelevant == null) DmInterpreter(def).validate(dynamic.iDoc)
            else DmInterpreter(def).validatePart(dynamic.iDoc, interpreterRelevant)
        val interpreter = if (outcome.unsupported.isNotEmpty()) "UNSUPPORTED"
            else outcome.messages.filter { it.code == RULE_CODE }
                .map { "${it.errorPath}|${if (it.type == MsgType.OMISSION) "OMISSION_ERROR" else "VALUE_ERROR"}" }
                .sorted().toString()
        return "fires=${groovy != "[]"} groovy=$groovy java=$javaStatic interpreter=$interpreter"
    }

    private fun assertGrid(rows: List<Row>) {
        val key = { r: Row ->
            "${r.condition} [${r.packages.joinToString { it.code ?: "-" }}; Wanted=${r.wanted}" +
                (if (r.errorField == REFERENCE) "" else "; error=${r.errorField}") +
                (if (r.guarded) "" else "; unguarded") +
                (r.relevant?.let { "; relevant=${it.keys}" } ?: "") + "]"
        }
        val actual = rows.associate { key(it) to verdicts(it) }
        // the kernel's own firing is the expectation; the tri-check then asks the other two engines to repeat it
        val expected = rows.associate { r ->
            val a = actual.getValue(key(r))
            val groovy = a.substringAfter("groovy=").substringBefore(" java=")
            key(r) to "fires=${r.fires} groovy=$groovy java=$groovy interpreter=${if (r.declined) "UNSUPPORTED" else groovy}"
        }
        assertEquals(expected, actual)
    }

    @Test
    fun aKeyedGroupInAGroupListQuantifierReadsTheSelectedRow() = assertGrid(listOf(
        Row("NoGroupFilled(/Order/Packages For \"P3\")", true),
        Row("NoGroupFilled(/Order/Packages For \"P1\")", false),
        Row("AtLeastOneGroupFilled(/Order/Packages For \"P3\", /Order/Packages For \"P2\")", true),
        Row("AllGroupsFilled(/Order/Packages For \"P1\", /Order/Packages For \"P3\")", false),
        Row("NotAllGroupsFilled(/Order/Packages For \"P1\", /Order/Packages For \"P3\")", true),
        Row("GroupsNotCollectivelyFilled(/Order/Packages For \"P1\", /Order/Packages For \"P3\")", true),
        Row("AllGroupsFilled(/Order/Packages For \"P1\", /Order/Reference)", true),
        // a keyed subgroup: P2's row exists but its Dims holds nothing
        Row("NoGroupFilled(/Order/Packages/Dims For \"P2\")", true),
        Row("NoGroupFilled(/Order/Packages/Dims For \"P1\")", false),
        // a field-valued key is read at run time, and an empty key selects no row
        Row("NoGroupFilled(/Order/Packages For /Order/Wanted)", false, wanted = "P2"),
        Row("NoGroupFilled(/Order/Packages For /Order/Wanted)", true, wanted = "P3"),
        Row("NoGroupFilled(/Order/Packages For /Order/Wanted)", true, wanted = null),
        // a duplicated key names no single row
        Row("NoGroupFilled(/Order/Packages For \"P1\")", false, duplicated),
        Row("AtLeastOneGroupFilled(/Order/Packages For \"P1\")", false, duplicated),
        Row("AtLeastOneGroupFilled(/Order/Packages For \"P2\")", true, duplicated)))

    /**
     * An index-selected FIELD is a group-list operand too, read like `FieldFilled(Carrier For key)`: the selected
     * row's cell, an empty one for a key no row carries, and undecided for a key two rows carry.
     */
    @Test
    fun aKeyedFieldInAGroupListQuantifierReadsTheSelectedCell() = assertGrid(listOf(
        Row("AtLeastOneGroupFilled(/Order/Packages/Carrier For \"P1\", /Order/Reference)", true),
        Row("AtLeastOneGroupFilled(/Order/Packages/Carrier For \"P2\", /Order/Packages/Carrier For \"P3\")", false),
        Row("NoGroupFilled(/Order/Packages/Carrier For \"P2\", /Order/Packages/Carrier For \"P3\")", true),
        Row("NoGroupFilled(/Order/Packages/Carrier For \"P1\", /Order/Packages/Carrier For \"P3\")", false),
        Row("AllGroupsFilled(/Order/Packages/Carrier For \"P1\", /Order/Reference)", true),
        Row("AllGroupsFilled(/Order/Packages/Carrier For \"P2\", /Order/Reference)", false),
        Row("NotAllGroupsFilled(/Order/Packages/Carrier For \"P2\", /Order/Reference)", true),
        Row("GroupsNotCollectivelyFilled(/Order/Packages/Carrier For \"P1\", /Order/Packages/Carrier For \"P2\")", true),
        Row("AtLeastOneGroupFilled(/Order/Packages For \"P3\", /Order/Packages/Carrier For \"P1\")", true),
        Row("NoGroupFilled(/Order/Packages/Carrier For /Order/Wanted, /Order/Packages/Carrier For \"P3\")", true,
            wanted = "P2"),
        Row("NoGroupFilled(/Order/Packages/Carrier For /Order/Wanted, /Order/Packages/Carrier For \"P3\")", false,
            wanted = "P1"),
        Row("AtLeastOneGroupFilled(/Order/Packages/Carrier For \"P1\", /Order/Packages/Carrier For \"P3\")", false,
            duplicated),
        Row("NoGroupFilled(/Order/Packages/Carrier For \"P1\", /Order/Packages/Carrier For \"P3\")", false, duplicated),
        Row("AtLeastOneGroupFilled(/Order/Packages/Carrier For \"P2\", /Order/Packages/Carrier For \"P1\")", true,
            duplicated),
        Row("AtLeastOneGroupFilled(/Order/Packages/Carrier For \"P1\", /Order/Packages/Carrier For \"P3\")", true,
            relevant = mapOf("/Order" to listOf(0))),
        Row("AtLeastOneGroupFilled(/Order/Packages/Carrier For \"P1\", /Order/Packages/Carrier For \"P3\")", false,
            relevant = mapOf(REFERENCE to listOf(0, 0), WANTED to listOf(0, 0))),
        Row("AtLeastOneGroupFilled(/Order/Packages/Carrier For \"P1\", /Order/Reference)", true, errorField = CARRIER,
            guarded = false)))

    @Test
    fun aKeyedGroupInAPresenceLeafReadsTheSelectedRow() = assertGrid(listOf(
        Row("GroupFilled(/Order/Packages For \"P2\")", true),
        Row("GroupFilled(/Order/Packages For \"P3\")", false),
        Row("GroupNotFilled(/Order/Packages For \"P3\")", true),
        Row("GroupNotFilled(/Order/Packages For \"P1\")", false),
        Row("GroupFilled(/Order/Packages/Dims For \"P2\")", false),
        Row("GroupNotFilled(/Order/Packages/Dims For \"P2\")", true),
        Row("GroupNotFilled(/Order/Packages For /Order/Wanted)", true, wanted = null),
        Row("GroupFilled(/Order/Packages For \"P1\")", false, duplicated),
        Row("GroupNotFilled(/Order/Packages For \"P1\")", false, duplicated),
        Row("GroupNotFilled(/Order/Packages/Dims For \"P2\")", true, errorField = CARRIER)))

    /**
     * A malformed `Length` is all P2's `Dims` holds, so the selected row's presence is its formal state, not its
     * raw content: no presence condition over it is decided, while a deciding sibling operand still fires.
     */
    @Test
    fun aMalformedCellAloneInTheSelectedRowLeavesItsPresenceUndecided() {
        val malformed = listOf(Pkg("P1", "DHL", "3", "4"), Pkg("P2", length = "abc"))
        assertGrid(listOf(
            Row("GroupFilled(/Order/Packages/Dims For \"P2\")", false, malformed),
            Row("GroupNotFilled(/Order/Packages/Dims For \"P2\")", false, malformed),
            Row("NoGroupFilled(/Order/Packages/Dims For \"P2\")", false, malformed),
            Row("AtLeastOneGroupFilled(/Order/Packages/Dims For \"P2\", /Order/Reference)", true, malformed)))
    }

    /**
     * An error field inside the indexed group. Guarded, `FieldFilled(Carrier)` makes the rule iterate the package
     * rows, so the question is whether the keyed operand moves the message off the iterated row. Unguarded, the
     * keyed operand is the rule's only reference into the group, so A12 attaches the message to the row the key
     * selects, or to the unbound row when no row carries the key.
     */
    @Test
    fun aKeyedGroupListErrorAttachesWhereTheKernelAttachesIt() = assertGrid(listOf(
        Row("NoGroupFilled(/Order/Packages/Dims For \"P2\")", true, errorField = CARRIER),
        Row("NoGroupFilled(/Order/Packages/Dims For \"P3\")", true, errorField = CARRIER),
        Row("AtLeastOneGroupFilled(/Order/Packages For \"P2\")", true, errorField = CARRIER),
        Row("NoGroupFilled(/Order/Packages For \"P1\")", false, duplicated, errorField = CARRIER),
        Row("NoGroupFilled(/Order/Packages For \"P3\")", true, errorField = CARRIER, guarded = false),
        Row("GroupFilled(/Order/Packages For \"P2\")", true, errorField = CARRIER, guarded = false),
        Row("AtLeastOneGroupFilled(/Order/Packages For \"P2\")", true, duplicated, errorField = CARRIER,
            guarded = false),
        Row("GroupFilled(RuleGroup For \"P2\")", true, errorField = CARRIER, guarded = false),
        Row("NoGroupFilled(/Order/Packages/Dims For \"P2\")", true, errorField = LENGTH, guarded = false)))

    /**
     * `FirstFilledValue` walks past a key no row carries as it walks past an empty row: both are fillable, which
     * A12 types OMISSION on the value the walk goes on to find.
     */
    @Test
    fun firstFilledValueWalksPastAKeyNoRowCarriesAsAFillableEmpty() = assertGrid(listOf(
        Row("FirstFilledValue(/Order/Packages/Dims For \"P3\", /Order/Packages/Dims For \"P1\") == 3", true),
        Row("FirstFilledValue(/Order/Packages/Dims For \"P2\", /Order/Packages/Dims For \"P1\") == 3", true)))

    /**
     * The field-fill quantifiers and the aggregates are kernel-admitted over a keyed group, and the interpreter
     * refuses them rather than answer (INTERPRETER-GAPS IG155). Each row keeps the kernel's own verdict, so the
     * row that stops declining has its expectation already measured.
     */
    @Test
    fun aKeyedGroupInAFieldFillQuantifierOrAnAggregateIsDeclined() = assertGrid(listOf(
        Row("NumberOfFilledFields(/Order/Packages For \"P1\") == 4", true, declined = true),
        Row("NumberOfFilledFields(/Order/Packages For \"P2\") == 1", true, declined = true),
        Row("Sum(/Order/Packages/Dims For \"P1\") == 7", true, declined = true),
        Row("Sum(/Order/Packages/Dims For \"P2\") == 0", true, declined = true),
        Row("NoFieldFilled(/Order/Packages For \"P3\")", true, declined = true),
        Row("AllFieldsFilled(/Order/Packages For \"P1\", /Order/Reference)", true, declined = true),
        Row("AllFieldsFilled(/Order/Packages For \"P2\", /Order/Reference)", false, declined = true)))

    /** Partial validation: the selected row's relevance, not the whole document's, decides the operand. */
    @Test
    fun aKeyedGroupListOperandFollowsPartialRelevance() {
        val all = mapOf("/Order" to listOf(0))
        val referenceOnly = mapOf(REFERENCE to listOf(0, 0), WANTED to listOf(0, 0))
        val secondRow = referenceOnly + mapOf(PACKAGES to listOf(0, 2))
        assertGrid(listOf(
            Row("NoGroupFilled(/Order/Packages For \"P3\")", true, relevant = all),
            Row("AtLeastOneGroupFilled(/Order/Packages For \"P1\")", true, relevant = all),
            // a clean no-match is not a definite absence while the key column is out of relevance
            Row("NoGroupFilled(/Order/Packages For \"P3\")", false, relevant = referenceOnly),
            Row("AtLeastOneGroupFilled(/Order/Packages For \"P1\")", false, relevant = referenceOnly),
            Row("AtLeastOneGroupFilled(/Order/Packages For \"P2\")", true, relevant = secondRow),
            Row("NoGroupFilled(/Order/Packages For \"P1\")", false, relevant = secondRow),
            Row("GroupNotFilled(/Order/Packages For \"P3\")", false, relevant = referenceOnly),
            Row("GroupFilled(/Order/Packages For \"P2\")", true, relevant = secondRow),
            // a filled row outside the coverage is not filled for the rule
            Row("GroupFilled(/Order/Packages For \"P1\")", false, relevant = secondRow),
            Row("GroupFilled(/Order/Packages/Dims For \"P1\")", false,
                relevant = referenceOnly + mapOf(CODE to listOf(0, 0, 0), CARRIER to listOf(0, 0, 0)))))
    }

    /**
     * One tiered computation per row, from a seeded 9: `Flag` becomes 1 when [condition] holds, else 2, because
     * the second alternative's `FieldFilled(Reference)` holds on every document. So a false [condition] stores 2,
     * and only a read that poisons leaves `Flag` cleared.
     */
    private fun computed(condition: String, packages: List<Pkg>, wanted: String?): String {
        val kModel = RuntimeLaws.withTieredComputation(model, "/Order", "FlagComp", "../Flag", null,
            listOf(RuntimeLaws.CompAlt(condition, "1"), RuntimeLaws.CompAlt("FieldFilled($REFERENCE)", "2")))
        assertAdmitted(kModel)
        val def = ModelLoader.load(kModel)
        val row = Row(condition, false, packages, wanted)
        val seeded = { b: Both -> build(b, row); b.field("/Order/Flag", "9", 1, 1) }
        fun kernel(c: RuntimeLaws.Computed) =
            if (c.cleared()) "CLEARED" else if (c.errored()) "ERRORED" else "VALUE|${c.value().orElse("")}"
        val dynamic = Both(RuntimeLaws.doc(), def.newDocument()).also { seeded(it) }
        val groovy = RuntimeLaws.compute(kModel, dynamic.kDoc).joinToString(";") { kernel(it) }
        val staticDoc = Both(RuntimeLaws.doc(), def.newDocument()).also { seeded(it) }.kDoc
        val engine = RuntimeLaws.kernelJavaEngine(kModel)
        val javaStatic = engine.computeOutcomesSorted(engine.instance(staticDoc)).joinToString(";") {
            when (val v = it.substringAfter('|')) { "ERR" -> "ERRORED"; "CLEARED" -> "CLEARED"; else -> "VALUE|$v" }
        }
        val interpreter = runCatching { DmInterpreter(def).compute(dynamic.iDoc) }.fold(
            onSuccess = { results ->
                results.joinToString(";") {
                    when (it.outcome) {
                        ComputeOutcome.CLEARED -> "CLEARED"
                        ComputeOutcome.ERRORED -> "ERRORED"
                        ComputeOutcome.VALUE -> "VALUE|${it.value}"
                    }
                }
            },
            onFailure = { "THREW ${it.message}" },
        )
        return "groovy=$groovy java=$javaStatic interpreter=$interpreter"
    }

    /**
     * Computation reads the key column differently from validation. The duplicated-key rows are the
     * discriminator: `P2` itself is unique, so validation resolves it and would answer TRUE, while computation
     * poisons on any invalid key cell, even for a key no row carries. The last two rows are the read order: an
     * operand that decides first spares the keyed read.
     */
    @Test
    fun aKeyedGroupListOperandComputesLikeTheKernel() {
        val rows = listOf(
            Triple("NoGroupFilled(/Order/Packages For \"P3\")", standard, "P3") to "VALUE|1",
            Triple("NoGroupFilled(/Order/Packages For \"P1\")", standard, "P3") to "VALUE|2",
            Triple("AtLeastOneGroupFilled(/Order/Packages/Dims For \"P1\")", standard, "P3") to "VALUE|1",
            Triple("AtLeastOneGroupFilled(/Order/Packages/Dims For \"P2\")", standard, "P3") to "VALUE|2",
            Triple("NoGroupFilled(/Order/Packages For /Order/Wanted)", standard, null) to "VALUE|1",
            Triple("GroupNotFilled(/Order/Packages For \"P3\")", standard, "P3") to "VALUE|1",
            Triple("AtLeastOneGroupFilled(/Order/Packages For \"P2\")", duplicated, "P3") to "CLEARED",
            Triple("NoGroupFilled(/Order/Packages For \"P3\")", duplicated, "P3") to "CLEARED",
            Triple("GroupFilled(/Order/Packages For \"P2\")", duplicated, "P3") to "CLEARED",
            Triple("AtLeastOneGroupFilled($REFERENCE, /Order/Packages For \"P2\")", duplicated, "P3") to "VALUE|1",
            Triple("AtLeastOneGroupFilled(/Order/Packages For \"P2\", $REFERENCE)", duplicated, "P3") to "CLEARED",
            Triple("AtLeastOneGroupFilled($CARRIER For \"P1\", $REFERENCE)", standard, "P3") to "VALUE|1",
            Triple("NoGroupFilled($CARRIER For \"P2\", $CARRIER For \"P3\")", standard, "P3") to "VALUE|1",
            Triple("NoGroupFilled($CARRIER For \"P1\", $CARRIER For \"P3\")", standard, "P3") to "VALUE|2",
            Triple("AtLeastOneGroupFilled($CARRIER For \"P2\", $REFERENCE)", duplicated, "P3") to "CLEARED",
            Triple("AtLeastOneGroupFilled($REFERENCE, $CARRIER For \"P2\")", duplicated, "P3") to "VALUE|1")
        val key = { c: String, p: List<Pkg>, w: String? -> "$c [${p.joinToString { it.code ?: "-" }}; Wanted=$w]" }
        val actual = rows.associate { (r, _) -> key(r.first, r.second, r.third) to computed(r.first, r.second, r.third) }
        val expected = rows.associate { (r, outcome) ->
            key(r.first, r.second, r.third) to "groovy=$outcome java=$outcome interpreter=$outcome"
        }
        assertEquals(expected, actual)
    }
}
