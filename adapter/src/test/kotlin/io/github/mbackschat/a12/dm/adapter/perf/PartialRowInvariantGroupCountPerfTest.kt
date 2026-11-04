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
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource

/**
 * Perf gate — under PARTIAL validation, a per-row rule reading a group count over its own group stays **linear**
 * (INTERPRETER-FINDINGS IF411). Full validation folds such a reader once per scope ([RowInvariantStarReaderPerfTest]);
 * the two readers that ask the group-count gate share that memo under partial validation too, keyed by the gate's
 * deciding prefix as well, since without it each row re-folds the whole group. The relevant set wildcards every
 * policy, so the gate admits the count and the fold runs.
 *
 * Every limit is 1 and the count is N, so no condition holds and nothing fires.
 */
@Tag("perf")
class PartialRowInvariantGroupCountPerfTest {

    enum class Reader(val count: (BoundModel) -> Condition) {
        NUMBER_OF_FILLED_GROUPS({ m -> m.num(LIMIT).gt(Dsl.numberOfFilledGroups(m.flattenGroup("$POLICIES*"))) }),
        SUM_OF_TERMS({ m ->
            m.num(LIMIT).gt(Dsl.sumOfTerms(m.flattenGroup("$POLICIES*"), m.groupAt(POLICIES).orElseThrow().num("Limit").asNumber()))
        }),
    }

    private val skeleton = DocumentModelDraft.named("policy-book").locale("en_US")
        .rootGroup("Book") { b ->
            b.group("Policies") { p -> p.repeatable(MAX).numberField("Limit") { it.fractionDigits(0) } }
        }.build().toJson()

    private fun model(reader: Reader) = BoundModel.ofJson(skeleton).let { bound ->
        ModelLoader.load(bound.addAll(
            listOf(
                bound.rule(bound.group(POLICIES)).name("policy_counts_every_policy").errorField(bound.field(LIMIT))
                    .`when`(bound.num(LIMIT).filled().and(reader.count(bound)))
                    .code("POLICY_COUNTS").severity(Severity.ERROR).message(Dsl.en("Check the policy.")).build(),
            ),
            emptyList(),
        ).toJson())
    }

    private fun document(reader: Reader, n: Int): Document = model(reader).newDocument().also { doc ->
        doc.group("/Book", 1)
        for (p in 1..n) {
            doc.group(POLICIES, 1, p)
            doc.field(LIMIT, "1", 1, p, 1)
        }
    }

    @ParameterizedTest
    @EnumSource(Reader::class)
    fun partialRowInvariantGroupCountScalesSubQuadratically(reader: Reader) {
        val every = setOf(RelevantEntity(POLICIES, listOf(1, RelevantEntity.ALL)))
        val prepared = listOf(N, N * 4).associateWith { n -> DmInterpreter(model(reader)) to document(reader, n) }
        prepared.values.forEach { (interpreter, doc) ->
            assertEquals(0, interpreter.validatePart(doc, every).messages.size, "nothing fires: every limit is below the count")
        }
        PerfHarness.assertSubQuadraticLane(
            label = "partial row-invariant ${reader.name.lowercase().replace('_', ' ')}",
            small = N,
            identityAt = { n ->
                PerfHarness.fingerprintedLabel(
                    "partial-row-invariant-${reader.name.lowercase()}",
                    "policies=$n|relevant=every-policy-wildcard|limit=1|reader=${reader.name}",
                )
            },
            culprit = "a gated group-count reader re-folded for every row instead of once per scope",
            unit = "rows",
        ) { n ->
            val (interpreter, doc) = prepared.getValue(n)
            PerfHarness.measureCurrentThreadCpu(warmup = 3, iters = 7) { interpreter.validatePart(doc, every) }
        }
    }

    private companion object {
        const val N = 1000
        const val MAX = 100_000
        const val POLICIES = "/Book/Policies"
        const val LIMIT = "$POLICIES/Limit"
    }
}
