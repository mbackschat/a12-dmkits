package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.interpreter.DmInterpreter
import io.github.mbackschat.a12.dm.interpreter.eval.newDocument
import io.github.mbackschat.a12.dm.interpreter.load.ModelLoader
import io.github.mbackschat.a12.dm.interpreter.model.ComputeOutcome
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel
import io.github.mbackschat.a12.dm.rulekit.dsl.Condition
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl
import io.github.mbackschat.a12.dm.rulekit.dsl.NumberLiteral
import io.github.mbackschat.a12.dm.rulekit.dsl.Quantifier
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Tri-engine RV04 lock for every legal bare-group fill quantifier. The nested repeatable field's declared range
 * is read field-major before the later malformed field; reversing declaration order makes that malformed field
 * poison first. The entire fixture, conditions, and computations are authored through modelkit/rulekit.
 */
class BareGroupComputeExactnessDiffTest {

    @Test
    fun everyQuantifierUsesFieldMajorDeclaredOrderAndEarlyStop() {
        for (quantifier in QUANTIFIERS) {
            agree(quantifier, invalidFirst = false, expectedProbe = "VALUE|1")
            agree(quantifier, invalidFirst = true, expectedProbe = "CLEARED")
        }
    }

    private fun agree(quantifier: Quantifier, invalidFirst: Boolean, expectedProbe: String) {
        val base = DocumentModelDraft.named("policy").locale("en_US")
            .rootGroup("Policy") { policy ->
                policy.numberField("Flag") { it.fractionDigits(0) }
                policy.numberField("Probe") { it.fractionDigits(0) }
                policy.group("Details") { details ->
                    if (invalidFirst) {
                        details.numberField("Later") { it.fractionDigits(0) }
                        entries(details)
                    } else {
                        entries(details)
                        details.numberField("Later") { it.fractionDigits(0) }
                    }
                }
            }
            .build()
            .toJson()
        val model = BoundModel.ofJson(base)
        val scope = model.flattenGroup(DETAILS)
        val flagCondition = condition(quantifier, scope)
        val flag = model.computation(model.num(FLAG)).name("FlagComp")
            .`when`(flagCondition).calc(NumberLiteral.of("1"))
            .message(Dsl.en("Flag follows the details fill state.")).build()
        val probeCondition = model.num(FLAG).filled().or(model.num(FLAG).notFilled())
        val probe = model.computation(model.num(PROBE)).name("ProbeComp")
            .`when`(probeCondition).calc(NumberLiteral.of("1"))
            .message(Dsl.en("Probe distinguishes a plain result from poison.")).build()
        val modelJson = TypedRules.inject(TypedRules.inject(base, flag), probe)
        val def = ModelLoader.load(modelJson)
        val filledRows = if (quantifier == Quantifier.MORE_THAN_ONE_FIELD_FILLED ||
            quantifier == Quantifier.NOT_EXACTLY_ONE_FIELD_FILLED) 2 else 1

        fun populate(
            group: (String, IntArray) -> Unit,
            field: (String, String, IntArray) -> Unit,
        ) {
            group(ROOT, intArrayOf(1))
            group(DETAILS, intArrayOf(1, 1))
            field(FLAG, "9", intArrayOf(1, 1))
            field(PROBE, "9", intArrayOf(1, 1))
            for (row in 1..filledRows) {
                group(ENTRIES, intArrayOf(1, 1, row))
                field(FIRST, row.toString(), intArrayOf(1, 1, row, 1))
            }
            field(LATER, "7.5", intArrayOf(1, 1, 1))
        }

        val dynamicDoc = RuntimeLaws.doc()
        populate(
            { path, reps -> dynamicDoc.group(path, *reps) },
            { path, value, reps -> dynamicDoc.field(path, value, *reps) },
        )
        val dynamic = RuntimeLaws.compute(modelJson, dynamicDoc)
            .first { it.path() == PROBE }
            .let { if (it.cleared()) "CLEARED" else if (it.errored()) "ERRORED" else "VALUE|${it.value().orElse("")}" }

        val staticEngine = RuntimeLaws.kernelJavaEngine(modelJson)
        val staticDoc = RuntimeLaws.doc()
        populate(
            { path, reps -> staticDoc.group(path, *reps) },
            { path, value, reps -> staticDoc.field(path, value, *reps) },
        )
        val java = staticEngine.computeOutcomesSorted(staticEngine.instance(staticDoc))
            .first { it.startsWith("$PROBE|") }
            .substringAfter('|')
            .let { if (it == "ERR") "ERRORED" else if (it == "CLEARED") "CLEARED" else "VALUE|$it" }

        val interpDoc = def.newDocument()
        populate(
            { path, reps -> interpDoc.group(path, *reps) },
            { path, value, reps -> interpDoc.field(path, value, *reps) },
        )
        val interp = DmInterpreter(def).compute(interpDoc)
            .first { it.path == PROBE }
            .let {
                when (it.outcome) {
                    ComputeOutcome.CLEARED -> "CLEARED"
                    ComputeOutcome.ERRORED -> "ERRORED"
                    ComputeOutcome.VALUE -> "VALUE|${it.value}"
                }
            }

        val scenario = "$quantifier, invalidFirst=$invalidFirst"
        assertEquals(expectedProbe, dynamic, "$scenario — normative dynamic-Groovy")
        assertEquals(dynamic, java, "$scenario — generated Java")
        assertEquals(dynamic, interp, "$scenario — interpreter")
    }

