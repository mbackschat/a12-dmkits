package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.edit.EditSession
import io.github.mbackschat.a12.dm.interpreter.DmInterpreter
import io.github.mbackschat.a12.dm.interpreter.eval.newDocument
import io.github.mbackschat.a12.dm.interpreter.load.ModelLoader
import io.github.mbackschat.a12.dm.interpreter.model.ComputeOutcome
import io.github.mbackschat.a12.dm.modelkit.FieldKind
import io.github.mbackschat.a12.dm.rulekit.dsl.Condition
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl
import io.github.mbackschat.a12.dm.rulekit.dsl.FieldRef
import io.github.mbackschat.a12.dm.rulekit.dsl.Rule
import io.github.mbackschat.a12.dm.rulekit.dsl.Severity
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Kernel differential for IF229's former reachability claim that evaluation reads stored boolean tokens
 * case-insensitively. The lower-level evaluator contains a case-insensitive text comparison, but that is not
 * evidence that a malformed stored token can reach it through a complete validation or computation run.
 *
 * This test attacks all three plausible links with one typed model: a direct boolean comparison, the
 * `NumberOfValueInFields(True In …)` aggregate, and a bare boolean-copy computation. In both kernel codegen
 * strategies, the formal token check wins first: the two rules become unknown/suppressed and the computation
 * clears its stale target while reporting the operand's formal error. The interpreter must match. Therefore the
 * case-insensitive branch is not a measured full-engine behavior and IF229 records it only as a source-level
 * implementation detail.
 */
class BooleanTokenEvaluationReachabilityDiffTest {

    private val edit = EditSession.read(RuntimeLaws.model("models/order.dm.json"))
    private val order = edit.group(ROOT)
        .addField("PeerFlag", FieldKind.BOOLEAN)
        .addField("ProjectedFlag", FieldKind.BOOLEAN)
    private val primary = order.bool("ExpressShipping")
    private val peer = order.bool("PeerFlag")
    private val projected = order.bool("ProjectedFlag")

    private val direct = rule(
        name = "DirectTrue",
        code = DIRECT,
        errorField = primary.field(),
        condition = primary.isTrue(),
    )
    private val aggregate = rule(
        name = "AggregateTrue",
        code = AGGREGATE,
        errorField = peer.field(),
        condition = peer.filled().and(
            Dsl.numberOfValueInFields(true, primary.field(), peer.field()).ge(1),
        ),
    )
    private val computation = order.computation(projected.field()) {
        it.name("ProjectFlag")
            .calc(primary.field().value())
            .message(Dsl.en("Project the primary flag."))
            .build()
    }
    private val modelJson = TypedRules.inject(
        TypedRules.inject(TypedRules.inject(edit.toJson(), direct), aggregate),
        computation,
    )
    private val definition = ModelLoader.load(modelJson)
    private val staticKernel = RuntimeLaws.kernelJavaEngine(modelJson)

    @Test
    fun formalInvalidityPrecedesCaseInsensitiveEvaluationInTheMeasuredRoutes() {
        for (token in listOf("TRUE", "True")) {
            val dynamicCodes = RuntimeLaws.firedCodes(modelJson, kernelDocument(token)).sorted()
            val staticCodes = staticKernel.firedCodesSorted(staticKernel.instance(kernelDocument(token)))
            val interpretedCodes = DmInterpreter(definition).validateFull(interpreterDocument(token))
                .map { it.code }.sorted()

            assertEquals(listOf("feldJaNeinFalsch"), dynamicCodes, "dynamic-Groovy validation for $token")
            assertEquals(dynamicCodes, staticCodes, "static-Java validation for $token")
            assertEquals(dynamicCodes, interpretedCodes, "interpreter validation for $token")

            val dynamicDetail = computationDetail(RuntimeLaws.computeWithFormalErrors(modelJson, kernelDocument(token)))
            val staticDetail = computationDetail(
                staticKernel.computeWithFormalErrors(staticKernel.instance(kernelDocument(token))),
            )
            val interpreted = DmInterpreter(definition).compute(interpreterDocument(token))
                .filter { it.path == PROJECTED }
                .map { "${it.path}|${it.outcome}|${it.value.orEmpty()}" }

            assertEquals(
                listOf("$PROJECTED|CLEARED|") to listOf("feldJaNeinFalsch"),
                dynamicDetail,
                "dynamic-Groovy computation for $token",
            )
            assertEquals(dynamicDetail, staticDetail, "static-Java computation for $token")
            assertEquals(
                listOf("$PROJECTED|${ComputeOutcome.CLEARED}|"),
                interpreted,
                "interpreter computation for $token",
            )
        }
    }

    private fun rule(name: String, code: String, errorField: FieldRef, condition: Condition): Rule =
        order.rule {
            it.name(name)
                .code(code)
                .errorField(errorField)
                .`when`(condition)
                .severity(Severity.ERROR)
                .message(Dsl.en("Malformed case variants must not reach this rule."))
                .build()
        }

    private fun kernelDocument(token: String) = RuntimeLaws.doc()
        .group(ROOT, 1)
        .field(PRIMARY, token, 1, 1)
        .field(PEER, "false", 1, 1)
        .field(PROJECTED, "false", 1, 1)

    private fun interpreterDocument(token: String) = definition.newDocument()
        .group(ROOT, 1)
        .field(PRIMARY, token, 1, 1)
        .field(PEER, "false", 1, 1)
        .field(PROJECTED, "false", 1, 1)

    private fun computationDetail(detail: RuntimeLaws.ComputationDetail): Pair<List<String>, List<String>> {
        val outcomes = detail.outcomes().filter { it.path() == PROJECTED }.map {
            val outcome = if (it.cleared()) "CLEARED" else if (it.errored()) "ERRORED" else "VALUE"
            "${it.path()}|$outcome|${it.value().orElse("")}"
        }
        return outcomes to detail.formalErrorsInOperands().map { it.code() }.sorted()
    }

    private companion object {
        const val ROOT = "/Order"
        const val PRIMARY = "/Order/ExpressShipping"
        const val PEER = "/Order/PeerFlag"
        const val PROJECTED = "/Order/ProjectedFlag"
        const val DIRECT = "DIRECT_CASE_VARIANT"
        const val AGGREGATE = "AGGREGATE_CASE_VARIANT"
    }
}
