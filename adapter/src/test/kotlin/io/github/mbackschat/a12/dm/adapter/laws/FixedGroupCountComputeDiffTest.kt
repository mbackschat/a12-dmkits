package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.interpreter.DmInterpreter
import io.github.mbackschat.a12.dm.interpreter.eval.newDocument
import io.github.mbackschat.a12.dm.interpreter.load.ModelLoader
import io.github.mbackschat.a12.dm.interpreter.model.ComputeOutcome
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * a12-kernel-lean **EXP-2026-08-06-01** — the COMPUTATION half of the fixed multi-group filled count. When one
 * operand group of `NumberOfFilledGroups(Alpha, Beta)` is formally unavailable and that count is the **direct**
 * expression of a **seeded** Number computed target, does the target become `VALUE|0`, `CLEARED`, or `ERRORED`?
 *
 * The validation half is already closed elsewhere (an erroneous operand group makes the plain multi-group count
 * unavailable rather than counting the admitted subset — `GroupPresenceFormalInvalidDiffTest`), and is not
 * re-measured here. The competing accounts: the general third-state rule predicts `ERRORED`, because a
 * computation reading a formally invalid cell aborts the computing instance; the entry's source audit instead
 * suggested the count may resolve an unavailable operand *before* the poison mechanism observes it, which would
 * make it a special case ahead of the general rule.
 *
 * Both controls the entry requires are present, because the three outcomes are otherwise confusable with an inert
 * fixture: an **all-clean positive** control whose target takes the exact nonzero count (without it, `CLEARED`
 * cannot be told from a target the computation never reached) and an **all-clean zero** control whose target takes
 * exact `0` (without it, `VALUE|0` cannot be told from a count that simply computed correctly). The target is
 * seeded in every case, so `CLEARED` is reachable at all — the kernel classifies a clear only when the source cell
 * was filled. Observed on **both** kernel strategies, since a codegen-versus-dynamic split would itself be the
 * finding.
 *
 * `fractionDigits(1, 1)` makes `"7"` formally invalid and `"7.0"` valid, so a group can be made malformed-only
 * without any other difference in shape.
 */
class FixedGroupCountComputeDiffTest {

    private val base = DocumentModelDraft.named("depot-fixed-group-count").locale("en_US")
        .rootGroup("Depot") { depot ->
            depot.numberField("FilledCount") { it.fractionDigits(0) }
            depot.numberField("EchoQty") { it.fractionDigits(1, 1) }
            depot.group("Alpha") { alpha -> alpha.numberField("AlphaQty") { it.fractionDigits(1, 1) } }
            depot.group("Beta") { beta -> beta.numberField("BetaQty") { it.fractionDigits(1, 1) } }
        }
        .build().toJson()

    private val bound = BoundModel.ofJson(base)

    /** The target is computed DIRECTLY from the count — no arithmetic wrapper that could resolve it first. */
    private val modelJson = listOf(
        // The PRESENCE-testing computation: the entry's shape.
        bound.computation(bound.num(COUNT)).name("FilledGroupProjection")
            .calc(Dsl.numberOfFilledGroups(bound.group(ALPHA), bound.group(BETA)).expression())
            .message(Dsl.en("Number of filled depot groups.")).build(),
        // The VALUE-READING twin over the SAME malformed cell, in the same model and the same compute run. This is
        // what turns the mechanism below from a source reading into a measurement: if presence-versus-read is what
        // decides poisoning, these two targets must part company on one document.
        bound.computation(bound.num(ECHO)).name("EchoProjection")
            .calc(bound.num(ALPHA_QTY).asNumber().expression())
            .message(Dsl.en("Echo of the alpha quantity.")).build(),
    ).fold(base) { json, computation -> TypedRules.inject(json, computation) }

    private val definition = ModelLoader.load(modelJson)
    private val javaEngine by lazy { RuntimeLaws.kernelJavaEngine(modelJson) }

    private fun outcome(path: String, value: String?, cleared: Boolean, errored: Boolean): String = when {
        cleared -> "$path|CLEARED"
        errored -> "$path|ERRORED"
        else -> "$path|VALUE|$value"
    }

