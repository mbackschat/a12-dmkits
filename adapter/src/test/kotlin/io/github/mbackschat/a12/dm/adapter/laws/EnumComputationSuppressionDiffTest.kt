package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.interpreter.model.ComputeOutcome
import io.github.mbackschat.a12.dm.interpreter.eval.newDocument
import io.github.mbackschat.a12.dm.interpreter.DmInterpreter
import io.github.mbackschat.a12.dm.interpreter.load.ModelLoader
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl
import io.github.mbackschat.a12.dm.rulekit.validate.DraftComputation
import io.github.mbackschat.a12.dm.rulekit.write.ModelWriter
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.util.Locale

/**
 * What a text computed into an enumeration does at runtime once the computation's preamble suppresses
 * `MVK_ERROR_INVALID_ENUM_CALCULATION`, which kernel 31.3.1 then admits (KF298,
 * `rulekit.validate.laws.ComputationParserInformationLawsTest`). The source text is a declared token, that token's
 * label, an undeclared text and nothing; each row reads the computed outcome and the messages on the target, on both
 * kernel strategies and the interpreter.
 *
 * Own domain (an insurance policy's tier, derived from a free-text code).
 */
class EnumComputationSuppressionDiffTest {

    private val model = ModelWriter.addComputation(
        DocumentModelDraft.named("policy-tier").locale("en_US")
            .rootGroup("Policy") { policy -> policy
                .stringField("Code") { }
                .enumField("Tier") { e -> e.value("GOLD") { l -> l.label("en_US", "Gold") }
                    .value("SILVER") { l -> l.label("en_US", "Silver") } }
            }.build().toJson(),
        DraftComputation.builder().groupPath("/Policy").name("TierComp").computedFieldRelPath("../Tier")
            .additionalParserInformation("@SuppressWarning(MVK_ERROR_INVALID_ENUM_CALCULATION)")
            .alternatives(listOf(DraftComputation.Alternative(null, "[Code]"))).build(),
        listOf(Dsl.en("The tier follows the code.")))

    @Test
    fun aSuppressedTextIntoAnEnumerationComputesAsBothStrategiesDo() {
        // The empty row seeds the target, because the kernel reports a computed clear only for a cell that held a
        // value; on a fresh cell it reports no outcome where the interpreter reports one (INTERPRETER-FINDINGS,
        // the compute-granularity ruling), and that projection is not what this test measures.
        val rows = linkedMapOf("a declared token" to ("GOLD" to null), "the token's label" to ("Gold" to null),
            "an undeclared text" to ("BRONZE" to null), "no text, the tier seeded" to (null to "SILVER"))
        val observed = rows.mapValues { (_, input) -> observe(input.first, input.second) }
        assertEquals(observed.mapValues { it.value[0] }, observed.mapValues { it.value[1] }, "static Java against dynamic Groovy")
        assertEquals(EXPECTED, observed.mapValues { it.value[0] }, "dynamic Groovy")
        assertEquals(observed.mapValues { it.value[0] }, observed.mapValues { it.value[2] }, "interpreter against dynamic Groovy")
    }

    private fun observe(code: String?, seed: String?): List<String> {
        val kDoc = RuntimeLaws.doc().group("/Policy", 1)
        code?.let { kDoc.field(CODE, it, 1, 1) }
        seed?.let { kDoc.field(TIER, it, 1, 1) }
        val dynamic = RuntimeLaws.compute(model, kDoc).filter { it.path() == TIER }.joinToString(",") {
            if (it.cleared()) "CLEARED" else if (it.errored()) "ERRORED" else "VALUE ${it.value().orElse("")}"
        } + " | " + RuntimeLaws.messages(model, kDoc).map { "${it.code()}:${it.type().name}" }.sorted().joinToString(",")
        val java = RuntimeLaws.kernelJavaEngine(model)
        val generated = java.computeOutcomesSorted(java.instance(kDoc)).filter { it.startsWith("$TIER|") }
            .joinToString(",") { when (val v = it.substringAfter('|')) { "ERR" -> "ERRORED"; "CLEARED" -> "CLEARED"; else -> "VALUE $v" } } +
            " | " + java.messagesModern(kDoc, null, Locale.US).map { "${it.code()}:${it.type().name}" }.sorted().joinToString(",")
        val interpreted = runCatching {
            val definition = ModelLoader.load(model)
            val iDoc = definition.newDocument().group("/Policy", 1)
            code?.let { iDoc.field(CODE, it, 1, 1) }
            seed?.let { iDoc.field(TIER, it, 1, 1) }
            val interpreter = DmInterpreter(definition)
            interpreter.compute(iDoc).filter { it.path == TIER }.joinToString(",") {
                when (it.outcome) { ComputeOutcome.CLEARED -> "CLEARED"; ComputeOutcome.ERRORED -> "ERRORED"; ComputeOutcome.VALUE -> "VALUE ${it.value ?: ""}" }
            } + " | " + interpreter.validate(iDoc).messages.map { "${it.code}:${if (it.type.name == "OMISSION") "OMISSION_ERROR" else "VALUE_ERROR"}" }
                .sorted().joinToString(",")
        }.getOrElse { "REFUSED ${it::class.simpleName}: ${it.message}" }
        return listOf(dynamic, generated, interpreted)
    }

    private companion object {
        const val CODE = "/Policy/Code"
        const val TIER = "/Policy/Tier"
        /** Measured 2026-11-07 on kernel 31.3.1, both strategies alike: only a declared token is stored. */
        val EXPECTED = linkedMapOf(
            "a declared token" to "VALUE GOLD | ",
            "the token's label" to "ERRORED | ",
            "an undeclared text" to "ERRORED | ",
            "no text, the tier seeded" to "CLEARED | ",
        )
    }
}
