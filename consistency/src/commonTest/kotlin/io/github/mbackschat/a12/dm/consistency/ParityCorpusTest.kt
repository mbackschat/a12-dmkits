package io.github.mbackschat.a12.dm.consistency

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The full report contract, compared against the corpus the PACKAGED arms compare against.
 *
 * **This is the third arm of one claim, not a third claim.** F's oracle for "JS matches JVM" asks to execute
 * the same fixtures "through Java, packed Node and real-browser consumers, comparing the full portable report
 * contract" — and the corpus this reads is embedded from the very bytes `:consistency:nodeConsumerTest` reads
 * off disk and `:consistency:browserConsumerTest` fetches. So a divergence between the compiled library and
 * the packaged one shows up here as a mismatch, and a divergence between the JVM and Kotlin/JS shows up
 * because this same test runs on both targets.
 *
 * **What it deliberately does not do is state a semantic expectation.** Every value here was produced by the
 * checker, so this suite cannot tell a right answer from a wrong one — the claim-per-case assertions that can
 * live in the module's family suites and in `consumer-tests/shared/cases.ts`. What a golden corpus CAN see is
 * every member no named assertion mentions, which is most of the document and the whole reason F's oracle
 * asks for the full contract rather than for fields.
 *
 * Comparison is over PARSED documents: object key order is not part of the wire contract and array order is,
 * which is exactly what `JsonElement` equality gives.
 */
class ParityCorpusTest {

    @Test
    fun theCorpusReachesThisArm() {
        // The embedding is generated, so an empty corpus is a build-wiring failure that would otherwise make
        // every assertion below vanish while the suite stayed green.
        assertTrue(ParityCorpusJson.cases().isNotEmpty(), "the embedded parity corpus is empty")
    }

    @Test
    fun everyCaseProducesTheReportTheOtherArmsProduce() {
        for (case in ParityCorpusJson.cases()) {
            val actual = Json.parseToJsonElement(reportFor(case))
            val expected = Json.parseToJsonElement(ParityCorpusJson.report(case.name))
            val difference = firstDifference(actual, expected)
            assertEquals(
                null,
                difference,
                "${case.name}: $difference. Regenerate with " +
                    "./build.sh :consistency:syncCheckerParityCorpus if this change is intended",
            )
        }
    }

    private fun reportFor(case: ParityCase): String {
        val sources = case.sources.map { ModelSource(ParityCorpusJson.source(it)) }
        return when (case.call) {
            "checkModel" -> check(sources[0]).toJson()
            "checkEdit" -> checkEdit(sources[0], sources[1]).toJson()
            // A call this arm cannot execute must fail, never be skipped: a skip would leave the corpus
            // looking fully replayed on the other two arms and silently short here.
            else -> error("the parity corpus asks for an unknown call: ${case.call}")
        }
    }

    /**
     * The first structural difference as a readable path, or `null` when the documents agree.
     *
     * `assertEquals` on two 14 kB documents prints both and names nothing, which is unusable on the failure
     * this test exists to report. The path is the diagnostic.
     */
    private fun firstDifference(actual: JsonElement, expected: JsonElement, path: String = "$"): String? {
        if (actual == expected) {
            return null
        }
        if (actual is JsonObject && expected is JsonObject) {
            for (key in (expected.keys + actual.keys).sorted()) {
                val here = "$path.$key"
                val mine = actual[key] ?: return "$here: absent, expected ${expected[key]}"
                val theirs = expected[key] ?: return "$here: present as $mine, expected absent"
                firstDifference(mine, theirs, here)?.let { return it }
            }
            return null
        }
        if (actual is JsonArray && expected is JsonArray) {
            if (actual.size != expected.size) {
                return "$path: ${actual.size} element(s), expected ${expected.size}"
            }
            for (index in expected.indices) {
                firstDifference(actual[index], expected[index], "$path[$index]")?.let { return it }
            }
            return null
        }
        return "$path: $actual, expected $expected"
    }
}
