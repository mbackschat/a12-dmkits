package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.adapter.laws.RuntimeLaws.Relevant
import io.github.mbackschat.a12.dm.interpreter.DmInterpreter
import io.github.mbackschat.a12.dm.interpreter.eval.newDocument
import io.github.mbackschat.a12.dm.interpreter.load.ModelLoader
import io.github.mbackschat.a12.dm.interpreter.model.ModelDef
import io.github.mbackschat.a12.dm.interpreter.model.RelevantEntity
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl
import io.github.mbackschat.a12.dm.rulekit.dsl.Severity
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Does the starred VALUE-LIST extent gate consume relevance the same way the all-rows AGGREGATE gate does?
 * [IF262] established the aggregate gate as universal over a reduced identifier set and the interpreter applied
 * the same predicate to both call sites on the strength of shared implementation. This measures the two side by
 * side on one model and one relevant set at a time, which is the only thing that can settle it.
 */
class PartialExtentGateShapeDiffTest {

    private val json = DocumentModelDraft.named("catalog").locale("en_US")
        .rootGroup("Order") { g ->
            g.stringField("Ack") { }
                .group("Items") { it.repeatable(50).stringField("Tag") { }.numberField("Qty") { } }
        }.build().toJson()

    // Model text, loaded interpreter model, and the static-Java engine are built ONCE per condition and shared
    // across the seven relevant-set shapes. Rebuilding them per case is what the CLAUDE.md rule about new tests
    // perturbing timing floors in the same task is about: the codegen is the cost, not the seven comparisons.
    private val engines = mutableMapOf<String, RuntimeLaws.JavaKernelEngine>()
    private val models = mutableMapOf<String, String>()
    private val defs = mutableMapOf<String, ModelDef>()

    private val countModel = run {
        val model = BoundModel.ofJson(json)
        val order = model.group(ORDER)
        val ack = model.str(ACK)
        val quantities = model.flatten("$ITEMS*/Qty")
        val rules = listOf(
            model.rule(order).name(FILLED_FIELDS).errorField(ack)
                .`when`(ack.filled().and(Dsl.numberOfFilledFields(quantities).gt(0)))
                .code(FILLED_FIELDS).severity(Severity.ERROR).message(Dsl.en("filled fields")).build(),
            model.rule(order).name(FILLED_GROUPS).errorField(ack)
                .`when`(ack.filled().and(Dsl.numberOfFilledGroups(model.group(ITEMS)).gt(0)))
                .code(FILLED_GROUPS).severity(Severity.ERROR).message(Dsl.en("filled groups")).build(),
            model.rule(order).name(VALUE_COUNT).errorField(ack)
                .`when`(ack.filled().and(Dsl.numberOfValueInFields(10L, quantities).gt(0)))
                .code(VALUE_COUNT).severity(Severity.ERROR).message(Dsl.en("value count")).build(),
        )
        rules.fold(json) { modelJson, rule -> TypedRules.inject(modelJson, rule) }
    }
    private val countDef = ModelLoader.load(countModel)
    private val countEngine by lazy { RuntimeLaws.kernelJavaEngine(countModel) }

    @Test
    fun theValueListAndAggregateExtentGatesReadTheSameRelevantSet() {
        val selections = listOf(
            "field wildcard alone" to (
                listOf(field(TAG, 1, 0, 1)) to listOf(field(QTY, 1, 0, 1))),
            "field wildcard + concrete group rows" to (
                listOf(field(TAG, 1, 0, 1)) + concreteRows to listOf(field(QTY, 1, 0, 1)) + concreteRows),
            "field wildcard + a concrete identifier for the SAME field" to (
                listOf(field(TAG, 1, 0, 1), field(TAG, 1, 1, 1)) to
                    listOf(field(QTY, 1, 0, 1), field(QTY, 1, 1, 1))),
            "concrete field identifier + wildcard group" to (
                listOf(field(TAG, 1, 1, 1), Sel(ITEMS, listOf(0, 0))) to
                    listOf(field(QTY, 1, 1, 1), Sel(ITEMS, listOf(0, 0)))),
            "all-semantic field wildcard + concrete group rows" to (
                listOf(field(TAG, 0, 0, 0)) + concreteRows to listOf(field(QTY, 0, 0, 0)) + concreteRows),
            "field wildcard + physical-root concrete group rows" to (
                listOf(field(TAG, 1, 0, 1)) + physicalRows to listOf(field(QTY, 1, 0, 1)) + physicalRows),
            "concrete group rows only" to (concreteRows to concreteRows),
        )

        val readings = selections.map { (label, sets) ->
            val (forValueList, forAggregate) = sets
            "$label | value-list ${verdicts(VALUE_LIST, forValueList)}" +
                " | aggregate ${verdicts(AGGREGATE, forAggregate)}"
        }

        assertEquals(
            listOf(
                "field wildcard alone | value-list $FIRES | aggregate $FIRES",
                "field wildcard + concrete group rows | value-list $FIRES | aggregate $SILENT",
                "field wildcard + a concrete identifier for the SAME field | value-list $FIRES | aggregate $FIRES",
                "concrete field identifier + wildcard group | value-list $FIRES | aggregate $FIRES",
                "all-semantic field wildcard + concrete group rows | value-list $FIRES | aggregate $FIRES",
                "field wildcard + physical-root concrete group rows | value-list $FIRES | aggregate $SILENT",
                "concrete group rows only | value-list $SILENT | aggregate $SILENT",
            ),
            readings,
            "the two gates split on rows 2 and 6 — a wildcard beside concrete siblings it does not encompass. " +
                "The value-list gate is existential and fires; the all-rows aggregate gate is universal over " +
                "the reduced identifiers and stays UNKNOWN. Row 3 is the control that keeps the aggregate " +
                "reading honest: a wildcard DOES remove a concrete identifier for its own field, so the " +
                "aggregate fires there too and the split is about group expansion, not about wildcards as such",
        )
    }