    /** Compute the seeded target over one (alpha, beta) content pair on all three engines, which must agree. */
    private fun computed(alpha: String?, beta: String?): String {
        val kernelDocument = RuntimeLaws.doc().group(ROOT, 1)
            .field(COUNT, SEED, 1, 1).field(ECHO, ECHO_SEED, 1, 1)
            .group(ALPHA, 1, 1).group(BETA, 1, 1)
        if (alpha != null) kernelDocument.field(ALPHA_QTY, alpha, 1, 1, 1)
        if (beta != null) kernelDocument.field(BETA_QTY, beta, 1, 1, 1)

        val dynamic = RuntimeLaws.compute(modelJson, kernelDocument).filter { it.path() in TARGETS }
            .map { outcome(it.path(), it.value().orElse(null), it.cleared(), it.errored()) }.sorted()
        val static = javaEngine.compute(javaEngine.instance(kernelDocument)).filter { it.path() in TARGETS }
            .map { outcome(it.path(), it.value().orElse(null), it.cleared(), it.errored()) }.sorted()

        val interpreterDocument = definition.newDocument().group(ROOT, 1)
            .field(COUNT, SEED, 1, 1).field(ECHO, ECHO_SEED, 1, 1)
            .group(ALPHA, 1, 1).group(BETA, 1, 1)
        if (alpha != null) interpreterDocument.field(ALPHA_QTY, alpha, 1, 1, 1)
        if (beta != null) interpreterDocument.field(BETA_QTY, beta, 1, 1, 1)
        val interpreter = DmInterpreter(definition).compute(interpreterDocument).filter { it.path in TARGETS }.map {
            when (it.outcome) {
                ComputeOutcome.VALUE -> "${it.path}|VALUE|${it.value}"
                ComputeOutcome.CLEARED -> "${it.path}|CLEARED"
                ComputeOutcome.ERRORED -> "${it.path}|ERRORED"
            }
        }.sorted()

        assertEquals(dynamic, static, "alpha=$alpha beta=$beta — dynamic-Groovy vs generated Java")
        assertEquals(dynamic, interpreter, "alpha=$alpha beta=$beta — kernel vs interpreter")
        return dynamic.ifEmpty { listOf("(no instance)") }.joinToString(",")
    }

    /**
     * THE CONTROL THAT DECIDES WHETHER THE FIXTURE IS INERT. The compute row below reports the same channel for a
     * malformed operand group as for a clean one, which is also exactly what a fixture whose "malformed" value was
     * silently valid would report. So the same document is validated first: `"7"` under `fractionDigits(1, 1)` must
     * draw a NUMBER formal error, proving the cell really is formally unavailable. Without this the compute finding
     * would be worthless.
     */
    @Test fun theMalformedOperandIsGenuinelyFormallyInvalidInTheSameDocument() {
        val kernelDocument = RuntimeLaws.doc().group(ROOT, 1)
            .field(COUNT, SEED, 1, 1).field(ECHO, ECHO_SEED, 1, 1)
            .group(ALPHA, 1, 1).group(BETA, 1, 1)
            .field(ALPHA_QTY, "7", 1, 1, 1)
            .field(BETA_QTY, "8.0", 1, 1, 1)
        val formals = RuntimeLaws.messages(modelJson, kernelDocument)
            .map { it.code }.filter { it.startsWith("zahl") }.sorted()
        assertEquals(listOf("zahlOhneDezimalTrenner"), formals, "the malformed operand cell must be formally invalid")
    }

    @Test fun anUnavailableOperandGroupDecidesTheSeededTargetsChannel() {
        assertEquals(
            EXPECTED,
            listOf(
                "malformed alpha, clean beta => ${computed("7", "8.0")}",
                "all clean, both filled    => ${computed("7.0", "8.0")}",
                "all clean, both empty     => ${computed(null, null)}",
            ),
            "the seeded target's channel as BOTH kernel strategies report it",
        )
    }

    private companion object {
        val EXPECTED = listOf(
            "malformed alpha, clean beta => /Depot/EchoQty|CLEARED,/Depot/FilledCount|VALUE|2",
            "all clean, both filled    => /Depot/EchoQty|VALUE|7.0,/Depot/FilledCount|VALUE|2",
            "all clean, both empty     => /Depot/EchoQty|VALUE|0.0,/Depot/FilledCount|VALUE|0",
        )
        const val ROOT = "/Depot"
        const val COUNT = "/Depot/FilledCount"
        const val ALPHA = "/Depot/Alpha"
        const val BETA = "/Depot/Beta"
        const val ECHO = "/Depot/EchoQty"
        val TARGETS = setOf(COUNT, ECHO)
        const val ALPHA_QTY = "/Depot/Alpha/AlphaQty"
        const val BETA_QTY = "/Depot/Beta/BetaQty"
        const val SEED = "9"
        const val ECHO_SEED = "1.0"
    }
}
