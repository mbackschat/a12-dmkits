package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.interpreter.DmInterpreter
import io.github.mbackschat.a12.dm.interpreter.eval.newDocument
import io.github.mbackschat.a12.dm.interpreter.load.ModelLoader
import io.github.mbackschat.a12.dm.interpreter.model.ComputeOutcome
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl
import io.github.mbackschat.a12.dm.rulekit.dsl.NumberLiteral
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * SPEC-2026-07-20-12 discriminator for the observable fresh second scan of
 * `FieldsNotCollectivelyFilled`: `[empty, filled, invalid]` lets the restarted declared-range scan find the
 * earlier empty before the invalid tail. Continuing after the filled witness would read the invalid cell and
 * poison. The complete fixture is authored through modelkit/rulekit and tri-checked.
 */
class ComputationFillQuantifierRestartDiffTest {

    @Test
    fun collectivelyFilledSecondStageRestartsBeforeTheFilledWitness() {
        val base = DocumentModelDraft.named("inspection").locale("en_US")
            .rootGroup("Inspection") { inspection ->
                inspection.numberField("Flag") { it.fractionDigits(0) }
                inspection.group("Checklist") { checklist ->
                    checklist.numberField("EarlierEmpty") { it.fractionDigits(0) }
                    checklist.numberField("Witness") { it.fractionDigits(0) }
                    checklist.numberField("InvalidTail") { it.fractionDigits(0) }
                }
            }
            .build()
            .toJson()
        val model = BoundModel.ofJson(base)
        val condition = Dsl.fieldsNotCollectivelyFilled(model.flattenGroup(CHECKLIST))
        val computation = model.computation(model.num(FLAG)).name("FlagFromChecklist")
            .`when`(condition)
            .calc(NumberLiteral.of("1"))
            .message(Dsl.en("Flag a partially completed checklist."))
            .build()
        val modelJson = TypedRules.inject(base, computation)
        val definition = ModelLoader.load(modelJson)

        fun populate(
            group: (String, IntArray) -> Unit,
            field: (String, String, IntArray) -> Unit,
        ) {
            group(ROOT, intArrayOf(1))
            group(CHECKLIST, intArrayOf(1, 1))
            field(FLAG, "9", intArrayOf(1, 1))
            field(WITNESS, "1", intArrayOf(1, 1, 1))
            field(INVALID_TAIL, "7.5", intArrayOf(1, 1, 1))
        }

        val dynamicDocument = RuntimeLaws.doc()
        populate(
            { path, reps -> dynamicDocument.group(path, *reps) },
            { path, value, reps -> dynamicDocument.field(path, value, *reps) },
        )
        val dynamic = RuntimeLaws.compute(modelJson, dynamicDocument)
            .single { it.path() == FLAG }
            .let { if (it.cleared()) "CLEARED" else if (it.errored()) "ERRORED" else "VALUE|${it.value().orElse("")}" }

        val javaEngine = RuntimeLaws.kernelJavaEngine(modelJson)
        val javaDocument = RuntimeLaws.doc()
        populate(
            { path, reps -> javaDocument.group(path, *reps) },
            { path, value, reps -> javaDocument.field(path, value, *reps) },
        )
        val generated = javaEngine.computeOutcomesSorted(javaEngine.instance(javaDocument))
            .single { it.startsWith("$FLAG|") }
            .substringAfter('|')
            .let { if (it == "ERR") "ERRORED" else if (it == "CLEARED") "CLEARED" else "VALUE|$it" }

        val interpretedDocument = definition.newDocument()
        populate(
            { path, reps -> interpretedDocument.group(path, *reps) },
            { path, value, reps -> interpretedDocument.field(path, value, *reps) },
        )
        val interpreted = DmInterpreter(definition).compute(interpretedDocument)
            .single { it.path == FLAG }
            .let {
                when (it.outcome) {
                    ComputeOutcome.CLEARED -> "CLEARED"
                    ComputeOutcome.ERRORED -> "ERRORED"
                    ComputeOutcome.VALUE -> "VALUE|${it.value}"
                }
            }

        assertEquals("VALUE|1", dynamic, "normative Groovy-dynamic restart")
        assertEquals(dynamic, generated, "generated Java restart")
        assertEquals(dynamic, interpreted, "interpreter restart")
    }

    private companion object {
        const val ROOT = "/Inspection"
        const val CHECKLIST = "$ROOT/Checklist"
        const val FLAG = "$ROOT/Flag"
        const val WITNESS = "$CHECKLIST/Witness"
        const val INVALID_TAIL = "$CHECKLIST/InvalidTail"
    }
}
