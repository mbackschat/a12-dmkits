package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.adapter.laws.RuntimeLaws.Relevant
import io.github.mbackschat.a12.dm.interpreter.DmInterpreter
import io.github.mbackschat.a12.dm.interpreter.eval.newDocument
import io.github.mbackschat.a12.dm.interpreter.load.ModelLoader
import io.github.mbackschat.a12.dm.interpreter.model.RelevantEntity
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl
import io.github.mbackschat.a12.dm.rulekit.dsl.NumberExpr
import io.github.mbackschat.a12.dm.rulekit.dsl.Severity
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Which relevance lets a partial `SumOfProducts` decide (a12-kernel-lean SPEC-2026-10-06-03). Each depot keys its
 * bins, and a product multiplies two selected bin lists — the key's depot with itself, with another depot, or with
 * a depot no key names — beside an unkeyed spare-part product. The product decides only when its extent is covered
 * to the DECLARED lower capacity, absent slots included; a missing keyed parent needs a wildcard at the parent level.
 * `Sum` and `NumberOfFilledFields` over the same keyed and unkeyed lists keep their own wildcard gate.
 * Every document and relevance pair is compared on both kernel strategies and the interpreter, and the separating
 * rows are pinned on the kernel.
 *
 * Own domain (a store's depots, keyed by code, and their bins).
 */
class PartialProductCapacityDiffTest {

    private val json = DocumentModelDraft.named("store-products").locale("en_US")
        .rootGroup("Store") { s ->
            s.stringField("Ref") { }.stringField("Key") { }
                .group("Depots") { d ->
                    d.repeatable(3).indexField("Code").stringField("Code") { }
                        .group("Bins") { b -> b.repeatable(3).numberField("Qty") { n -> n.fractionDigits(0) }
                            .numberField("Weight") { n -> n.fractionDigits(0) } }
                }
                .group("Spare") { p -> p.repeatable(3).numberField("Qty") { n -> n.fractionDigits(0) } }
        }.build().toJson()

    private val model = run {
        val bound = BoundModel.ofJson(json)
        val store = bound.group(STORE)
        val ref = bound.str(REF)
        val key = bound.field(KEY)
        val bins = bound.flatten("$DEPOTS/Bins*/Qty")
        val byKey = bins.forIndex(key)
        val every = bound.flatten("$DEPOTS*/Bins*/Qty")
        fun at(code: String) = bins.forIndex(code)
        fun rule(code: String, product: NumberExpr, total: Long) =
            bound.rule(store).name(code).errorField(ref).`when`(ref.filled().and(product.eq(total)))
                .code(code).severity(Severity.ERROR).message(Dsl.en("product")).build()
        listOf(
            rule("P11", Dsl.sumOfProducts(byKey, byKey), 29),
            rule("P12", Dsl.sumOfProducts(byKey, at("D2")), 6),
            rule("P21", Dsl.sumOfProducts(at("D2"), byKey), 6),
            rule("P1X", Dsl.sumOfProducts(byKey, at("none")), 0),
            rule("PXX", Dsl.sumOfProducts(at("none"), at("none")), 0),
            rule("P12Z", Dsl.sumOfProducts(byKey, at("D2")), 0),
            rule("P21Z", Dsl.sumOfProducts(at("D2"), byKey), 0),
            rule("SPARE", Dsl.sumOfProducts(bound.flatten("$SPARE*/Qty"), bound.flatten("$SPARE*/Qty")), 13),
            rule("SUMX", Dsl.sum(at("none")), 0),
            rule("CNTX", Dsl.numberOfFilledFields(at("none")), 0),
            rule("SUMP", Dsl.sum(every), 10),
            rule("CNTP", Dsl.numberOfFilledFields(every), 3),
            rule("SUMK", Dsl.sum(byKey), 7),
            rule("CNTK", Dsl.numberOfFilledFields(byKey), 2),
        ).fold(json) { modelJson, rule -> TypedRules.inject(modelJson, rule) }
    }
    private val def = ModelLoader.load(model)
    private val engine by lazy { RuntimeLaws.kernelJavaEngine(model) }

    /** A relevance coordinate: a path and its repetition per level, 0 being the wildcard. */
    private data class Sel(val path: String, val reps: List<Int>)

    private fun all(path: String) = Sel(path, List(path.count { it == '/' }) { 0 })
    private fun bin(depot: Int, slot: Int) = Sel(QTY, listOf(1, depot, slot, 1))
    private val prerequisites = listOf(all(REF), all(KEY), all(CODE), all(SPARE))

    private val selections: Map<String, List<Sel>> = linkedMapOf(
        "root" to listOf(all(STORE)),
        "pair" to prerequisites + listOf(bin(1, 1), bin(2, 1)),
        "concrete" to prerequisites + listOf(bin(1, 1), bin(1, 2), bin(2, 1)),
        "left-only" to prerequisites + listOf(bin(1, 1), bin(1, 2)),
        "no-target" to prerequisites,
        "target-wild" to prerequisites + all(QTY),
        "no-key" to listOf(all(REF), all(CODE), all(SPARE), all(QTY)),
        "no-index" to listOf(all(REF), all(KEY), all(SPARE), all(QTY)),
        "all9" to prerequisites + (1..3).flatMap { d -> (1..3).map { s -> bin(d, s) } },
        "key-wild-present" to prerequisites + listOf(bin(0, 1), bin(0, 2)),
        "key-wild-capacity" to prerequisites + listOf(bin(0, 1), bin(0, 2), bin(0, 3)),
        "lower-wild-parents" to prerequisites + listOf(bin(1, 0), bin(2, 0)),
        "lower-wild-left" to prerequisites + listOf(bin(1, 0)),
        "key-wild-pair" to prerequisites + listOf(bin(0, 1)),
        "spare-concrete-2" to listOf(all(REF), Sel(SPARE_QTY, listOf(1, 1, 1)), Sel(SPARE_QTY, listOf(1, 2, 1))),
        "spare-concrete-3" to listOf(all(REF)) + (1..3).map { Sel(SPARE_QTY, listOf(1, it, 1)) },
    )

    /** Depot D1 holds bins `[2, 5]`, depot D2 holds `[3]`, the spares `[2, 3]`; [key] names the depot read. */
    private val documents: Map<String, Pair<String, List<String?>>> = linkedMapOf(
        "healthy" to ("D1" to listOf("2", "5")),
        "invalid-tail" to ("D1" to listOf("2", "bad")),
        "invalid-pair" to ("D1" to listOf("bad", "5")),
        "missing-key" to ("absent" to listOf("2", "5")),
        "empty-left" to ("D1" to emptyList()),
    )

    /** One document write, applied alike to the kernel's document and the interpreter's. */
    private sealed interface Write
    private data class GroupAt(val path: String, val reps: IntArray) : Write
    private data class FieldAt(val path: String, val value: String, val reps: IntArray) : Write

    private fun writes(document: String): List<Write> {
        val (key, firstBins) = documents.getValue(document)
        val out = mutableListOf<Write>(GroupAt(STORE, intArrayOf(1)), FieldAt(REF, "R", intArrayOf(1, 1)),
            FieldAt(KEY, key, intArrayOf(1, 1)))
        for ((row, binsOf) in listOf(1 to firstBins, 2 to listOf("3"))) {
            out += GroupAt(DEPOTS, intArrayOf(1, row))
            out += FieldAt(CODE, "D$row", intArrayOf(1, row, 1))
            binsOf.forEachIndexed { i, qty ->
                out += GroupAt(BINS, intArrayOf(1, row, i + 1))
                if (qty != null) out += FieldAt(QTY, qty, intArrayOf(1, row, i + 1, 1))
            }
        }
        listOf("2", "3").forEachIndexed { i, qty ->
            out += GroupAt(SPARE, intArrayOf(1, i + 1))
            out += FieldAt(SPARE_QTY, qty, intArrayOf(1, i + 1, 1))
        }
        return out
    }

    /** Fired code-and-type pairs, asserted equal across dynamic Groovy, generated Java and the interpreter. */
    private fun observe(document: String, selection: List<Sel>): Set<String> {
        val kDoc = RuntimeLaws.doc()
        val iDoc = def.newDocument()
        for (write in writes(document)) when (write) {
            is GroupAt -> { kDoc.group(write.path, *write.reps); iDoc.group(write.path, *write.reps) }
            is FieldAt -> { kDoc.field(write.path, write.value, *write.reps); iDoc.field(write.path, write.value, *write.reps) }
        }
        val kRelevant = selection.map { Relevant.of(it.path, *it.reps.toIntArray()) }.toSet()
        val iRelevant = selection.map { RelevantEntity(it.path, it.reps) }.toSet()
        val dynamic = RuntimeLaws.messagesPart(model, kDoc, kRelevant).map { "${it.code()}|${it.type().name.removeSuffix("_ERROR")}" }.toSortedSet()
        val static = engine.messagesPart(kDoc, kRelevant).map { "${it.code()}|${it.type().name.removeSuffix("_ERROR")}" }
            .toSortedSet()
        val interpreter = DmInterpreter(def).validatePart(iDoc, iRelevant).messages
            .map { "${it.code}|${it.type.name.removeSuffix("_ERROR")}" }.toSortedSet()
        assertEquals(dynamic, static, "$document / ${selection.size} coordinates: dynamic Groovy vs generated Java")
        assertEquals(dynamic, interpreter, "$document / ${selection.size} coordinates: kernel vs interpreter")
        return dynamic
    }

    @Test
    fun allThreeEnginesAgreeOnEveryDocumentAndRelevance() {
        val wrong = mutableListOf<String>()
        for (document in documents.keys) {
            for ((label, selection) in selections) {
                try {
                    observe(document, selection)
                } catch (e: AssertionError) {
                    wrong += "$document / $label: ${e.message?.take(400)}"
                }
            }
        }
        assertEquals(emptyList<String>(), wrong)
    }

    /**
     * The separating rows, pinned on the kernel so the agreement cannot pass vacuously. A product decides over the
     * key's depot covered to all three declared slots and not to two; a key no depot carries needs the depot level
     * wildcarded, so nine concrete coordinates do not stand in for it while a depot wildcard over the three slots
     * does; and the unkeyed control splits the same way at its declared capacity. `Sum` keeps its own, stricter
     * gate over the same lists: over a key no depot carries it decides only when every level is wildcarded, and over
     * the key's own depot only when the slot level is.
     */
    @Test
    fun aProductDecidesOnlyOverItsDeclaredLowerCapacity() {
        fun fired(document: String, label: String) =
            observe(document, selections.getValue(label)).map { it.substringBefore('|') }.toSet()
        fun products(document: String, label: String) =
            fired(document, label).filter { it.startsWith("P") }.toSet()
        val actual = linkedMapOf<String, Any>(
            "two of three slots under the key's depot" to products("healthy", "left-only"),
            "every slot of both depots" to products("healthy", "all9"),
            "a depot wildcard over three slots" to products("healthy", "key-wild-capacity"),
            "a depot wildcard over two slots" to products("healthy", "key-wild-present"),
            "a missing key, nine concrete coordinates" to products("missing-key", "all9"),
            "a missing key, a depot wildcard over three slots" to products("missing-key", "key-wild-capacity"),
            "the spares, two of three slots" to ("SPARE" in fired("healthy", "spare-concrete-2")),
            "the spares, all three slots" to ("SPARE" in fired("healthy", "spare-concrete-3")),
            "Sum over a key no depot carries, a depot wildcard over three slots" to
                ("SUMX" in fired("healthy", "key-wild-capacity")),
            "Sum over a key no depot carries, every level wildcarded" to ("SUMX" in fired("healthy", "target-wild")),
            "Sum over the key's depot, every slot concrete" to ("SUMK" in fired("healthy", "all9")),
            "Sum over the key's depot, its slots wildcarded" to ("SUMK" in fired("healthy", "lower-wild-left")),
        )
        assertEquals(linkedMapOf<String, Any>(
            "two of three slots under the key's depot" to emptySet<String>(),
            "every slot of both depots" to setOf("P11", "P12", "P21"),
            "a depot wildcard over three slots" to setOf("P11", "P12", "P1X", "P21", "PXX"),
            "a depot wildcard over two slots" to emptySet<String>(),
            "a missing key, nine concrete coordinates" to emptySet<String>(),
            "a missing key, a depot wildcard over three slots" to setOf("P12Z", "P1X", "P21Z", "PXX"),
            "the spares, two of three slots" to false,
            "the spares, all three slots" to true,
            "Sum over a key no depot carries, a depot wildcard over three slots" to false,
            "Sum over a key no depot carries, every level wildcarded" to true,
            "Sum over the key's depot, every slot concrete" to false,
            "Sum over the key's depot, its slots wildcarded" to true,
        ), actual)
    }

    private companion object {
        const val STORE = "/Store"
        const val REF = "/Store/Ref"
        const val KEY = "/Store/Key"
        const val DEPOTS = "/Store/Depots"
        const val CODE = "/Store/Depots/Code"
        const val BINS = "/Store/Depots/Bins"
        const val QTY = "/Store/Depots/Bins/Qty"
        const val SPARE = "/Store/Spare"
        const val SPARE_QTY = "/Store/Spare/Qty"
    }
}
