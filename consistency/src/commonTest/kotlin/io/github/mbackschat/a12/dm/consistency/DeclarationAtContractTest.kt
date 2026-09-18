package io.github.mbackschat.a12.dm.consistency

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertSame

/**
 * What [TrackedFactBase.declarationAt]'s two overloads promise, on the points their call sites rest on.
 *
 * Both indexes were built to retire a whole-model scan: 29 bare path lookups moved onto the one-argument
 * form, then 15 kind-qualified gates onto the two-argument one (2026-09-18).
 *
 * **Why this exists, and it is not the happy path.** The sweep that moved those sites off
 * `allDeclarations().firstOrNull { it.path == X }` argued equivalence from `cursorsByPath` being built with
 * `getOrPut` — FIRST occurrence wins, exactly as `firstOrNull` returns. The argument is sound by
 * construction and nothing enforced it: changing the index to `associateBy`, or to a plain assignment, makes
 * it LAST-wins, and a cold review confirmed by mutation that all 696 tests pass under that change. The
 * repository's own record had already admitted the same gap one index over — `PreparedModel.resolvableByPath`
 * notes that `associateBy` "keeps the LAST, so it would have changed which duplicate a family reads. Nothing
 * measured that difference." Adding dependants to an unmeasured invariant is what this closes.
 *
 * **A collided path is the checker's business, not an impossible input.** A12 cannot build a model whose two
 * children of one parent share a name, and this checker REPORTS that (`CK_MODEL_NOT_BUILDABLE`) rather than
 * refusing to look — `ELEMENT_NAME_UNIQUENESS` is one family among the producers, so every other family
 * still judges such a model. So "which twin does a lookup answer with" is live behaviour on exactly the
 * models the checker exists to diagnose.
 *
 * **The kinds are made to DIFFER on purpose.** Two twins of one kind make first-wins and last-wins
 * indistinguishable, so the fixture would pass either way; a field and a rule at one path make the answer
 * observable.
 */
class DeclarationAtContractTest {

    private fun prepared(): PreparedModel {
        val admitted = admit(
            ModelSource(
                """
                {"header":{"id":"twins","locales":[{"code":"en_US"}]},
                 "content":{"modelRoot":{"rootGroups":[
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
    fun theLookupAnswersWithTheFirstOfTwoDeclarationsSharingAPath() {
        val base = TrackedFactBase(prepared(), ReadTranscript())

        val twins = base.allDeclarations().filter { it.path == TWIN }
        assertEquals(
            listOf(DeclaredKind.FIELD, DeclaredKind.RULE),
            twins.map { it.declaredKind },
            "the fixture must declare two DIFFERING kinds at one path, or first-wins and last-wins give the " +
                "same answer and this test cannot fail",
        )

        assertSame(
            twins.first(),
            base.declarationAt(TWIN),
            "the index must answer with the same declaration the scan it replaced returned — the FIRST. A " +
                "last-wins index would silently disagree with the 29 call sites that moved onto it",
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

    /**
     * The kind-qualified lookup answers on the twin its caller asked about — the case that makes it a
     * SEPARATE index rather than a filter over the path-only one.
     *
     * Fifteen gates ask `it.path == X && it.declaredKind == K`. Spelling that as
     * `declarationAt(X)?.takeIf { it.declaredKind == K }` looks equivalent and is not: the path-only index
     * answers with the FIRST declaration at the path, so on this fixture it hands back the field and the
     * rule-shaped question goes unanswered. That non-equivalence was once recorded here as a specification
     * question — what a gate *should* report for a collided path — when it is only an artefact of keying by
     * path alone. Keyed by (path, kind), every one of those gates keeps the answer it has today and no
     * question arises.
     */
    @Test
    fun theKindQualifiedLookupAnswersOnTheTwinItsCallerAsked() {
        val base = TrackedFactBase(prepared(), ReadTranscript())

        assertEquals(
            DeclaredKind.FIELD,
            base.declarationAt(TWIN)?.declaredKind,
            "the path-only lookup must still answer with the first twin, or the row below is not the " +
                "discriminating case it claims to be",
        )

        assertEquals(
            DeclaredKind.RULE,
            base.declarationAt(TWIN, DeclaredKind.RULE)?.declaredKind,
            "the kind-qualified lookup must reach the SECOND twin; a path-only index filtered by kind " +
                "returns null here, which is how the fifteen gates would silently go quiet",
        )
        assertSame(
            base.allDeclarations().first { it.path == TWIN && it.declaredKind == DeclaredKind.FIELD },
            base.declarationAt(TWIN, DeclaredKind.FIELD),
            "and it must agree with the scan it replaces, on the same first-wins rule",
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
                "exactly what the scan it replaces charged — no body, and nothing narrower than the set",
        )
    }

    private companion object {
        const val TWIN = "/Order/Twin"
    }
}
