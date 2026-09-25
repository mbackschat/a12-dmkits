package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.interpreter.DmInterpreter
import io.github.mbackschat.a12.dm.interpreter.eval.newDocument
import io.github.mbackschat.a12.dm.interpreter.load.ModelLoader
import io.github.mbackschat.a12.dm.interpreter.model.ComputeOutcome
import io.github.mbackschat.a12.dm.interpreter.model.MsgType
import io.github.mbackschat.a12.dm.interpreter.model.RelevantEntity
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import java.util.Locale
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Differential + tri-check (groovy-dynamic == java-static == interpreter) for a keyed GROUP operand,
 * `Packages For "P1"`, where a field-fill quantifier or a field-list aggregate reads the cells inside it
 * (INTERPRETER-FINDINGS IF340, which closed IG155's remainder). The group-presence consumers of the same operand
 * are `IndexedGroupOperandDiffTest`'s.
 *
 * The fixture's keyed row holds a repeatable `Items` level, because that is the one place two readings of the
 * operand part: the fields of the selected row with every repeatable level below it free, or each contained
 * field carrying the key as a field of its own. The non-repeatable `Dims` cells cannot tell them apart.
 *
 * Every row first asserts that the kernel admits its model, and compares the `errorPath|type` signature rather
 * than a boolean, so a message of the wrong polarity cannot read as agreement.
 */
class KeyedGroupFieldListDiffTest {

    private companion object {
        const val REFERENCE = "/Order/Reference"
        const val WANTED = "/Order/Wanted"
        const val PACKAGES = "/Order/Packages"
        const val CODE = "/Order/Packages/Code"
        const val CARRIER = "/Order/Packages/Carrier"
        const val LENGTH = "/Order/Packages/Dims/Length"
        const val WIDTH = "/Order/Packages/Dims/Width"
        const val SKU = "/Order/Packages/Items/Sku"
        const val WEIGHT = "/Order/Packages/Items/Weight"
        const val RULE_CODE = "KGF"

        /** `P1` filled throughout with two of its three item rows, `P2` holding its key alone; no row is `P3`. */
        val standard = listOf(Pkg("P1", "DHL", "3", "4", listOf("S1" to "2", "S2" to "5")), Pkg("P2"))

        /** Two rows share the key `P1`, so the index names no single row. */
        val duplicated = listOf(Pkg("P1", "DHL"), Pkg("P1"), Pkg("P2", "UPS"))

        /** No row fills a `Width`, so a starred `Width` operand contributes no filled cell. */
        val noWidths = listOf(Pkg("P1", "DHL"), Pkg("P2"))

        /** `P2`'s only content beside its key is a malformed `Length`. */
        val malformed = listOf(Pkg("P1", "DHL", "3", "4"), Pkg("P2", length = "abc"))
    }

    private val model = DocumentModelDraft.named("keyed-parcels").locale("en_US")
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
                    .group("Items") { i ->
                        i.repeatable(3)
                            .stringField("Sku") { }
                            .numberField("Weight") { it.fractionDigits(0, 0) }
                    }
            }
        }.build().toJson()

    /** One package row: its key, the cells beside it, and its item rows as `(Sku, Weight)`. */
    private data class Pkg(val code: String?, val carrier: String? = null, val length: String? = null,
        val width: String? = null, val items: List<Pair<String, String>> = emptyList())

    /**
     * One grid row. [errorField] is where the rule attaches; [guarded] conjoins `FieldFilled(errorField)`, an
     * ordinary reference that makes the rule iterate the error field's rows. [relevant] switches to partial
     * validation over those `path` to `reps` entries, `0` wildcarding a level.
     */
    private data class Row(val condition: String, val fires: Boolean, val packages: List<Pkg> = standard,
        val wanted: String? = "P3", val errorField: String = REFERENCE, val guarded: Boolean = true,
        val relevant: Map<String, List<Int>>? = null)

    private fun build(b: Both, packages: List<Pkg>, wanted: String?) {
        b.group("/Order", 1).field(REFERENCE, "ORD-1", 1, 1)
        wanted?.let { b.field(WANTED, it, 1, 1) }
        packages.forEachIndexed { i, p ->
            val r = i + 1
            b.group(PACKAGES, 1, r)
            p.code?.let { b.field(CODE, it, 1, r, 1) }
            p.carrier?.let { b.field(CARRIER, it, 1, r, 1) }
            p.length?.let { b.field(LENGTH, it, 1, r, 1, 1) }
            p.width?.let { b.field(WIDTH, it, 1, r, 1, 1) }
            p.items.forEachIndexed { j, (sku, weight) ->
                b.field(SKU, sku, 1, r, j + 1, 1)
                b.field(WEIGHT, weight, 1, r, j + 1, 1)
            }
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

        val dynamic = Both(RuntimeLaws.doc(), def.newDocument()).also { build(it, row.packages, row.wanted) }
        val kernelRelevant = row.relevant?.mapTo(HashSet()) { (path, reps) ->
            RuntimeLaws.Relevant.of(path, *reps.toIntArray())
        }
        val groovy = signature(if (kernelRelevant == null) RuntimeLaws.messages(kModel, dynamic.kDoc)
            else RuntimeLaws.messagesPart(kModel, dynamic.kDoc, kernelRelevant))

        // the engines consume a document destructively, so the static arm gets its own instance
        val staticDoc = Both(RuntimeLaws.doc(), def.newDocument()).also { build(it, row.packages, row.wanted) }.kDoc
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
            "${r.condition} [${r.packages.joinToString { "${it.code ?: "-"}/${it.items.size}" }}; Wanted=${r.wanted}" +
                (if (r.errorField == REFERENCE) "" else "; error=${r.errorField}") +
                (if (r.guarded) "" else "; unguarded") +
                (r.relevant?.let { "; relevant=${it.keys}" } ?: "") + "]"
        }
        val actual = rows.associate { key(it) to verdicts(it) }
        // the kernel's own firing is the expectation; the tri-check then asks the other two engines to repeat it
        val expected = rows.associate { r ->
            val groovy = actual.getValue(key(r)).substringAfter("groovy=").substringBefore(" java=")
            key(r) to "fires=${r.fires} groovy=$groovy java=$groovy interpreter=$groovy"
        }
        assertEquals(expected, actual)
    }

    /**
     * `NumberOfFilledFields` counts the selected row's filled cells and the value aggregates fold them. `8` is the
     * discriminator: it counts both of `P1`'s item rows, which only a reading with `Items` free reaches. A value
     * aggregate cannot carry that row, because `Sum(Packages/Items For "P1")` is refused `MVK_NO_WILDCARD` and the
     * starred spelling is the key composed with a star, which stays declined (INTERPRETER-GAPS IG155).
     */
    @Test
    fun anAggregateReadsTheCellsOfTheSelectedRow() = assertGrid(listOf(
        Row("NumberOfFilledFields(/Order/Packages For \"P1\") == 8", true),
        Row("NumberOfFilledFields(/Order/Packages For \"P2\") == 1", true),
        Row("NumberOfFilledFields(/Order/Packages For \"P3\") == 0", true),
        Row("NumberOfFilledFields(/Order/Packages/Dims For \"P1\") == 2", true),
        Row("Sum(/Order/Packages/Dims For \"P1\") == 7", true),
        Row("Sum(/Order/Packages/Dims For \"P2\") == 0", true),
        Row("Sum(/Order/Packages/Dims For \"P3\") == 0", true),
        Row("MaxValue(/Order/Packages/Dims For \"P1\") == 4", true),
        Row("MinValue(/Order/Packages/Dims For \"P1\") == 3", true),
        Row("NumberOfFilledFields(/Order/Packages For \"P1\") > 0", false, duplicated),
        Row("Sum(/Order/Packages/Dims For \"P1\") == 0", false, duplicated),
        // a keyed FIELD in the same positions, the class's sibling
        Row("Sum($WIDTH For \"P1\", $WIDTH For \"P2\") == 4", true),
        Row("NumberOfFilledFields($CARRIER For \"P1\", $CARRIER For \"P2\") == 1", true)))

    /** The row the aggregate grid cannot carry: a repeatable level below the key must be starred, as it must be
     *  without a key, so the kernel refuses the model before any engine could read it. */
    @Test
    fun aRepeatableLevelBelowTheKeyMustBeStarred() {
        val kModel = RuntimeLaws.withRule(model, "/Order", "R", "../Reference", RULE_CODE,
            "FieldFilled($REFERENCE) And Sum(/Order/Packages/Items For \"P1\") == 7")
        val errors = RuntimeLaws.consistencyMessages(kModel).filter { it.startsWith("ERROR") }
        assertTrue(errors.any { Regex("\\bMVK_NO_WILDCARD\\b").containsMatchIn(it) }, errors.toString())
    }

    @Test
    fun aFieldFillQuantifierReadsTheCellsOfTheSelectedRow() = assertGrid(listOf(
        Row("NoFieldFilled(/Order/Packages For \"P3\")", true),
        Row("NoFieldFilled(/Order/Packages For \"P1\")", false),
        Row("AtLeastOneFieldFilled(/Order/Packages/Dims For \"P2\")", false),
        Row("AtLeastOneFieldFilled(/Order/Packages For \"P2\")", true),
        Row("AllFieldsFilled(/Order/Packages/Dims For \"P1\")", true),
        // the declared range: P1 fills two of its three item rows
        Row("AllFieldsFilled(/Order/Packages For \"P1\")", false),
        Row("AllFieldsFilled(/Order/Packages For \"P1\")", true,
            listOf(Pkg("P1", "DHL", "3", "4", listOf("S1" to "2", "S2" to "5", "S3" to "1")))),
        Row("NotAllFieldsFilled(/Order/Packages For \"P1\")", true),
        Row("NotAllFieldsFilled(/Order/Packages/Dims For \"P1\")", false),
        Row("MoreThanOneFieldFilled(/Order/Packages For \"P2\")", false),
        Row("NotExactlyOneFieldFilled(/Order/Packages For \"P2\")", false),
        Row("FieldsNotCollectivelyFilled(/Order/Packages For \"P2\")", true),
        Row("NoFieldFilled(/Order/Packages For \"P1\")", false, duplicated),
        Row("AtLeastOneFieldFilled(/Order/Packages For \"P1\")", false, duplicated),
        Row("AtLeastOneFieldFilled(/Order/Packages For \"P1\", $REFERENCE)", true, duplicated),
        Row("NoFieldFilled(/Order/Packages For $WANTED)", true, wanted = "P3"),
        Row("NoFieldFilled(/Order/Packages For $WANTED)", false, wanted = "P2"),
        Row("NoFieldFilled(/Order/Packages For $WANTED)", true, wanted = null),
        // beside a starred operand, which takes the list onto the group-scope path
        Row("AtLeastOneFieldFilled(/Order/Packages For \"P3\", /Order/Packages*/Carrier)", true),
        Row("AtLeastOneFieldFilled($CARRIER For \"P3\", /Order/Packages*/Carrier)", true),
        // every Width is empty, so the keyed cell alone decides: P2's carrier is empty and P1's is not
        Row("NoFieldFilled($CARRIER For \"P2\", /Order/Packages*/Dims/Width)", true, noWidths),
        Row("NoFieldFilled($CARRIER For \"P1\", /Order/Packages*/Dims/Width)", false, noWidths)))

    @Test
    fun aMalformedCellInTheSelectedRowIsReadAsTheKernelReadsIt() = assertGrid(listOf(
        Row("NoFieldFilled(/Order/Packages/Dims For \"P2\")", false, malformed),
        Row("AtLeastOneFieldFilled(/Order/Packages/Dims For \"P2\", $REFERENCE)", true, malformed),
        Row("NumberOfFilledFields(/Order/Packages/Dims For \"P2\") == 0", false, malformed)))

    @Test
    fun aKeyedFieldListOperandFollowsPartialRelevanceAndAttachesToTheSelectedRow() {
        val referenceOnly = mapOf(REFERENCE to listOf(0, 0), WANTED to listOf(0, 0))
        assertGrid(listOf(
            Row("NoFieldFilled(/Order/Packages For \"P3\")", true, relevant = mapOf("/Order" to listOf(0))),
            Row("NoFieldFilled(/Order/Packages For \"P3\")", false, relevant = referenceOnly),
            Row("AtLeastOneFieldFilled(/Order/Packages For \"P1\")", false,
                relevant = referenceOnly + mapOf(PACKAGES to listOf(0, 2))),
            Row("NumberOfFilledFields(/Order/Packages For \"P2\") == 1", true, errorField = CARRIER, guarded = false),
            Row("NoFieldFilled(/Order/Packages For \"P3\")", true, errorField = CARRIER, guarded = false)))
    }

    /**
     * One tiered computation per row, from a seeded 9: `Flag` becomes 1 when [condition] holds, else 2, because
     * the second alternative's `FieldFilled(Reference)` holds on every document. So a false [condition] stores 2,
     * and only a read that poisons leaves `Flag` cleared.
     */
    private fun computed(condition: String, packages: List<Pkg>): String {
        val kModel = RuntimeLaws.withTieredComputation(model, "/Order", "FlagComp", "../Flag", null,
            listOf(RuntimeLaws.CompAlt(condition, "1"), RuntimeLaws.CompAlt("FieldFilled($REFERENCE)", "2")))
        assertAdmitted(kModel)
        val def = ModelLoader.load(kModel)
        val seeded = { b: Both -> build(b, packages, "P3"); b.field("/Order/Flag", "9", 1, 1) }
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
     * Computation scans the expanded cells in declaration order and stops at the deciding one, so a malformed
     * `Length` after `P2`'s filled key is never read by `AtLeastOneFieldFilled`, while `NoFieldFilled` must read
     * it. A duplicated key poisons the read unless an operand before it decides.
     */
    @Test
    fun aKeyedFieldListOperandComputesLikeTheKernel() {
        val rows = listOf(
            ("AtLeastOneFieldFilled(/Order/Packages For \"P2\")" to standard) to "VALUE|1",
            ("NoFieldFilled(/Order/Packages For \"P3\")" to standard) to "VALUE|1",
            ("AllFieldsFilled(/Order/Packages/Dims For \"P2\")" to standard) to "VALUE|2",
            ("NumberOfFilledFields(/Order/Packages For \"P1\") == 8" to standard) to "VALUE|1",
            ("Sum(/Order/Packages/Dims For \"P1\") == 7" to standard) to "VALUE|1",
            ("AtLeastOneFieldFilled(/Order/Packages For \"P2\")" to duplicated) to "CLEARED",
            ("AtLeastOneFieldFilled($REFERENCE, /Order/Packages For \"P2\")" to duplicated) to "VALUE|1",
            ("NoFieldFilled(/Order/Packages/Dims For \"P2\")" to malformed) to "CLEARED",
            ("AtLeastOneFieldFilled(/Order/Packages For \"P2\")" to malformed) to "VALUE|1",
            ("Sum(/Order/Packages/Dims For \"P2\") == 0" to malformed) to "CLEARED")
        val key = { c: String, p: List<Pkg> -> "$c [${p.joinToString { "${it.code ?: "-"}/${it.length}" }}]" }
        val actual = rows.associate { (r, _) -> key(r.first, r.second) to computed(r.first, r.second) }
        val expected = rows.associate { (r, outcome) ->
            key(r.first, r.second) to "groovy=$outcome java=$outcome interpreter=$outcome"
        }
        assertEquals(expected, actual)
    }
}
