package io.github.mbackschat.a12.dm.interpreter

import io.github.mbackschat.a12.dm.interpreter.ast.Cond
import io.github.mbackschat.a12.dm.interpreter.eval.EvalConfig
import io.github.mbackschat.a12.dm.interpreter.eval.Interpreter
import io.github.mbackschat.a12.dm.interpreter.eval.newDocument
import io.github.mbackschat.a12.dm.interpreter.model.CustomCondition
import io.github.mbackschat.a12.dm.interpreter.model.FieldDef
import io.github.mbackschat.a12.dm.interpreter.model.FieldPointer
import io.github.mbackschat.a12.dm.interpreter.model.Kind
import io.github.mbackschat.a12.dm.interpreter.model.ModelDef
import io.github.mbackschat.a12.dm.interpreter.model.Requiredness
import io.github.mbackschat.a12.dm.interpreter.model.RuleDef
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Multiplatform lock for the callback inputs and structural empty-row eligibility established by
 * `CustomConditionInvocationDiffTest` against both kernel strategies.
 */
class CustomConditionInvocationTest {

    @Test
    fun callbackReceivesExactFormalAddressesAndCurrentRepeatedErrorPointer() {
        val model = ModelDef(
            fields = listOf(
                FieldDef(REQUIRED, Kind.STRING, required = Requiredness.ABSOLUTE),
                FieldDef(CHECK_ERROR, Kind.STRING),
                FieldDef(SCORE, Kind.NUMBER, scale = 0),
            ),
            rules = listOf(
                RuleDef(
                    "$CHECKS/ExactInputs",
                    CHECKS,
                    "FieldFilled(Error) And CustomCondition ExactInputs",
                    EXACT_CODE,
                    "../Error",
                ),
            ),
            repeatableGroups = setOf(CHECKS),
            declaredReps = mapOf(CHECKS to 3),
        )
        val expectedFormal = setOf(
            FieldPointer(REQUIRED, listOf(1, 1)),
            FieldPointer(SCORE, listOf(1, 2, 1)),
        )
        val expectedError = FieldPointer(CHECK_ERROR, listOf(1, 2, 1))
        val callback = CustomCondition { _, relevant, formallyIncorrect, errorField ->
            relevant == null && formallyIncorrect == expectedFormal && errorField == expectedError
        }
        val document = model.newDocument()
            .group(CLAIM, 1)
            .emptyField(REQUIRED, 1, 1)
            .group(CHECKS, 1, 1)
            .field(CHECK_ERROR, "first", 1, 1, 1)
            .field(SCORE, "10", 1, 1, 1)
            .group(CHECKS, 1, 2)
            .field(CHECK_ERROR, "second", 1, 2, 1)
            .field(SCORE, "bad", 1, 2, 1)

        val fired = DmInterpreter(model, customConditions = mapOf("ExactInputs" to callback))
            .validate(document).messages.filter { it.code == EXACT_CODE }.map { it.errorPath }

        assertEquals(listOf("/Claim[1]/Checks[2]/Error"), fired)
    }

    @Test
    fun blankRowCustomOrFiresWhileGuardedAndStaysSilent() {
        val model = ModelDef(
            fields = listOf(FieldDef(ROOT_ERROR, Kind.STRING)),
            rules = listOf(
                RuleDef(
                    "$CLAIM/BlankOr",
                    CLAIM,
                    "CustomCondition Always Or FieldFilled(Error)",
                    OR_CODE,
                    "../Error",
                ),
                RuleDef(
                    "$CLAIM/BlankAnd",
                    CLAIM,
                    "FieldFilled(Error) And CustomCondition Always",
                    AND_CODE,
                    "../Error",
                ),
            ),
        )
        val always = CustomCondition { _, _, _, _ -> true }

        val fired = DmInterpreter(model, customConditions = mapOf("Always" to always))
            .validate(model.newDocument()).messages.map { it.code }

        assertEquals(listOf(OR_CODE), fired)
    }

    @Test
    fun directLowLevelInterpreterRetainsItsBestEffortFormalSet() {
        val model = ModelDef(
            fields = listOf(FieldDef(ROOT_ERROR, Kind.STRING)),
            rules = emptyList(),
        )
        val document = model.newDocument().field(ROOT_ERROR, "bad", 1, 1)
        val error = FieldPointer(ROOT_ERROR, listOf(1, 1))
        val callback = CustomCondition { _, _, formallyIncorrect, _ ->
            formallyIncorrect == setOf(error)
        }

        val fires = Interpreter(
            document.onceContextFor(CLAIM).single(),
            EvalConfig(customConditions = mapOf("Exact" to callback)),
            error,
            invalid = { it == ROOT_ERROR },
        ).fires(Cond.Custom("Exact"))

        assertEquals(true, fires)
    }

    private companion object {
        const val CLAIM = "/Claim"
        const val ROOT_ERROR = "/Claim/Error"
        const val REQUIRED = "/Claim/RequiredCode"
        const val CHECKS = "/Claim/Checks"
        const val CHECK_ERROR = "/Claim/Checks/Error"
        const val SCORE = "/Claim/Checks/Score"
        const val EXACT_CODE = "CUSTOM_INPUTS"
        const val OR_CODE = "CUSTOM_OR"
        const val AND_CODE = "CUSTOM_AND"
    }
}
