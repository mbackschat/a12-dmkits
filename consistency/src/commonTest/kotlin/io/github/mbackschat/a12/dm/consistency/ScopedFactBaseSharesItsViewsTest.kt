package io.github.mbackschat.a12.dm.consistency

import kotlin.test.Test
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * A scoped fact base shares its parent's derived views — the claim `scopedTo` makes about itself.
 *
 * **It made that claim while being false about a third of it.** The contract read "it shares this base's
 * model, transcript and cursor list"; the model and transcript were constructor parameters and shared, while
 * the cursor list was a per-INSTANCE `by lazy`. Since the dispatcher scopes once per declaration, every scope
 * rebuilt the whole cursor list and every index over it, which is a check quadratic in declarations and N
 * cursor allocations N times over.
 *
 * Identity is the only oracle for "shared", which is why the claim went unchecked for as long as it did: a
 * rebuilt list is EQUAL to the one it duplicates, so every structural assertion passes on both. This is the
 * "advertise nothing you cannot point at" rule applied to a sentence that had no enforcement locus.
 */
class ScopedFactBaseSharesItsViewsTest {

    @Test
    fun aScopeAnswersFromItsParentsOwnCursorsRatherThanARebuildOfThem() {
        val prepared = prepare(admit(ModelSource(MODEL)) as Admission.Admitted)
        val base = TrackedFactBase(prepared, ReadTranscript())
        val parentCursors = base.allDeclarations()
        val at = parentCursors.first()

        val scoped = base.scopedTo(DeclarationKey(at.path, 0), at)

        assertTrue(parentCursors.size > 1, "the fixture must declare several elements, or sharing is trivial")
        assertSame(
            parentCursors,
            scoped.allDeclarations(),
            "a scope rebuilt the cursor list, so the check allocates every cursor once per declaration",
        )
        assertSame(
            base.repeatableGroups(),
            scoped.repeatableGroups(),
            "a scope refiltered the repeatable groups",
        )
        // The by-path index is private, so it is reached through the member that consults it: same cursor
        // instance back means the same map, since a rebuilt map would hold rebuilt cursors.
        assertSame(
            base.declarationAt(at.path),
            scoped.declarationAt(at.path),
            "a scope rebuilt the by-path index",
        )
    }

    private companion object {
        /** Several elements including a repeatable group, so all three views have something to share. */
        private val MODEL = """
            {"header":{"modelVersion":"28.4.0","id":"shared","locales":[{"code":"en_US"}]},
             "content":{"modelInfo":{"name":"model"},"modelRoot":{"rootGroups":[
              {"type":"Group","id":"g0","name":"Basket","Group":{"elements":[
                {"type":"Field","id":"f0","name":"Total","Field":{"fieldType":{"type":"NumberType"}}},
                {"type":"Group","id":"g1","name":"Lines","Group":{"repeatability":5,"elements":[
                  {"type":"Field","id":"f1","name":"Amount","Field":{"fieldType":{"type":"NumberType"}}}
                ]}}
              ]}}
             ]}}}
        """.trimIndent()
    }
}