    private fun entries(details: DocumentModelDraft.GroupBuilder) {
        details.group("Entries") { entries ->
            entries.repeatable(3)
            entries.numberField("First") { it.fractionDigits(0) }
        }
    }

    private fun condition(
        quantifier: Quantifier,
        scope: io.github.mbackschat.a12.dm.rulekit.dsl.GroupListRef,
    ): Condition = when (quantifier) {
        Quantifier.ALL_FIELDS_FILLED -> Dsl.allFieldsFilled(scope)
        Quantifier.NO_FIELD_FILLED -> Dsl.noFieldFilled(scope)
        Quantifier.AT_LEAST_ONE_FIELD_FILLED -> Dsl.atLeastOneFieldFilled(scope)
        Quantifier.NOT_ALL_FIELDS_FILLED -> Dsl.notAllFieldsFilled(scope)
        Quantifier.FIELDS_NOT_COLLECTIVELY_FILLED -> Dsl.fieldsNotCollectivelyFilled(scope)
        Quantifier.NOT_EXACTLY_ONE_FIELD_FILLED -> Dsl.notExactlyOneFieldFilled(scope)
        Quantifier.MORE_THAN_ONE_FIELD_FILLED -> Dsl.moreThanOneFieldFilled(scope)
        else -> error("$quantifier is not a field-fill quantifier")
    }

    private companion object {
        const val ROOT = "/Policy"
        const val DETAILS = "/Policy/Details"
        const val ENTRIES = "/Policy/Details/Entries"
        const val FLAG = "/Policy/Flag"
        const val PROBE = "/Policy/Probe"
        const val FIRST = "/Policy/Details/Entries/First"
        const val LATER = "/Policy/Details/Later"
        val QUANTIFIERS = listOf(
            Quantifier.ALL_FIELDS_FILLED,
            Quantifier.NO_FIELD_FILLED,
            Quantifier.AT_LEAST_ONE_FIELD_FILLED,
            Quantifier.NOT_ALL_FIELDS_FILLED,
            Quantifier.FIELDS_NOT_COLLECTIVELY_FILLED,
            Quantifier.NOT_EXACTLY_ONE_FIELD_FILLED,
            Quantifier.MORE_THAN_ONE_FIELD_FILLED,
        )
    }
}
