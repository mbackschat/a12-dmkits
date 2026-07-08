package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.interpreter.DmInterpreter
import io.github.mbackschat.a12.dm.interpreter.eval.Document
import io.github.mbackschat.a12.dm.interpreter.eval.newDocument
import io.github.mbackschat.a12.dm.interpreter.load.ModelLoader
import io.github.mbackschat.a12.dm.interpreter.model.ComputeOutcome
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Differential — IF87 (graduated from the IG44 residual): a computed field targeted by MULTIPLE computations
 * (the composed base + subtype-override shape — a single flat model rejects duplicate computations, so this only
 * arises via model composition/includes). The kernel processes a field's computations in DOCUMENT (group-declaration)
 * order and the FIRST that yields a NON-EMPTY outcome (a VALUE or a POISON) wins; an empty/clearing computation
 * (precondition-false / no matching alternative) is a NO-OP and control falls through to the next
 * (`CalculationController.handleBerechnetenWert` — *"for empty values, do like no calculation was done"*). The
 * interpreter formerly DEDUPED by target path (`computationsInDependencyOrder`), keeping only the FIRST computation
 * and using its outcome even when EMPTY — so an empty base calc masked a later value/poison override.
 *
 * Both kernel strategies share the runtime calc controller ⟹ codegen-parity (static-JAVA tri-checked).
 *
 * Own-domain (a quote whose `Price` is computed by a base calc in group `Base` and a subtype calc in group `Sub`).
 */
class MultiComputationFieldDiffTest {

    private val base = DocumentModelDraft.named("quote").locale("en_US")
        .rootGroup("Quote") { q ->
            q.numberField("Price") { it.fractionDigits(2) }        // the shared computed target
            q.numberField("Warn") { }                              // a dependent computed field (scale 0)
            q.stringField("Flag") { }                              // a gate for the base calc's precondition
            q.group("Base") { g -> g.numberField("BaseAmt") { it.fractionDigits(2) }; g.numberField("BadBase") { it.fractionDigits(2, 2) } }
            q.group("Sub") { g -> g.numberField("SubAmt") { it.fractionDigits(2) }; g.numberField("BadSub") { it.fractionDigits(2, 2) } }
        }.build().toJson()

    // Price computations live in the sibling subgroups (element-relative `../../Price` reaches the shared target).
    private fun priceUncond(m: String, group: String, name: String, op: String) =
        RuntimeLaws.withComputation(m, group, name, "../../Price", op)
    private fun priceGuarded(m: String, group: String, name: String, guard: String, op: String) =
        RuntimeLaws.withTieredComputation(m, group, name, "../../Price", guard, listOf(RuntimeLaws.CompAlt(null, op)))

    private class Both(val kDoc: RuntimeLaws.Doc, val iDoc: Document) {
        fun group(path: String, vararg reps: Int): Both { kDoc.group(path, *reps); iDoc.group(path, *reps); return this }
        fun field(path: String, v: String, vararg reps: Int): Both { kDoc.field(path, v, *reps); iDoc.field(path, v, *reps); return this }
    }

    /** groovy-dynamic == java-static == interpreter compute outcomes, as a `path → outcome` map (every target seeded). */
    private fun agree(kModel: String, msg: String, expect: Map<String, String>, build: (Both) -> Unit) {
        val def = ModelLoader.load(kModel)
        val b = Both(RuntimeLaws.doc(), def.newDocument()); build(b)
        val groovy = RuntimeLaws.compute(kModel, b.kDoc).associate { it.path() to kSig(it) }
        val engine = RuntimeLaws.kernelJavaEngine(kModel)
        val kDoc2 = RuntimeLaws.doc(); build(Both(kDoc2, def.newDocument()))
        val javaStatic = engine.computeOutcomesSorted(engine.instance(kDoc2)).associate {
            it.substringBefore('|') to when (val v = it.substringAfter('|')) { "ERR" -> "ERRORED"; "CLEARED" -> "CLEARED"; else -> "VALUE|$v" }
        }
        val interp = DmInterpreter(def).compute(b.iDoc).associate { it.path to iSig(it) }
        assertEquals(expect, groovy, "$msg — the kernel groovy-dynamic outcome")
        assertEquals(groovy, javaStatic, "$msg — groovy vs java-static (tri-check)")
        assertEquals(groovy, interp, "$msg — kernel vs interpreter")
    }

    private fun kSig(c: RuntimeLaws.Computed): String =
        if (c.cleared()) "CLEARED" else if (c.errored()) "ERRORED" else "VALUE|${c.value().orElse("")}"
    private fun iSig(c: io.github.mbackschat.a12.dm.interpreter.model.Computed): String = when (c.outcome) {
        ComputeOutcome.CLEARED -> "CLEARED"; ComputeOutcome.ERRORED -> "ERRORED"; ComputeOutcome.VALUE -> "VALUE|${c.value}"
    }

