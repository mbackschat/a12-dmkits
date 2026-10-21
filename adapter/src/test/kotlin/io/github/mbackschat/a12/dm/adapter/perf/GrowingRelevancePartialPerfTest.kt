package io.github.mbackschat.a12.dm.adapter.perf

import io.github.mbackschat.a12.dm.interpreter.DmInterpreter
import io.github.mbackschat.a12.dm.interpreter.eval.Document
import io.github.mbackschat.a12.dm.interpreter.eval.newDocument
import io.github.mbackschat.a12.dm.interpreter.load.ModelLoader
import io.github.mbackschat.a12.dm.interpreter.model.RelevantEntity
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel
import io.github.mbackschat.a12.dm.rulekit.dsl.Condition
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl
import io.github.mbackschat.a12.dm.rulekit.dsl.Severity
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

/**
 * Perf gate — partial validation with a per-row rule stays **linear** when the relevant set names every row
 * (INTERPRETER-FINDINGS IF412). The existing partial lanes either ask one root-level rule (`PartialSumOfProductsPerfTest`)
 * or grow the PATTERNS while the document stays fixed (`StarRelevanceReductionPerfTest`). Here the document and the
 * relevant set grow together: N policies, each relevant by its own row. Each rule reads its own policy's claims
 * through one of the partial gates, which each decide a star's extent per row: a sum, a value list, `SumOfProducts`
 * and a group count. The relevance of each group instance is decided against the relevant set too, and a decision
 * that scans the whole set per row costs O(N) per row. One policy leaves its required limit empty, which is what
 * makes the interpreter decide group relevance at all.
 *
 * Interpreter only, like `StarRelevanceReductionPerfTest`: the Kernel's partial entry point is package-private to
 * the `laws` package, and this lane asserts the interpreter's own growth, not a cross-engine ratio.
 */
@Tag("perf")
class GrowingRelevancePartialPerfTest {

    private val skeleton = DocumentModelDraft.named("policy-book").locale("en_US")
        .rootGroup("Book") { b ->
            b.group("Policies") { p -> p.repeatable(MAX)
                .numberField("Limit") { it.fractionDigits(0).required() }
                .group("Claims") { c -> c.repeatable(10)
                    .numberField("Amount") { it.fractionDigits(0) }
                    .stringField("Status") { } } }
        }.build().toJson()

    private val bound = BoundModel.ofJson(skeleton)
    private val limit = bound.num(LIMIT)
    private val amounts = bound.flatten("$CLAIMS*/Amount")

    private fun rule(code: String, condition: Condition) =
        bound.rule(bound.group(POLICIES)).name("${code.lowercase()}_rule").errorField(bound.field(LIMIT))
            .`when`(limit.filled().and(condition))
            .code(code).severity(Severity.ERROR).message(Dsl.en("Check the policy's claims.")).build()

    // Every claim is 1 and open, and each limit is 100, so no condition holds.
    private val model = ModelLoader.load(bound.addAll(
        listOf(
            rule("CLAIMS_ABOVE_LIMIT", Dsl.sum(amounts).gt(limit)),
            rule("CLAIM_REJECTED", Dsl.atLeastOneFieldValueIncludedInValueList(listOf("rejected"), bound.flatten("$CLAIMS*/Status"))),
            rule("SQUARED_CLAIMS_ABOVE_LIMIT", Dsl.sumOfProducts(amounts, amounts).gt(limit)),
            rule("CLAIM_COUNT_ABOVE_LIMIT", Dsl.numberOfFilledGroups(bound.flattenGroup("$CLAIMS*")).gt(limit)),
        ),
        emptyList(),
    ).toJson())

    private fun document(n: Int): Document = model.newDocument().also { doc ->
        doc.group("/Book", 1)
        for (p in 1..n) {
            doc.group(POLICIES, 1, p)
            if (p != 1) doc.field(LIMIT, "100", 1, p, 1)
            for (c in 1..CLAIMS_PER_POLICY) {
                doc.group(CLAIMS, 1, p, c)
                doc.field(AMOUNT, "1", 1, p, c, 1)
                doc.field("$CLAIMS/Status", "open", 1, p, c, 1)
            }
        }
    }

    private fun everyPolicy(n: Int): Set<RelevantEntity> =
        (1..n).mapTo(hashSetOf()) { p -> RelevantEntity(POLICIES, listOf(RelevantEntity.ALL, p)) }

    @Test
    fun partialValidationScalesSubQuadraticallyInTheRelevantRows() {
        val prepared = listOf(N, N * 4).associateWith { n -> Triple(DmInterpreter(model), document(n), everyPolicy(n)) }
        prepared.values.forEach { (interpreter, doc, relevant) ->
            // The empty limit is reported, and nothing else fires: the lane measures the relevance decisions.
            assertEquals(1, interpreter.validatePart(doc, relevant).messages.size, "only the empty required limit draws a message")
        }
        PerfHarness.assertSubQuadraticLane(
            label = "growing-relevance partial validation",
            small = N,
            identityAt = { n ->
                PerfHarness.fingerprintedLabel(
                    "growing-relevance-partial",
                    "policies=$n|claims=$CLAIMS_PER_POLICY|relevant=every-policy-row|limit-empty-at=1|gates=sum,value-list,sum-of-products,group-count",
                )
            },
            culprit = "a relevance decision scanning the whole relevant set for every row or group instance",
            unit = "relevant rows",
        ) { n ->
            val (interpreter, doc, relevant) = prepared.getValue(n)
            PerfHarness.measureCurrentThreadCpu(warmup = 3, iters = 7) { interpreter.validatePart(doc, relevant) }
        }
    }

    private companion object {
        const val N = 1000
        const val MAX = 100_000
        const val CLAIMS_PER_POLICY = 3
        const val POLICIES = "/Book/Policies"
        const val LIMIT = "$POLICIES/Limit"
        const val CLAIMS = "$POLICIES/Claims"
        const val AMOUNT = "$CLAIMS/Amount"
    }
}
