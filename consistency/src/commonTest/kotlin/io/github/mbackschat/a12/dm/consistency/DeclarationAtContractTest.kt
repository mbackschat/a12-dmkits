package io.github.mbackschat.a12.dm.consistency

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame

/**
 * What [TrackedFactBase.declarationAt]'s two overloads promise, on the points their call sites rest on.
 *
 * Both answer over the model A12 CHECKS, not the model as read. Of two declarations at one path, A12's converter
 * keeps the first and leaves the later one out with a sentence of its own ([KERNEL-FINDINGS KF652],
 * `adapter.laws.CheckerConverterSentenceAgreementTest`), so a lookup answers with the first and a question about
 * the later one's kind has nothing to answer with. A view that kept the later one would let 29 path lookups and
 * 15 kind-qualified gates judge a declaration A12 never judges — the over-rejection this file exists to catch.
 *
 * **The kinds are made to DIFFER on purpose.** Two twins of one kind cannot show whether the later one was left
 * out, since a lookup answers with the first either way; a field and a rule at one path make it observable.
 */
class DeclarationAtContractTest {

    private fun prepared(): PreparedModel {
        val admitted = admit(
            ModelSource(
                """
                {"header":{"modelVersion":"29.4.0","id":"twins","locales":[{"code":"en_US"}]},
                 "content":{"modelInfo":{"name":"model"},"modelRoot":{"rootGroups":[
                  {"type":"Group","id":"g0","name":"Order","Group":{"elements":[
                    {"type":"Field","id":"f0","name":"Code","Field":{"fieldType":{"type":"StringType"}}},
                    {"type":"Field","id":"f1","name":"Twin","Field":{"fieldType":{"type":"StringType"}}},
                    {"type":"Rule","id":"r0","name":"Twin","Rule":{"errorCode":"E1"}}
                  ]}}
                 ]}}}
                """.trimIndent(),
            ),
        )
        assertIs<Admission.Admitted>(admitted, "the fixture must be admissible for a lookup to be asked")
        return prepare(admitted)
    }

    @Test
    fun theLookupsAnswerWithTheTwinA12KeepsAndNeverWithTheOneItLeavesOut() {
        val base = TrackedFactBase(prepared(), ReadTranscript())

        assertEquals(
            listOf(DeclaredKind.FIELD, DeclaredKind.RULE),
            base.declarationsAsRead().filter { it.path == TWIN }.map { it.declaredKind },
            "the fixture must declare two DIFFERING kinds at one path, or leaving the later one out is not observable",
        )
        val kept = base.allDeclarations().filter { it.path == TWIN }
        assertEquals(listOf(DeclaredKind.FIELD), kept.map { it.declaredKind }, "A12 keeps the first twin alone")
        assertSame(kept.single(), base.declarationAt(TWIN), "the path lookup answers with the kept twin")
        assertSame(kept.single(), base.declarationAt(TWIN, DeclaredKind.FIELD), "and so does the kind-qualified one")
        assertNull(
            base.declarationAt(TWIN, DeclaredKind.RULE),
            "the rule A12 leaves out must not answer a rule-shaped question, or every kind-qualified gate judges it",
        )
    }

    @Test
    fun theLookupChargesTheSetsShapeSoACellCannotSurviveTheSetMoving() {
        val transcript = ReadTranscript()
        val base = TrackedFactBase(prepared(), transcript)

        base.declarationAt(TWIN)

        assertEquals(
            setOf<FactRead>(FactRead.DeclarationSetShape),
            transcript.observed(),
            "the lookup must charge the set's shape and nothing else: without the charge a cell would be " +
                "keyed on less than it read, and with a narrower one it would miss a declaration moving",
        )
    }

    @Test
    fun theKindQualifiedLookupChargesTheSetsShapeAndNothingMore() {
        val transcript = ReadTranscript()
        val base = TrackedFactBase(prepared(), transcript)

        base.declarationAt(TWIN, DeclaredKind.FIELD)

        assertEquals(
            setOf<FactRead>(FactRead.DeclarationSetShape),
            transcript.observed(),
            "the kind is a shape member that charges nothing on its own, so this lookup must charge " +
                "exactly what the path lookup charges — no body, and nothing narrower than the set",
        )
    }

    private companion object {
        const val TWIN = "/Order/Twin"
    }
}
