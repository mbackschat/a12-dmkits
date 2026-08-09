package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.interpreter.DmInterpreter
import io.github.mbackschat.a12.dm.interpreter.eval.Document
import io.github.mbackschat.a12.dm.interpreter.eval.newDocument
import io.github.mbackschat.a12.dm.interpreter.load.ModelLoader
import io.github.mbackschat.a12.dm.interpreter.model.ComputeOutcome
import io.github.mbackschat.a12.dm.edit.EditSession
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel
import io.github.mbackschat.a12.dm.rulekit.dsl.ConditionLanguage
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl
import io.github.mbackschat.a12.dm.rulekit.dsl.DslRenderer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Differential + tri-check (groovy-dynamic == java-static == interpreter) — a SEMANTIC-INDEX lookup
 * (`[Items/Price For LookupKey]`) as a COMPUTATION operation (the IG56 E4 hostile CVV minimal: a real
 * workspace `[OrderInformation/Price For SemIndex]` whose index COLUMN carries a hostile 16-digit cell — the
 * kernel CLEARS every reading computation, match or no match: the IndexFieldCache's invalid-index marking
 * (the semantic-index analog of IF96's parallel-iteration rule; any malformed, DUPLICATED, or EMPTY key cell
 * triggers it). A CLEAN column behaves like the validation read — a no-match and a matched-but-empty cell
 * both read empty-as-0 (the interpreter's original behavior, kernel-confirmed).
 */
class SemanticIndexComputeNoMatchDiffTest {

    private companion object {
        const val KEY = "/Shop/LookupKey"
        const val SEED_KEY = "/Shop/SeedKey"
        const val RESULT = "/Shop/Result"
        const val GROUP_RESULT = "/Shop/GroupResult"
        const val ITEM_NO = "/Shop/Items/ItemNo"
        const val PRICE = "/Shop/Items/Price"
        const val PROJECTION = "/Shop/Items/Projection"
        const val ITEMS = "/Shop/Items"
        const val NESTED_RESULT = "/Tariff/Result"
        const val NESTED_OFFERED = "/Tariff/Offered"
        const val NESTED_ITEM_NO = "/Tariff/Offered/ItemNo"
        const val NESTED_DETAILS = "/Tariff/Offered/Details"
        const val NESTED_SOURCE = "/Tariff/Offered/Details/Source"
        const val NESTED_PREMIUM = "/Tariff/Offered/Details/Premium"
    }

    private val base = DocumentModelDraft.named("pricelookup").locale("en_US")
        .rootGroup("Shop") { s ->
            s.numberField("LookupKey") { it.fractionDigits(0, 0) }
            s.numberField("SeedKey") { it.fractionDigits(0, 0) }
            s.numberField("Result") { it.fractionDigits(2, 2) }
            s.numberField("GroupResult") { it.fractionDigits(2, 2) }
            s.group("Items") { i ->
                i.repeatable(10).numberField("Projection") { it.fractionDigits(2, 2) }
                    .numberField("ItemNo") { it.fractionDigits(0, 0) }
                    .numberField("Price") { it.fractionDigits(2, 2) }
                    .indexField("ItemNo")
            }
        }.build().toJson()

    private val kModel = RuntimeLaws.withComputation(base, "/Shop", "LookupComp", "../Result",
        "[Items/Price For LookupKey]")

    private val cascadeModel by lazy {
        val session = EditSession.read(base)
        val items = session.group(ITEMS)
        items.addComputation(items.num("Projection")) { computation ->
            computation.name("BuildProjection")
                .calc(items.num("Price").value())
                .message(Dsl.en("Project the source price."))
                .build()
        }
        val shop = session.group("/Shop")
        shop.addComputation(shop.num("Result")) { computation ->
            computation.name("ReadProjection")
                .calc(items.num("Projection").forIndex(shop.num("LookupKey").field()).expression())
                .message(Dsl.en("Read the projected price by semantic key."))
                .build()
        }
        val bound = BoundModel.ofJson(base)
        shop.addComputation(shop.num("GroupResult")) { computation ->
            computation.name("ReadGroupProjection")
                .calc(
                    Dsl.firstFilledNumber(
                        bound.firstFilledGroupFor(ITEMS, bound.field(KEY)),
                    ).expression(),
                )
                .message(Dsl.en("Read the first projected value from the selected group."))
                .build()
        }
        session.validate().toJson()
    }

    private val computedQueryModel by lazy {
        val session = EditSession.read(base)
        val shop = session.group("/Shop")
        shop.addComputation(shop.num("LookupKey")) { computation ->
            computation.name("BuildLookupKey")
                .calc(shop.num("SeedKey").value())
                .message(Dsl.en("Build the semantic lookup key."))
                .build()
        }
        val items = session.group(ITEMS)
        shop.addComputation(shop.num("Result")) { computation ->
            computation.name("ReadWithComputedKey")
                .calc(items.num("Price").forIndex(shop.num("LookupKey").field()).expression())
                .message(Dsl.en("Read the price through the computed semantic key."))
                .build()
        }
        session.validate().toJson()
    }

    private val nestedGroupCascadeModel by lazy {
        val nestedBase = DocumentModelDraft.named("semantic-nested-group-compute").locale("en_US")
            .rootGroup("Tariff") { tariff ->
                tariff.numberField("Result") { it.fractionDigits(0, 0) }
                    .group("Offered") { offered ->
                        offered.repeatable(10)
                            .numberField("ItemNo") { it.fractionDigits(0, 0) }
                            .group("Details") { details ->
                                details.numberField("Source") { it.fractionDigits(0, 0) }
                                    .numberField("Premium") { it.fractionDigits(0, 0) }
                            }
                            .indexField("ItemNo")
                    }
            }.build().toJson()
        val session = EditSession.read(nestedBase)
        val details = session.group(NESTED_DETAILS)
        details.addComputation(details.num("Premium")) { computation ->
            computation.name("BuildPremium")
                .calc(details.num("Source").value())
                .message(Dsl.en("Build the nested premium."))
                .build()
        }
        val tariff = session.group("/Tariff")
        val bound = BoundModel.ofJson(nestedBase)
        tariff.addComputation(tariff.num("Result")) { computation ->
            computation.name("ReadSelectedDetails")
                .calc(Dsl.firstFilledNumber(bound.firstFilledGroupFor(NESTED_DETAILS, "5")).expression())
                .message(Dsl.en("Read the selected nested group."))
                .build()
        }
        session.validate().toJson()
    }

    /** [rows]: (itemNo?, price?) per row. Returns the tri-checked Result outcome. */
    private fun triOutcome(key: String, rows: List<Pair<String?, String?>>): String =
        triCompute(kModel, key, rows)

    private fun triCompute(
        model: String,
        key: String?,
        rows: List<Pair<String?, String?>>,
        target: String = RESULT,
        seedKey: String? = null,
    ): String {
        val def = ModelLoader.load(model)
        fun build(k: RuntimeLaws.Doc, i: Document) {
            k.group("/Shop", 1).field(RESULT, "999.99", 1, 1).field(GROUP_RESULT, "999.99", 1, 1)
            i.group("/Shop", 1).field(RESULT, "999.99", 1, 1).field(GROUP_RESULT, "999.99", 1, 1)
            key?.let { k.field(KEY, it, 1, 1); i.field(KEY, it, 1, 1) }
            seedKey?.let { k.field(SEED_KEY, it, 1, 1); i.field(SEED_KEY, it, 1, 1) }
            rows.forEachIndexed { idx, (no, price) ->
                val r = idx + 1
                k.group(ITEMS, 1, r); i.group(ITEMS, 1, r)
                no?.let { k.field(ITEM_NO, it, 1, r, 1); i.field(ITEM_NO, it, 1, r, 1) }
                price?.let { k.field(PRICE, it, 1, r, 1); i.field(PRICE, it, 1, r, 1) }
            }
        }
        val kDoc = RuntimeLaws.doc(); val iDoc = def.newDocument()
        build(kDoc, iDoc)
        val groovy = RuntimeLaws.compute(model, kDoc).firstOrNull { it.pointer() == "/Shop[1]/${target.substringAfterLast('/')}" }
            .let { c -> if (c == null) "NONE" else if (c.cleared()) "CLEARED" else if (c.errored()) "ERRORED" else "VALUE|${c.value().orElse("")}" }
        val engine = RuntimeLaws.kernelJavaEngine(model)
        val kDoc2 = RuntimeLaws.doc(); val iDoc2 = def.newDocument()
        build(kDoc2, iDoc2)
        val javaStatic = engine.computeOutcomesSorted(engine.instance(kDoc2)).firstOrNull { it.startsWith("$target|") }
            ?.substringAfter("$target|")?.let { if (it == "ERR") "ERRORED" else if (it == "CLEARED") "CLEARED" else "VALUE|$it" } ?: "NONE"
        val interp = DmInterpreter(def).compute(iDoc).firstOrNull { it.path == target }
            .let { c -> when (c?.outcome) {
                null -> "NONE"
                ComputeOutcome.CLEARED -> "CLEARED"
                ComputeOutcome.ERRORED -> "ERRORED"
                ComputeOutcome.VALUE -> "VALUE|${c.value}"
            } }
        assertEquals(groovy, javaStatic, "key=$key rows=$rows — groovy vs java-static (tri-check)")
        assertEquals(groovy, interp, "key=$key rows=$rows — kernel vs interpreter")
        return groovy
    }

    @Test fun noMatchOverACleanColumnIsEmptyAsZero() =
        assertEquals("VALUE|0.00", triOutcome("274", listOf("534" to "0.01", "12" to "0.02")),
            "a clean index column with no matching row reads empty-as-0 — same as the validation read")

    @Test fun matchingRowComputesItsPrice() =
        assertEquals("VALUE|5.25", triOutcome("12", listOf("534" to "0.01", "12" to "5.25")))

    @Test fun semanticReadSeesAPayloadComputedEarlierInTheCascade() =
        assertEquals(
            "VALUE|5.25",
            triCompute(cascadeModel, "12", listOf("534" to "0.01", "12" to "5.25")),
            "row selection may use the stored index, but the selected payload must come from the current " +
                "computation overlay",
        )

    @Test fun semanticGroupReadSeesPayloadsComputedEarlierInTheCascade() =
        assertEquals(
            "VALUE|5.25",
            triCompute(cascadeModel, "12", listOf("534" to "0.01", "12" to "5.25"), GROUP_RESULT),
            "the selected group's contained-field scan must project the computation overlay, not the stored row",
        )

    @Test fun semanticReadSeesAQueryKeyComputedEarlierInTheCascade() =
        assertEquals(
            "VALUE|5.25",
            triCompute(
                computedQueryModel,
                key = null,
                rows = listOf("534" to "0.01", "12" to "5.25"),
                seedKey = "12",
            ),
            "semantic row selection must resolve its field-valued query from the current computation overlay",
        )

    @Test
    fun nestedSemanticGroupReadSeesPayloadComputedEarlierInTheCascade() {
        val model = nestedGroupCascadeModel
        val definition = ModelLoader.load(model)
        fun kernelDocument(): RuntimeLaws.Doc = RuntimeLaws.doc().also { kernel ->
            kernel.group("/Tariff", 1).field(NESTED_RESULT, "999", 1, 1)
            listOf("6" to "10", "5" to "20").forEachIndexed { index, (itemNo, source) ->
                val row = index + 1
                kernel.group(NESTED_OFFERED, 1, row)
                    .field(NESTED_ITEM_NO, itemNo, 1, row, 1)
                    .group(NESTED_DETAILS, 1, row, 1)
                    .field(NESTED_SOURCE, source, 1, row, 1, 1)
            }
        }
        fun interpreterDocument(): Document = definition.newDocument().also { interpreter ->
            interpreter.group("/Tariff", 1).field(NESTED_RESULT, "999", 1, 1)
            listOf("6" to "10", "5" to "20").forEachIndexed { index, (itemNo, source) ->
                val row = index + 1
                interpreter.group(NESTED_OFFERED, 1, row)
                    .field(NESTED_ITEM_NO, itemNo, 1, row, 1)
                    .group(NESTED_DETAILS, 1, row, 1)
                    .field(NESTED_SOURCE, source, 1, row, 1, 1)
            }
        }

        val dynamic = RuntimeLaws.compute(model, kernelDocument())
            .single { it.pointer() == "/Tariff[1]/Result" }.value().orElseThrow()
        val engine = RuntimeLaws.kernelJavaEngine(model)
        val javaStatic = engine.computeOutcomesSorted(engine.instance(kernelDocument()))
            .single { it.startsWith("$NESTED_RESULT|") }.substringAfter('|')
        val interpreted = DmInterpreter(definition).compute(interpreterDocument())
            .single { it.path == NESTED_RESULT }.value

        assertEquals("20", dynamic, "dynamic kernel")
        assertEquals(dynamic, javaStatic, "generated Java")
        assertEquals(dynamic, interpreted, "interpreter overlay")
    }

    @Test fun noMatchWithAnInvalidIndexCellClears() =
        assertEquals("CLEARED", triOutcome("274", listOf("534" to "0.01", "9999999999999999" to "0.01")),
            "the corpus instance: no match; a 16-digit index cell present")

    @Test fun matchWithAnotherRowsIndexMalformedClears() =
        assertEquals("CLEARED", triOutcome("12", listOf<Pair<String?, String?>>("12" to "5.25", "9999999999999999" to "0.01")),
            "the column marking poisons the lookup even with a clean match")

    @Test fun matchWithADuplicateKeyElsewhereClears() =
        assertEquals("CLEARED", triOutcome("12", listOf<Pair<String?, String?>>("12" to "5.25", "7" to "0.01", "7" to "0.02")))

    @Test fun matchWithAnEmptyKeyCellElsewhereClears() =
        assertEquals("CLEARED", triOutcome("12", listOf<Pair<String?, String?>>("12" to "5.25", null to "0.01")))

    @Test fun matchedRowWithAnEmptyTargetCellIsEmptyAsZero() =
        assertEquals("VALUE|0.00", triOutcome("12", listOf<Pair<String?, String?>>("12" to null, "7" to "0.01")))

    // --- SPEC-2026-07-23-08: a Number index field compares keys by normalized numeric value ----------
    // Index-key identity is owned by the declared index-field kind (kernel IndexFieldCache.normalizeValue):
    // a Number index field normalizes BOTH the admitted stored key AND the requested (literal or field-valued)
    // key, so legal decimal spellings such as "5.00" (stored) and "5" (requested) select the same row. ItemNo
    // here admits 2 fractional digits so "5.00" is a VALID stored key — the scale-0 model above would reject it.
    private val decimalBase = DocumentModelDraft.named("pricelookup-decimal").locale("en_US")
        .rootGroup("Shop") { s ->
            s.numberField("LookupKey") { it.fractionDigits(0, 2) }
            s.numberField("Result") { it.fractionDigits(2, 2) }
            s.group("Items") { i ->
                i.repeatable(10).numberField("ItemNo") { it.fractionDigits(0, 2) }
                    .numberField("Price") { it.fractionDigits(2, 2) }.indexField("ItemNo")
            }
        }.build().toJson()
    private val dm = BoundModel.ofJson(decimalBase)
    private val rel = DslRenderer(ConditionLanguage.EN, "/Shop")

    /** [computationValue] is the rendered semantic-index expression computed into Result; [lookupKey] fills the
     *  LookupKey field for a field-valued key (null for a literal key). Returns the tri-checked Result outcome. */
    private fun triDecimal(computationValue: String, lookupKey: String?, rows: List<Pair<String?, String?>>): String {
        val model = RuntimeLaws.withComputation(decimalBase, "/Shop", "LookupComp", "../Result", computationValue)
        val def = ModelLoader.load(model)
        fun build(k: RuntimeLaws.Doc, i: Document) {
            k.group("/Shop", 1).field(RESULT, "999.99", 1, 1); i.group("/Shop", 1).field(RESULT, "999.99", 1, 1)
            lookupKey?.let { k.field(KEY, it, 1, 1); i.field(KEY, it, 1, 1) }
            rows.forEachIndexed { idx, (no, price) ->
                val r = idx + 1
                k.group(ITEMS, 1, r); i.group(ITEMS, 1, r)
                no?.let { k.field(ITEM_NO, it, 1, r, 1); i.field(ITEM_NO, it, 1, r, 1) }
                price?.let { k.field(PRICE, it, 1, r, 1); i.field(PRICE, it, 1, r, 1) }
            }
        }
        val kDoc = RuntimeLaws.doc(); val iDoc = def.newDocument(); build(kDoc, iDoc)
        val groovy = RuntimeLaws.compute(model, kDoc).firstOrNull { it.pointer() == "/Shop[1]/Result" }
            .let { c -> if (c == null) "NONE" else if (c.cleared()) "CLEARED" else if (c.errored()) "ERRORED" else "VALUE|${c.value().orElse("")}" }
        val engine = RuntimeLaws.kernelJavaEngine(model)
        val kDoc2 = RuntimeLaws.doc(); val iDoc2 = def.newDocument(); build(kDoc2, iDoc2)
        val javaStatic = engine.computeOutcomesSorted(engine.instance(kDoc2)).firstOrNull { it.startsWith("$RESULT|") }
            ?.substringAfter("$RESULT|")?.let { if (it == "ERR") "ERRORED" else if (it == "CLEARED") "CLEARED" else "VALUE|$it" } ?: "NONE"
        val interp = DmInterpreter(def).compute(iDoc).firstOrNull { it.path == RESULT }
            .let { c -> when (c?.outcome) {
                null -> "NONE"
                ComputeOutcome.CLEARED -> "CLEARED"
                ComputeOutcome.ERRORED -> "ERRORED"
                ComputeOutcome.VALUE -> "VALUE|${c.value}"
            } }
        assertEquals(groovy, javaStatic, "value=$computationValue key=$lookupKey rows=$rows — groovy vs java-static")
        assertEquals(groovy, interp, "value=$computationValue key=$lookupKey rows=$rows — kernel vs interpreter")
        return groovy
    }

    @Test fun literalNumberKeyMatchesADifferentlySpelledStoredKey() =
        assertEquals("VALUE|5.25",
            triDecimal(rel.render(dm.num(PRICE).forIndex("5").expression()), null,
                listOf("5.00" to "5.25", "7" to "0.01")),
            "requested literal 5 selects the row stored as 5.00 on a Number index field")

    @Test fun fieldValuedNumberKeyMatchesADifferentlySpelledStoredKey() =
        assertEquals("VALUE|5.25",
            triDecimal(rel.render(dm.num(PRICE).forIndex(dm.field(KEY)).expression()), "5",
                listOf("5.00" to "5.25", "7" to "0.01")),
            "field-valued key 5 selects the row stored as 5.00")

    @Test fun buildSideAndQuerySideNormalizeSymmetrically() =
        assertEquals("VALUE|5.25",
            triDecimal(rel.render(dm.num(PRICE).forIndex("5.00").expression()), null,
                listOf("5" to "5.25", "7" to "0.01")),
            "requested 5.00 selects the row stored as 5 — the reverse spelling asymmetry (second instance)")
}
