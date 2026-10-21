package io.github.mbackschat.a12.dm.adapter.perf

import io.github.mbackschat.a12.dm.interpreter.load.ModelLoader
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel
import io.github.mbackschat.a12.dm.rulekit.dsl.Condition
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl
import io.github.mbackschat.a12.dm.rulekit.dsl.Severity
import org.junit.jupiter.api.Tag
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource

/**
 * Perf gate — a row-invariant star reader in a per-row rule stays **linear** (INTERPRETER-FINDINGS IF411). Each line
 * reads a value every line reads alike: a count, the first filled amount, or whether any line has a note.
 * `rowInvariantAggregateStaysSubQuadratic` holds the same shape for `Sum`; one lane per reader here, because
 * each reaches its cells through its own path, and a reader re-read for every line costs O(lines) per line.
 *
 * Every amount is 1 and no line has a note, so no condition holds and nothing fires.
 */
@Tag("perf")
class RowInvariantStarReaderPerfTest {

    enum class Reader(val condition: (BoundModel) -> Condition) {
        NUMBER_OF_FILLED_FIELDS({ m ->
            m.num(AMOUNT).filled().and(m.num(AMOUNT).gt(Dsl.numberOfFilledFields(m.flatten("$LINES*/Amount"))))
        }),
        NUMBER_OF_FILLED_GROUPS({ m ->
            m.num(AMOUNT).filled().and(m.num(AMOUNT).gt(Dsl.numberOfFilledGroups(m.flattenGroup("$LINES*"))))
        }),
        FIRST_FILLED_VALUE({ m ->
            m.num(AMOUNT).filled().and(m.num(AMOUNT).gt(Dsl.firstFilledNumber(m.flatten("$LINES*/Amount"))))
        }),
        FILL_QUANTIFIER({ m ->
            m.num(AMOUNT).filled().and(Dsl.atLeastOneFieldFilled(m.flatten("$LINES*/Note")))
        }),
    }

    private val skeleton = DocumentModelDraft.named("quote").locale("en_US")
        .rootGroup("Quote") { q ->
            q.group("Lines") { l -> l.repeatable(MAX)
                .numberField("Amount") { it.fractionDigits(0) }
                .stringField("Note") { } }
        }.build().toJson()

    private fun scenario(reader: Reader): PerfScenario {
        val bound = BoundModel.ofJson(skeleton)
        val model = bound.addAll(
            listOf(
                bound.rule(bound.group(LINES)).name("line_reads_every_line").errorField(bound.field(AMOUNT))
                    .`when`(reader.condition(bound))
                    .code("LINE_READS_EVERY_LINE").severity(Severity.ERROR).message(Dsl.en("Check this line.")).build(),
            ),
            emptyList(),
        ).toJson()
        return PerfScenario("row-invariant-${reader.name.lowercase()}", model, ModelLoader.load(model)) { sink, n, _ ->
            sink.group("/Quote", 1)
            for (r in 1..n) {
                sink.group(LINES, 1, r)
                sink.field(AMOUNT, "1", 1, r, 1)
            }
        }
    }

    @ParameterizedTest
    @EnumSource(Reader::class)
    fun rowInvariantStarReaderScalesSubQuadratically(reader: Reader) {
        val scenario = scenario(reader)
        PerfHarness.assertSemanticAgreement(scenario, listOf(N, N * 4), withJava = true)
        PerfHarness.assertSubQuadraticLane(
            label = "row-invariant ${reader.name.lowercase().replace('_', ' ')}",
            small = N,
            identityAt = { n -> PerfHarness.measurementLabel(scenario, n) },
            culprit = "a row-invariant star reader re-read for every row instead of once per scope",
            unit = "rows",
        ) { n -> PerfHarness.timeInterpreterCpu(scenario, n, warmup = 5, iters = 7) }
    }

    private companion object {
        const val N = 1000
        const val MAX = 100_000
        const val LINES = "/Quote/Lines"
        const val AMOUNT = "$LINES/Amount"
    }
}