    /**
     * EXP-2026-08-24-01's requested discriminator: the three direct starred count loops are separate from
     * the value-aggregate Combiner, so none inherits that gate without a runtime measurement. The first seven
     * rows are byte-for-byte the relevance shapes above, followed by the two incomplete concrete enumerations.
     * Every row pins the exact fired code set on both kernel strategies and the independent interpreter.
     */
    @Test
    fun theThreeCountLoopsSelectTheirMeasuredExtentGates() {
        val selections = listOf(
            "field wildcard alone" to listOf(field(QTY, 1, 0, 1)),
            "field wildcard + concrete group rows" to listOf(field(QTY, 1, 0, 1)) + concreteRows,
            "field wildcard + a concrete identifier for the same field" to
                listOf(field(QTY, 1, 0, 1), field(QTY, 1, 1, 1)),
            "concrete field identifier + wildcard group" to
                listOf(field(QTY, 1, 1, 1), Sel(ITEMS, listOf(0, 0))),
            "all-semantic field wildcard + concrete group rows" to
                listOf(field(QTY, 0, 0, 0)) + concreteRows,
            "field wildcard + physical-root concrete group rows" to
                listOf(field(QTY, 1, 0, 1)) + physicalRows,
            "both concrete group rows only" to concreteRows,
            "row 1 only" to listOf(concreteRows[0]),
            "row 2 only" to listOf(concreteRows[1]),
        )

        val expected = listOf(
            setOf(FILLED_FIELDS, VALUE_COUNT),
            setOf(VALUE_COUNT),
            setOf(FILLED_FIELDS, VALUE_COUNT),
            setOf(FILLED_FIELDS, FILLED_GROUPS, VALUE_COUNT),
            setOf(FILLED_FIELDS, VALUE_COUNT),
            setOf(VALUE_COUNT),
            emptySet(),
            emptySet(),
            emptySet(),
        )

        val readings = selections.mapIndexed { index, (label, selection) ->
            "$label | ${countVerdicts(selection, listOf("10", "10"), expected[index])}"
        }
        assertEquals(
            selections.mapIndexed { index, (label, _) ->
                "$label | groovy=${expected[index]} java=${expected[index]} interp=${expected[index]}"
            },
            readings,
        )
    }

    /** The operator controls prove the matrix's silence is relevance-driven rather than an inert fixture. */
    @Test
    fun theCountMatrixControlsReachEachOperator() {
        val wildcard = listOf(field(QTY, 0, 0, 0), Sel(ITEMS, listOf(0, 0)))
        assertEquals(
            "groovy=[$FILLED_FIELDS, $FILLED_GROUPS] java=[$FILLED_FIELDS, $FILLED_GROUPS] " +
                "interp=[$FILLED_FIELDS, $FILLED_GROUPS]",
            countVerdicts(wildcard, listOf("11", "11"), setOf(FILLED_FIELDS, FILLED_GROUPS)),
        )
        assertEquals(
            "groovy=[$FILLED_GROUPS] java=[$FILLED_GROUPS] interp=[$FILLED_GROUPS]",
            countVerdicts(wildcard, listOf(null, null), setOf(FILLED_GROUPS)),
        )
        assertEquals(
            "groovy=[] java=[] interp=[]",
            countVerdicts(wildcard, emptyList(), emptySet()),
        )
    }

