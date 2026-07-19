package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.interpreter.DmInterpreter
import io.github.mbackschat.a12.dm.interpreter.eval.newDocument
import io.github.mbackschat.a12.dm.interpreter.load.ModelLoader
import io.github.mbackschat.a12.dm.interpreter.model.ComputeOutcome
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft.GroupBuilder
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.util.function.Consumer

/**
 * Differential — a COMPUTE-mode BARE-GROUP fill quantifier scans the group's contained fields in DECLARATION
 * order with per-operator early-stop, so a later formally-invalid field after a decisive earlier one is NEVER
 * read and does NOT poison (IF147, graduated from IG93). Probe-confirmed: the kernel (groovy-dynamic AND
 * static-Java) early-stops; the interpreter's former eager whole-group tally over-poisoned.
 *
 * The poison is observed transitively (the ComputePoisonReadDiffTest pattern): FlagComp := 1 when
 * NoFieldFilled(Details); Flag2Comp := 1 when FieldNotFilled(Flag). NoFieldFilled(Details) is FALSE (First is
 * filled). If the group scan early-stops at First (Second never read) → Flag is a PLAIN clear → FieldNotFilled
 * reads it EMPTY → Flag2 = 1. If the scan reads a malformed Second → Flag is POISONED → Flag2 = CLEARED. So the
 * declaration ORDER of the decisive vs malformed field decides the outcome. Own domain (an insurance form).
 * Tri-check: groovy-dynamic == static-Java == interpreter.
 */
class BareGroupComputePoisonDiffTest {

    /** Cart{ Flag, Flag2, Details{ First(string), Second(number scale-0) } } — [malformedFirst] swaps the
     *  declaration order of First and Second. FlagComp(NoFieldFilled(Details)) then Flag2Comp(FieldNotFilled(Flag)). */
    private fun model(malformedFirst: Boolean): String {
        val base = DocumentModelDraft.named("form").locale("en_US")
            .rootGroup("Cart", Consumer { g: GroupBuilder ->
                g.numberField("Flag", Consumer { })
                g.numberField("Flag2", Consumer { })
                g.group("Details", Consumer { d: GroupBuilder ->
                    if (malformedFirst) {
                        d.numberField("Second", Consumer { })   // malformed, declared FIRST → read before the decisive First
                        d.stringField("First", Consumer { })
                    } else {
                        d.stringField("First", Consumer { })     // decisive filled, declared FIRST → early-stop before Second
                        d.numberField("Second", Consumer { })
                    }
                })
            }).build().toJson()
        var m = RuntimeLaws.withTieredComputation(base, "/Cart", "FlagComp", "../Flag", null,
            listOf(RuntimeLaws.CompAlt("NoFieldFilled(Details)", "1")))
        m = RuntimeLaws.withTieredComputation(m, "/Cart", "Flag2Comp", "../Flag2", null,
            listOf(RuntimeLaws.CompAlt("FieldNotFilled(Flag)", "1")))
        return m
    }

    /** Assert groovy-dynamic == static-Java == interpreter on Flag2's outcome for the given order + Second value. */
    private fun agree(malformedFirst: Boolean, second: String, expectFlag2: String, msg: String) {
        val kModel = model(malformedFirst)
        val def = ModelLoader.load(kModel)
        fun fill(set: (String, String, IntArray) -> Unit) {
            set("/Cart/Flag", "9", intArrayOf(1, 1))       // seed the computed targets so a kernel clear is reported
            set("/Cart/Flag2", "9", intArrayOf(1, 1))
            set("/Cart/Details/First", "x", intArrayOf(1, 1, 1))
            if (second.isNotEmpty()) set("/Cart/Details/Second", second, intArrayOf(1, 1, 1))
        }

        val kDoc = RuntimeLaws.doc(); kDoc.group("/Cart", 1); kDoc.group("/Cart/Details", 1, 1)
        fill { p, v, r -> kDoc.field(p, v, *r) }
        val groovy = RuntimeLaws.compute(kModel, kDoc).first { it.path() == "/Cart/Flag2" }
            .let { if (it.cleared()) "CLEARED" else if (it.errored()) "ERRORED" else "VALUE|${it.value().orElse("")}" }

        val engine = RuntimeLaws.kernelJavaEngine(kModel)
        val kDoc2 = RuntimeLaws.doc(); kDoc2.group("/Cart", 1); kDoc2.group("/Cart/Details", 1, 1)
        fill { p, v, r -> kDoc2.field(p, v, *r) }
        val java = engine.computeOutcomesSorted(engine.instance(kDoc2)).first { it.startsWith("/Cart/Flag2|") }
            .substringAfter('|').let { if (it == "ERR") "ERRORED" else if (it == "CLEARED") "CLEARED" else "VALUE|$it" }

        val iDoc = def.newDocument(); iDoc.group("/Cart", 1); iDoc.group("/Cart/Details", 1, 1)
        fill { p, v, r -> iDoc.field(p, v, *r) }
        val interp = DmInterpreter(def).compute(iDoc).first { it.path == "/Cart/Flag2" }
            .let { when (it.outcome) { ComputeOutcome.CLEARED -> "CLEARED"; ComputeOutcome.ERRORED -> "ERRORED"; ComputeOutcome.VALUE -> "VALUE|${it.value}" } }

        assertEquals(expectFlag2, groovy, "$msg — the kernel groovy-dynamic outcome")
        assertEquals(groovy, java, "$msg — groovy vs static-Java (tri-check)")
        assertEquals(groovy, interp, "$msg — kernel vs interpreter")
    }

    @Test fun decisiveFilledFieldBeforeAMalformedOneEarlyStops() =
        agree(malformedFirst = false, second = "7.5", expectFlag2 = "VALUE|1",
            "First is filled and declared first → NoFieldFilled decides FALSE at First; the later malformed Second is never read → plain clear")

    @Test fun aMalformedFieldBeforeTheDecisiveOneIsReadAndPoisons() =
        agree(malformedFirst = true, second = "7.5", expectFlag2 = "CLEARED",
            "the malformed Second is declared first → read before the decisive First → poison")

    @Test fun aCleanGroupComputesRegardlessOfOrder() {
        agree(malformedFirst = false, second = "", expectFlag2 = "VALUE|1", "no malformed cell → plain clear")
        agree(malformedFirst = true, second = "3", expectFlag2 = "VALUE|1", "a valid Second never poisons")
    }
}
