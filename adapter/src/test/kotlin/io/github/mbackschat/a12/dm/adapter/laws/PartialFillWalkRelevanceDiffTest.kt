package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.adapter.laws.RuntimeLaws.Relevant
import io.github.mbackschat.a12.dm.interpreter.DmInterpreter
import io.github.mbackschat.a12.dm.interpreter.eval.newDocument
import io.github.mbackschat.a12.dm.interpreter.load.ModelLoader
import io.github.mbackschat.a12.dm.interpreter.model.RelevantEntity
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel
import io.github.mbackschat.a12.dm.rulekit.dsl.Condition
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl
import io.github.mbackschat.a12.dm.rulekit.dsl.FieldListRef
import io.github.mbackschat.a12.dm.rulekit.dsl.Severity
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Which relevance lets a fill quantifier or `FirstFilledValue` over a field list decide under partial validation
 * (a12-kernel-lean SPEC-2026-10-06-03, the walks beside its `SumOfProducts`). Each depot keys its bins; every
 * quantifier reads three lists — all depots' bins, the key's depot, and a depot no key names — over documents that
 * store the key's bins filled, empty, malformed, or not at all. Every document and relevance pair is compared on both
 * kernel strategies and the interpreter.
 *
 * Own domain (a store's depots, keyed by code, and their bins).
 */
class PartialFillWalkRelevanceDiffTest {

    private val json = DocumentModelDraft.named("store-fill-walks").locale("en_US")
        .rootGroup("Store") { s ->
            s.stringField("Ref") { }.stringField("Key") { }
                .group("Depots") { d ->
                    d.repeatable(3).indexField("Code").stringField("Code") { }
                        .group("Bins") { b -> b.repeatable(3).numberField("Qty") { n -> n.fractionDigits(0) }
                            .numberField("Weight") { n -> n.fractionDigits(0) } }
                }
        }.build().toJson()

    private val model = run {
        val bound = BoundModel.ofJson(json)
        val store = bound.group(STORE)
        val ref = bound.str(REF)
        val bins = bound.flatten("$DEPOTS/Bins*/Qty")
        val lists: Map<String, FieldListRef> = linkedMapOf(
            "E" to bound.flatten("$DEPOTS*/Bins*/Qty"),
            "K" to bins.forIndex(bound.field(KEY)),
            "X" to bins.forIndex("none"),
        )
        val quantifiers: Map<String, (FieldListRef) -> Condition> = linkedMapOf(
            "ALO" to { Dsl.atLeastOneFieldFilled(it) },
            "NOF" to { Dsl.noFieldFilled(it) },
            "ALL" to { Dsl.allFieldsFilled(it) },
            "NAL" to { Dsl.notAllFieldsFilled(it) },
            "MTO" to { Dsl.moreThanOneFieldFilled(it) },
            "NEO" to { Dsl.notExactlyOneFieldFilled(it) },
            "FF" to { Dsl.firstFilledNumber(it).lt(100) },
        )
        (quantifiers.flatMap { (q, condition) ->
            lists.map { (l, list) ->
                bound.rule(store).name(q + l).errorField(ref).`when`(ref.filled().and(condition(list)))
                    .code(q + l).severity(Severity.ERROR).message(Dsl.en("bins")).build()
            }
        } + run {
            val group = bound.flattenGroup("$DEPOTS*/Bins*")
            val keyed = bound.flattenGroup("$DEPOTS/Bins*").forIndex(bound.field(KEY))
            linkedMapOf<String, Condition>(
                "ALOH" to Dsl.atLeastOneFieldFilled(keyed), "NOFH" to Dsl.noFieldFilled(keyed),
                "NALH" to Dsl.notAllFieldsFilled(keyed),
                "ALOG" to Dsl.atLeastOneFieldFilled(group), "NOFG" to Dsl.noFieldFilled(group),
                "ALLG" to Dsl.allFieldsFilled(group), "NALG" to Dsl.notAllFieldsFilled(group),
            ).map { (code, condition) ->
                bound.rule(store).name(code).errorField(ref).`when`(ref.filled().and(condition))
                    .code(code).severity(Severity.ERROR).message(Dsl.en("bins")).build()
            }
        }).fold(json) { modelJson, rule -> TypedRules.inject(modelJson, rule) }
    }
    private val def = ModelLoader.load(model)
    private val engine by lazy { RuntimeLaws.kernelJavaEngine(model) }

    /** A relevance coordinate: a path and its repetition per level, 0 being the wildcard. */
    private data class Sel(val path: String, val reps: List<Int>)

    private fun all(path: String) = Sel(path, List(path.count { it == '/' }) { 0 })
    private fun bin(depot: Int, slot: Int) = Sel(QTY, listOf(1, depot, slot, 1))
    private val prerequisites = listOf(all(REF), all(KEY), all(CODE))

    private val selections: Map<String, List<Sel>> = linkedMapOf(
        "root" to listOf(all(STORE)),
        "no-target" to prerequisites,
        "target-wild" to prerequisites + all(QTY),
        "first-slot" to prerequisites + listOf(bin(1, 1)),
        "stored-slots" to prerequisites + listOf(bin(1, 1), bin(1, 2)),
        "key-depot-slots" to prerequisites + (1..3).map { bin(1, it) },
        "key-depot-wild" to prerequisites + listOf(bin(1, 0)),
        "all9" to prerequisites + (1..3).flatMap { d -> (1..3).map { s -> bin(d, s) } },
        "depot-wild-two" to prerequisites + listOf(bin(0, 1), bin(0, 2)),
        "depot-wild-three" to prerequisites + (1..3).map { bin(0, it) },
        "third-slot" to prerequisites + listOf(bin(1, 3)),
        "weights-too" to prerequisites + listOf(all(QTY), all(WEIGHT)),
        "stored-depots" to prerequisites + (1..2).flatMap { d -> (1..3).map { s -> bin(d, s) } },
    )

    /** The key's depot D1 holds [first]; depot D2 always holds one empty bin. `null` stores the bin row alone. */
    private val documents: Map<String, Pair<String, List<String?>>> = linkedMapOf(
        "full" to ("D1" to listOf("2", "5", "1")),
        "filled" to ("D1" to listOf("2", "5")),
        "one-filled" to ("D1" to listOf("2")),
        "stored-empty" to ("D1" to listOf(null, null)),
        "none-stored" to ("D1" to emptyList()),
        "malformed-first" to ("D1" to listOf("bad", "5")),
        "missing-key" to ("absent" to listOf("2", "5")),
    )

    private sealed interface Write
    private data class GroupAt(val path: String, val reps: IntArray) : Write
    private data class FieldAt(val path: String, val value: String, val reps: IntArray) : Write

    private fun writes(document: String): List<Write> {
        val (key, firstBins) = documents.getValue(document)
        val out = mutableListOf<Write>(GroupAt(STORE, intArrayOf(1)), FieldAt(REF, "R", intArrayOf(1, 1)),
            FieldAt(KEY, key, intArrayOf(1, 1)))
        for ((row, binsOf) in listOf(1 to firstBins, 2 to listOf<String?>(null))) {
            out += GroupAt(DEPOTS, intArrayOf(1, row))
            out += FieldAt(CODE, "D$row", intArrayOf(1, row, 1))
            binsOf.forEachIndexed { i, qty ->
                out += GroupAt(BINS, intArrayOf(1, row, i + 1))
                if (qty != null) out += FieldAt(QTY, qty, intArrayOf(1, row, i + 1, 1))
            }
        }
        return out
    }

    /** Fired codes, asserted equal across dynamic Groovy, generated Java and the interpreter. */
    private fun observe(document: String, label: String): Set<String> {
        val kDoc = RuntimeLaws.doc()
        val iDoc = def.newDocument()
        for (write in writes(document)) when (write) {
            is GroupAt -> { kDoc.group(write.path, *write.reps); iDoc.group(write.path, *write.reps) }
            is FieldAt -> { kDoc.field(write.path, write.value, *write.reps); iDoc.field(write.path, write.value, *write.reps) }
        }
        val selection = selections.getValue(label)
        val kRelevant = selection.map { Relevant.of(it.path, *it.reps.toIntArray()) }.toSet()
        val iRelevant = selection.map { RelevantEntity(it.path, it.reps) }.toSet()
        val dynamic = RuntimeLaws.messagesPart(model, kDoc, kRelevant)
            .map { "${it.code()}|${it.type().name.removeSuffix("_ERROR")}" }.toSortedSet()
        val static = engine.messagesPart(kDoc, kRelevant)
            .map { "${it.code()}|${it.type().name.removeSuffix("_ERROR")}" }.toSortedSet()
        val interpreter = DmInterpreter(def).validatePart(iDoc, iRelevant).messages
            .map { "${it.code}|${it.type.name.removeSuffix("_ERROR")}" }.toSortedSet()
        assertEquals(dynamic, static, "$document / $label: dynamic Groovy vs generated Java")
        assertEquals(dynamic, interpreter, "$document / $label: kernel vs interpreter")
        return dynamic
    }

    @Test
    fun allThreeEnginesAgreeOnEveryDocumentAndRelevance() {
        val wrong = mutableListOf<String>()
        for (document in documents.keys) for (label in selections.keys) {
            try {
                observe(document, label)
            } catch (e: AssertionError) {
                wrong += "$document / $label: ${e.message?.take(400)}"
            }
        }
        assertEquals(emptyList<String>(), wrong)
    }

    /**
     * The separating rows, pinned on the kernel so the agreement cannot pass vacuously. A filled cell outside the
     * selection decides nothing; a missing slot outside it keeps `NoFieldFilled` and `FirstFilledValue` undecided,
     * even in a depot no row is stored for, while a relevant missing slot is empty and lets `NotAllFieldsFilled`
     * fire; and `AllFieldsFilled` needs every declared slot of the key's depot selected.
     */
    @Test
    fun everyDeclaredSlotMeetsTheSelection() {
        fun fired(document: String, label: String, vararg codes: String) =
            observe(document, label).map { it.substringBefore('|') }.filter { it in codes }.toSet()
        val actual = linkedMapOf(
            "filled cells, none selected" to fired("filled", "no-target", "ALOE", "ALOK", "NALE", "FFE"),
            "empty depot selected, the others not" to fired("stored-empty", "key-depot-wild", "NOFE", "NALE", "NOFK"),
            "both stored depots selected, the declared third not" to
                fired("stored-empty", "stored-depots", "NOFE", "FFE", "NOFK", "FFK"),
            "two of three slots per depot" to fired("stored-empty", "depot-wild-two", "NOFK", "FFK", "NALK"),
            "three of three slots per depot" to fired("stored-empty", "depot-wild-three", "NOFK", "FFK", "NOFX"),
            "the stored slots, filled" to fired("filled", "stored-slots", "ALOK", "MTOK", "NALK"),
            "a missing third slot alone" to fired("filled", "third-slot", "NALK", "ALOK"),
            "a full depot, two slots selected" to fired("full", "stored-slots", "ALLK"),
            "a full depot, three slots selected" to fired("full", "key-depot-slots", "ALLK"),
        )
        assertEquals(linkedMapOf(
            "filled cells, none selected" to emptySet(),
            "empty depot selected, the others not" to setOf("NALE", "NOFK"),
            "both stored depots selected, the declared third not" to setOf("NOFK", "FFK"),
            "two of three slots per depot" to setOf("NALK"),
            "three of three slots per depot" to setOf("NOFK", "FFK", "NOFX"),
            "the stored slots, filled" to setOf("ALOK", "MTOK"),
            "a missing third slot alone" to setOf("NALK"),
            "a full depot, two slots selected" to emptySet(),
            "a full depot, three slots selected" to setOf("ALLK"),
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
        const val WEIGHT = "/Store/Depots/Bins/Weight"
    }
}
