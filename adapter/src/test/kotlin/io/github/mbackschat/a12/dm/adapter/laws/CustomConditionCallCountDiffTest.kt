package io.github.mbackschat.a12.dm.adapter.laws

import com.mgmtp.a12.kernel.md.document.api.IDocument
import com.mgmtp.a12.kernel.md.document.api.IEntityInstance
import com.mgmtp.a12.kernel.md.rt.api.ICustomCondition
import io.github.mbackschat.a12.dm.edit.EditSession
import io.github.mbackschat.a12.dm.interpreter.DmInterpreter
import io.github.mbackschat.a12.dm.interpreter.eval.Document
import io.github.mbackschat.a12.dm.interpreter.eval.newDocument
import io.github.mbackschat.a12.dm.interpreter.load.ModelLoader
import io.github.mbackschat.a12.dm.interpreter.model.CustomCondition
import io.github.mbackschat.a12.dm.interpreter.model.RelevantEntity
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import io.github.mbackschat.a12.dm.rulekit.dsl.Condition
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * How often each engine calls a registered `CustomCondition` (KERNEL-FINDINGS KF604): only where A12's truth pass,
 * short-circuited on both connectives, reaches it, and once more when the rule holds and its constructs enable the
 * classification pass ([io.github.mbackschat.a12.dm.interpreter.eval.classificationEnabled]).
 *
 * Seven rules, each naming its own callback so their counts cannot mix: the callback behind an exempt and an
 * enabling `And`, behind an exempt and an enabling `Or`, behind a value comparison, and ahead of a negative in an
 * `And` and an `Or`. The note is empty or filled
 * and the callback answers true or false. The root carrier pins A12's counts; the full pass compares all three
 * engines, the partial pass (root relevant) dynamic Groovy with the interpreter, and a repeating carrier, one rule
 * run per line, all three again. A computation cannot carry a callback: A12 refuses one there
 * (`MVK_ERROR_INVALID_LANGUAGE_FEATURE`, KERNEL-SEMANTICS §14).
 *
 * Own domain: an order's note and quantity, and its lines'.
 */
class CustomConditionCallCountDiffTest {

    private class Carrier(val group: String) {
        val base: String = DocumentModelDraft.named("order-callback-count").locale("en_US")
            .rootGroup("Order") { o -> o
                .stringField("Note") { }
                .numberField("Qty") { n -> n.fractionDigits(0) }
                .group("Lines") { l -> l.repeatable(5)
                    .stringField("Note") { }
                    .numberField("Qty") { n -> n.fractionDigits(0) } } }
            .build().toJson()
        private val scope = EditSession.read(base).group(group)
        private val note = scope.str("Note")
        private val qty = scope.num("Qty")
        // A repeating level needs a guard before its negatives (MVK_NEG_CONDITION_IN_ITERATION); every line fills
        // Qty, so the guard holds on each and the counted shape is the root's.
        private fun guarded(condition: Condition) = if (group == "/Order") condition else qty.filled().and(condition)
        val conditions: Map<String, Condition> = linkedMapOf(
            "ExemptAnd" to guarded(note.filled().and(Dsl.customCondition("ExemptAnd"))),
            "EnablingAnd" to guarded(note.notFilled().and(Dsl.customCondition("EnablingAnd"))),
            "ExemptOr" to guarded(note.filled().or(Dsl.customCondition("ExemptOr"))),
            "EnablingOr" to guarded(note.notFilled().or(Dsl.customCondition("EnablingOr"))),
            "ValueAnd" to guarded(qty.gt(1).and(Dsl.customCondition("ValueAnd"))),
            "LeftAnd" to guarded(Dsl.customCondition("LeftAnd").and(note.notFilled())),
            "LeftOr" to guarded(Dsl.customCondition("LeftOr").or(note.notFilled())),
        )
        val model: String = conditions.entries.fold(base) { json, (name, condition) ->
            RuntimeLaws.withRule(json, group, name, if (name == "ValueAnd") "../Qty" else "../Note", name,
                RuntimeLaws.render(group, condition))
        }
    }

    private val root = Carrier("/Order")
    private val lines = Carrier("/Order/Lines")

    /** The root's quantity and note, or two lines of which exactly one has a note: the first when [noteFilled]
     *  is false, the second when it is true. Written once per engine through the same placements. */
    private fun <D> place(carrier: Carrier, noteFilled: Boolean, doc: D, group: D.(String, IntArray) -> Unit,
            field: D.(String, String, IntArray) -> Unit): D {
        doc.group("/Order", intArrayOf(1))
        if (carrier === root) {
            doc.field("/Order/Qty", "2", intArrayOf(1, 1))
            if (noteFilled) doc.field("/Order/Note", "n", intArrayOf(1, 1))
        } else for (line in 1..2) {
            doc.group("/Order/Lines", intArrayOf(1, line))
            doc.field("/Order/Lines/Qty", "2", intArrayOf(1, line, 1))
            if (noteFilled == (line == 2)) doc.field("/Order/Lines/Note", "n", intArrayOf(1, line, 1))
        }
        return doc
    }

    private fun kernelDoc(carrier: Carrier, noteFilled: Boolean): RuntimeLaws.Doc =
        place(carrier, noteFilled, RuntimeLaws.doc(), { path, reps -> group(path, *reps) },
            { path, value, reps -> field(path, value, *reps) })

