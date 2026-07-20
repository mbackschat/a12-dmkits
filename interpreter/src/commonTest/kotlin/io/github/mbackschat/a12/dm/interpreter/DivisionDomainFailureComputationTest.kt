package io.github.mbackschat.a12.dm.interpreter

import io.github.mbackschat.a12.dm.interpreter.eval.newDocument
import io.github.mbackschat.a12.dm.interpreter.eval.EvalConfig
import io.github.mbackschat.a12.dm.interpreter.eval.Interpreter
import io.github.mbackschat.a12.dm.interpreter.eval.PoisonedReadException
import io.github.mbackschat.a12.dm.interpreter.eval.SingleRowDoc
import io.github.mbackschat.a12.dm.interpreter.model.ComputationDef
import io.github.mbackschat.a12.dm.interpreter.model.ComputeOutcome
import io.github.mbackschat.a12.dm.interpreter.model.FieldDef
import io.github.mbackschat.a12.dm.interpreter.model.Kind
import io.github.mbackschat.a12.dm.interpreter.model.ModelDef
import io.github.mbackschat.a12.dm.interpreter.model.RuleDef
import io.github.mbackschat.a12.dm.interpreter.parse.parseExpression
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Multiplatform lock for the consumer-sensitive division-domain failure. */
class DivisionDomainFailureComputationTest {

    private val model = ModelDef(
        fields = listOf(
            FieldDef(NUMERATOR, Kind.NUMBER, scale = 0),
            FieldDef(DIVISOR, Kind.NUMBER, scale = 0),
            FieldDef(RESULT, Kind.NUMBER, scale = 2),
            FieldDef(DEPENDENT, Kind.NUMBER, scale = 2),
        ),
        rules = listOf(RuleDef("$ROOT/NoZeroDivision", ROOT,
            "FieldFilled(Numerator) And [Numerator] / [Divisor] > 0", "DIV_RULE", "../Numerator")),
        computations = listOf(
            ComputationDef(RESULT, ROOT, "RoundAccounting([Numerator] / [Divisor], 2)"),
            ComputationDef(DEPENDENT, ROOT, "RoundAccounting([Result] + 1, 2)"),
        ),
    )

    @Test
    fun comparisonDoesNotFireButComputationTargetAndDependentBothClear() {
        val doc = model.newDocument().group(ROOT, 1)
            .field(NUMERATOR, "10", 1, 1)
            .field(DIVISOR, "0", 1, 1)
            .field(RESULT, "9.00", 1, 1)
            .field(DEPENDENT, "8.00", 1, 1)
        val interpreter = DmInterpreter(model)

        assertTrue(interpreter.validateFull(doc).none { it.code == "DIV_RULE" })
        assertEquals(
            mapOf(RESULT to ComputeOutcome.CLEARED, DEPENDENT to ComputeOutcome.CLEARED),
            interpreter.compute(doc).associate { it.path to it.outcome },
        )
    }

    @Test
    fun numericWrappersCannotEraseTheDomainFailure() {
        val operations = listOf(
            "RoundAccounting(Abs([Numerator] / [Divisor]), 2)",
            "RoundAccounting(Min({[Numerator] / [Divisor]}, 3), 2)",
            "RoundAccounting(Max(3, {[Numerator] / [Divisor]}), 2)",
        )

        for (operation in operations) {
            val wrapperModel = model.copy(
                rules = emptyList(),
                computations = listOf(ComputationDef(RESULT, ROOT, operation)),
            )
            val doc = wrapperModel.newDocument().group(ROOT, 1)
                .field(NUMERATOR, "10", 1, 1)
                .field(DIVISOR, "0", 1, 1)
                .field(RESULT, "9.00", 1, 1)

            assertEquals(
                ComputeOutcome.CLEARED,
                DmInterpreter(wrapperModel).compute(doc).single { it.path == RESULT }.outcome,
                operation,
            )
        }
    }

    @Test
    fun aDomainFailedLeftSubtreeStillReadsAndSelectsThePoisonedRightOperand() {
        assertSelectedPoison(
            "RoundAccounting({1 / 0} + [Later], 2)",
            values = mapOf(LATER to "1"),
            expectedPath = LATER,
        )
    }

    @Test
    fun onePassLoweringSelectsTheLaterAuthoredOperandAsTheFirstPoisonedRead() {
        // The kernel's one-pass rewrite lowers `{Source / 2} * Later` to
        // `(Later * Source) / 2`; the observer must therefore see Later before Source.
        assertSelectedPoison(
            "RoundAccounting({[Source] / 2} * [Later], 2)",
            values = mapOf(SOURCE to "1", LATER to "2"),
            expectedPath = LATER,
        )
    }

    private fun assertSelectedPoison(operation: String, values: Map<String, String>, expectedPath: String) {
        val observed = mutableListOf<String>()
        val kinds = values.keys.associateWith { Kind.NUMBER }
        val interpreter = Interpreter(
            SingleRowDoc(ROOT, values, kinds),
            EvalConfig(computeMode = true),
            invalid = { path ->
                observed += path
                throw PoisonedReadException(path)
            },
        )

        val poison = assertFailsWith<PoisonedReadException> {
            interpreter.evalExpression(parseExpression(operation, ROOT))
        }
        assertEquals(expectedPath, poison.path)
        assertEquals(listOf(expectedPath), observed, "the selected poison is the first and only observed invalid read")
    }
}

private const val ROOT = "/Calc"
private const val NUMERATOR = "/Calc/Numerator"
private const val DIVISOR = "/Calc/Divisor"
private const val SOURCE = "/Calc/Source"
private const val LATER = "/Calc/Later"
private const val RESULT = "/Calc/Result"
private const val DEPENDENT = "/Calc/Dependent"