    private fun seedPriceRows(d: Both) {
        d.group("/Quote", 1).field("/Quote/Price", "99.00", 1, 1)
        d.group("/Quote/Base", 1, 1); d.group("/Quote/Sub", 1, 1)
    }

    // ── value-first wins over a later value (control — the interpreter already keeps the first computation) ──
    @Test fun valueFirstWinsOverLaterValue() {
        val m = priceUncond(priceUncond(base, "/Quote/Base", "CA", "[BaseAmt]"), "/Quote/Sub", "CB", "[SubAmt]")
        agree(m, "Base value(10) is document-first → wins over Sub value(20)", mapOf("/Quote/Price" to "VALUE|10")) { d ->
            seedPriceRows(d); d.field("/Quote/Base/BaseAmt", "10.00", 1, 1, 1); d.field("/Quote/Sub/SubAmt", "20.00", 1, 1, 1)
        }
    }

    // ── an EMPTY base calc is a no-op → control falls through to the later value (was: interp CLEARED) ──
    @Test fun emptyFirstFallsThroughToLaterValue() {
        val m = priceUncond(priceGuarded(base, "/Quote/Base", "CA", "FieldFilled(../Flag)", "[BaseAmt]"), "/Quote/Sub", "CB", "[SubAmt]")
        agree(m, "Base precondition FALSE (empty, no-op) → Sub value(20) wins", mapOf("/Quote/Price" to "VALUE|20")) { d ->
            seedPriceRows(d); d.field("/Quote/Base/BaseAmt", "10.00", 1, 1, 1); d.field("/Quote/Sub/SubAmt", "20.00", 1, 1, 1)
        }
    }

    // ── an empty SECOND calc is a no-op → the first value survives (control) ──
    @Test fun emptySecondIsNoOp() {
        val m = priceGuarded(priceUncond(base, "/Quote/Base", "CA", "[BaseAmt]"), "/Quote/Sub", "CB", "FieldFilled(../Flag)", "[SubAmt]")
        agree(m, "Base value(10) wins; the empty Sub calc is a no-op", mapOf("/Quote/Price" to "VALUE|10")) { d ->
            seedPriceRows(d); d.field("/Quote/Base/BaseAmt", "10.00", 1, 1, 1); d.field("/Quote/Sub/SubAmt", "20.00", 1, 1, 1)
        }
    }

    // ── value-first wins over a later POISON (control — interp keeps the first, valid, computation) ──
    @Test fun valueFirstWinsOverLaterPoison() {
        val m = priceUncond(priceUncond(base, "/Quote/Base", "CA", "[BaseAmt]"), "/Quote/Sub", "CB", "[BadSub]")
        agree(m, "Base value(10) wins; the later invalid-operand Sub calc is never reached", mapOf("/Quote/Price" to "VALUE|10")) { d ->
            seedPriceRows(d); d.field("/Quote/Base/BaseAmt", "10.00", 1, 1, 1); d.field("/Quote/Sub/BadSub", "5", 1, 1, 1)
        }
    }

    // ── poison-first wins over a later value (control — interp keeps the first, poisoning, computation) ──
    @Test fun poisonFirstWinsOverLaterValue() {
        val m = priceUncond(priceUncond(base, "/Quote/Base", "CA", "[BadBase]"), "/Quote/Sub", "CB", "[SubAmt]")
        agree(m, "Base invalid-operand poison is document-first → wins; the later Sub value is never reached", mapOf("/Quote/Price" to "CLEARED")) { d ->
            seedPriceRows(d); d.field("/Quote/Base/BadBase", "5", 1, 1, 1); d.field("/Quote/Sub/SubAmt", "20.00", 1, 1, 1)
        }
    }

    // ── the corpus shape: empty base + poisoning subtype → Price POISONED → a FieldNotFilled(Price) dependent cascades ──
    @Test fun emptyThenPoisonCascadesToDependent() {
        var m = priceGuarded(base, "/Quote/Base", "CA", "FieldFilled(../Flag)", "[BaseAmt]")   // empty (gate false) — document-first
        m = priceUncond(m, "/Quote/Sub", "CB", "[BadSub]")                                          // poison (invalid operand)
        // The dependent lives in Sub (after Base) so the base calc stays document-first — the interp's old
        // order-dependent dedup then keeps the EMPTY base calc, and the divergence is deterministic.
        m = RuntimeLaws.withTieredComputation(m, "/Quote/Sub", "WarnComp", "../../Warn", null,
            listOf(RuntimeLaws.CompAlt("FieldNotFilled(../Price)", "1")))                            // reads Price → poisons
        agree(m, "Price poisoned by the subtype calc → the dependent's FieldNotFilled(Price) read poisons → CLEARED",
            mapOf("/Quote/Price" to "CLEARED", "/Quote/Warn" to "CLEARED")) { d ->
            seedPriceRows(d); d.field("/Quote/Warn", "7", 1, 1)
            d.field("/Quote/Base/BaseAmt", "10.00", 1, 1, 1); d.field("/Quote/Sub/BadSub", "5", 1, 1, 1)
        }
    }
}