    private fun countVerdicts(selection: List<Sel>, quantities: List<String?>, expected: Set<String>): String {
        val kDoc = RuntimeLaws.doc().group(ORDER, 1).field(ACK, "X", 1, 1)
        val iDoc = countDef.newDocument().group(ORDER, 1).field(ACK, "X", 1, 1)
        quantities.forEachIndexed { index, quantity ->
            val row = index + 1
            kDoc.group(ITEMS, 1, row)
            iDoc.group(ITEMS, 1, row)
            quantity?.let {
                kDoc.field(QTY, it, 1, row, 1)
                iDoc.field(QTY, it, 1, row, 1)
            }
        }
        val relevant = selection + Sel(ACK, listOf(0, 0))
        val kRelevant = relevant.map { Relevant.of(it.path, *it.reps.toIntArray()) }.toSet()
        val iRelevant = relevant.map { RelevantEntity(it.path, it.reps) }.toSet()
        val dynamic = RuntimeLaws.messagesPart(countModel, kDoc, kRelevant)
            .map { it.code() }.filter { it in COUNT_CODES }.toSortedSet()
        val static = countEngine.messagesPart(kDoc, kRelevant)
            .map { it.code() }.filter { it in COUNT_CODES }.toSortedSet()
        val interpreter = DmInterpreter(countDef).validatePart(iDoc, iRelevant).messages
            .map { it.code }.filter { it in COUNT_CODES }.toSortedSet()

        assertEquals(expected, dynamic, "dynamic Groovy count pattern")
        assertEquals(dynamic, static, "dynamic Groovy vs generated Java count pattern")
        assertEquals(dynamic, interpreter, "kernel vs interpreter count pattern")
        return "groovy=$dynamic java=$static interp=$interpreter"
    }

    private fun verdicts(condition: String, selection: List<Sel>): String {
        val model = models.getOrPut(condition) {
            RuntimeLaws.withRule(json, "/Order", "EXT", "../Ack", "EXT", "FieldFilled(/Order/Ack) And ($condition)")
        }
        val def = defs.getOrPut(model) { ModelLoader.load(model) }
        val kDoc = RuntimeLaws.doc().group("/Order", 1).field(ACK, "X", 1, 1)
        val iDoc = def.newDocument().group("/Order", 1).field(ACK, "X", 1, 1)
        listOf("C", "D").forEachIndexed { idx, tag ->
            val r = idx + 1
            kDoc.group(ITEMS, 1, r).field(TAG, tag, 1, r, 1).field(QTY, "10", 1, r, 1)
            iDoc.group(ITEMS, 1, r).field(TAG, tag, 1, r, 1).field(QTY, "10", 1, r, 1)
        }
        val kRel = (selection + Sel(ACK, listOf(0, 0))).map { Relevant.of(it.path, *it.reps.toIntArray()) }.toSet()
        val iRel = (selection + Sel(ACK, listOf(0, 0))).map { RelevantEntity(it.path, it.reps) }.toSet()
        val dynamic = RuntimeLaws.messagesPart(model, kDoc, kRel).any { it.code() == "EXT" }
        val static = engines.getOrPut(model) { RuntimeLaws.kernelJavaEngine(model) }
            .messagesPart(kDoc, kRel).any { it.code() == "EXT" }
        val interp = DmInterpreter(def).validatePart(iDoc, iRel).messages.any { it.code == "EXT" }
        return "groovy=$dynamic java=$static interp=$interp"
    }

    private data class Sel(val path: String, val reps: List<Int>)

    private fun field(path: String, vararg reps: Int) = Sel(path, reps.toList())

    private val concreteRows = listOf(Sel(ITEMS, listOf(0, 1)), Sel(ITEMS, listOf(0, 2)))
    private val physicalRows = listOf(Sel(ITEMS, listOf(1, 1)), Sel(ITEMS, listOf(1, 2)))

    private companion object {
        const val ORDER = "/Order"
        const val ACK = "/Order/Ack"
        const val ITEMS = "/Order/Items"
        const val TAG = "/Order/Items/Tag"
        const val QTY = "/Order/Items/Qty"
        const val FILLED_FIELDS = "NFF"
        const val FILLED_GROUPS = "NFG"
        const val VALUE_COUNT = "NVV"
        val COUNT_CODES = setOf(FILLED_FIELDS, FILLED_GROUPS, VALUE_COUNT)
        const val VALUE_LIST = "NoFieldValueIncludedInValueList(/Order/Items*/Tag In \"A\")"
        const val AGGREGATE = "Sum(/Order/Items*/Qty) > 5"
        const val FIRES = "groovy=true java=true interp=true"
        const val SILENT = "groovy=false java=false interp=false"
    }
}