    private fun interpreterDoc(carrier: Carrier, noteFilled: Boolean): Document =
        place(carrier, noteFilled, ModelLoader.load(carrier.model).newDocument(), { path, reps -> group(path, *reps) },
            { path, value, reps -> field(path, value, *reps) })

    private enum class Engine { DYNAMIC, STATIC, INTERPRETER }

    /** Rule name → calls on [engine], for one document, one answer, and a full or root-relevant partial call. */
    private fun calls(carrier: Carrier, engine: Engine, noteFilled: Boolean, answer: Boolean, partial: Boolean)
            : Map<String, Int> {
        val calls = HashMap<String, Int>()
        val kernelConditions = carrier.conditions.keys.associateWith { name ->
            object : ICustomCondition {
                override fun check(document: IDocument, relevant: Set<IEntityInstance>?,
                        formallyIncorrect: Set<IEntityInstance>, errorEntity: IEntityInstance): Boolean {
                    calls.merge(name, 1, Int::plus); return answer
                }
            } as ICustomCondition
        }
        when (engine) {
            Engine.DYNAMIC -> if (partial) RuntimeLaws.messagesPart(carrier.model, kernelDoc(carrier, noteFilled),
                setOf(RuntimeLaws.Relevant.of("/Order", 1)), kernelConditions)
            else RuntimeLaws.messages(carrier.model, kernelDoc(carrier, noteFilled), kernelConditions)
            Engine.STATIC -> {
                check(!partial) { "static Java is compared on the full pass" }
                val java = RuntimeLaws.kernelJavaEngine(carrier.model)
                java.messages(java.instance(kernelDoc(carrier, noteFilled)), kernelConditions)
            }
            Engine.INTERPRETER -> {
                val interpreter = DmInterpreter(ModelLoader.load(carrier.model),
                    customConditions = carrier.conditions.keys.associateWith { name ->
                        CustomCondition { _, _, _, _ -> calls.merge(name, 1, Int::plus); answer }
                    })
                val doc = interpreterDoc(carrier, noteFilled)
                if (partial) interpreter.validatePart(doc, setOf(RelevantEntity("/Order", listOf(1))))
                else interpreter.validate(doc)
            }
        }
        return carrier.conditions.keys.associateWith { calls[it] ?: 0 }
    }

    private val documents = listOf(false, true).flatMap { noteFilled -> listOf(true, false).map { noteFilled to it } }

    private fun grid(carrier: Carrier, engine: Engine, partial: Boolean = false) = documents.associate { (note, answer) ->
        "note=${if (note) "filled" else "empty"},answer=$answer" to calls(carrier, engine, note, answer, partial)
    }

    @Test
    fun a12CallsACallbackWhereItsTruthPassReachesItAndAgainWhenAHoldingRuleClassifies() {
        assertEquals(mapOf(
            "note=empty,answer=true" to mapOf("ExemptAnd" to 0, "EnablingAnd" to 2, "ExemptOr" to 1, "EnablingOr" to 1,
                "ValueAnd" to 2, "LeftAnd" to 2, "LeftOr" to 2),
            "note=empty,answer=false" to mapOf("ExemptAnd" to 0, "EnablingAnd" to 1, "ExemptOr" to 1, "EnablingOr" to 1,
                "ValueAnd" to 1, "LeftAnd" to 1, "LeftOr" to 2),
            "note=filled,answer=true" to mapOf("ExemptAnd" to 1, "EnablingAnd" to 0, "ExemptOr" to 0, "EnablingOr" to 2,
                "ValueAnd" to 2, "LeftAnd" to 1, "LeftOr" to 2),
            "note=filled,answer=false" to mapOf("ExemptAnd" to 1, "EnablingAnd" to 0, "ExemptOr" to 0, "EnablingOr" to 1,
                "ValueAnd" to 1, "LeftAnd" to 1, "LeftOr" to 1),
        ), grid(root, Engine.DYNAMIC))
        assertEquals(grid(root, Engine.DYNAMIC), grid(root, Engine.STATIC), "static Java")
        assertEquals(grid(root, Engine.DYNAMIC), grid(root, Engine.DYNAMIC, partial = true), "partial, root relevant")
    }

    @Test
    fun theInterpreterCallsEachCallbackAsOftenAsA12() {
        assertEquals(grid(root, Engine.DYNAMIC), grid(root, Engine.INTERPRETER), "full")
        assertEquals(grid(root, Engine.DYNAMIC, partial = true), grid(root, Engine.INTERPRETER, partial = true),
            "partial, root relevant")
    }

    /**
     * Each document holds one line with a note and one without, so per row the root's counts hold when each line
     * count is the sum of the root's two documents for the same answer.
     */
    @Test
    fun aRepeatingCarrierCountsPerRowOnEveryEngine() {
        val dynamic = grid(lines, Engine.DYNAMIC)
        val rootCounts = grid(root, Engine.DYNAMIC)
        dynamic.forEach { (document, counts) ->
            val answer = document.substringAfter("answer=")
            assertEquals(counts.keys.associateWith { rule ->
                rootCounts.getValue("note=empty,answer=$answer").getValue(rule) +
                    rootCounts.getValue("note=filled,answer=$answer").getValue(rule)
            }, counts, document)
        }
        assertEquals(dynamic, grid(lines, Engine.STATIC), "static Java")
        assertEquals(dynamic, grid(lines, Engine.INTERPRETER), "interpreter")
    }
}
