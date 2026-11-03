package io.github.mbackschat.a12.dm.consistency

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals

/**
 * A declaration cursor compares by the declaration it stands for, not by the object handed out.
 *
 * **What this locks is a migration hazard rather than a feature.** A family moves behind the read barrier by
 * a receiver change, and `elements` becomes `declarations()`. Any comparison of two declarations in that
 * family would flip from the [DeclaredElement] data class's structural answer to an identity one, silently,
 * with the family's own verdict tests still green because they never compare declarations directly. The
 * barrier therefore carries the structural answer, and this is the test that fails if a later edit drops it.
 */
class TrackedDeclarationIdentityTest {

    private val policy = ModelSource(
        """
        {"header":{"modelVersion":"29.4.0","id":"policies","locales":[{"code":"en_US"}]},
         "content":{"modelInfo":{"name":"model"},"modelRoot":{"rootGroups":[
          {"type":"Group","id":"g0","name":"Policy","Group":{"elements":[
            {"type":"Field","id":"f0","name":"Holder","Field":{"fieldType":{"type":"StringType"}}},
            {"type":"Field","id":"f1","name":"Premium","Field":{"fieldType":{"type":"NumberType"}}}
          ]}}
         ]}}}
        """.trimIndent(),
    )

    @Test
    fun twoCursorsForOneDeclarationAreEqualAcrossSeparateWalks() {
        val admitted = admit(policy)
        assertIs<Admission.Admitted>(admitted, "the fixture must be admissible")
        val prepared = prepare(admitted)

        // Two separate FACT BASES over one model, not two walks on one: a fact base memoizes its cursors, so
        // two walks on the same one hand back the same object and an identity `equals` would pass. This is
        // the shape that discriminates, and it is the one a draft holds beside its baseline.
        val first = prepared.tracked().declarations().single { it.path == "/Policy/Holder" }
        val second = prepared.tracked().declarations().single { it.path == "/Policy/Holder" }

        assertEquals(first, second, "two cursors for one declaration must compare equal")
        assertEquals(first.hashCode(), second.hashCode(), "equal cursors must hash alike")
        assertNotEquals(
            first,
            prepared.tracked().declarations().single { it.path == "/Policy/Premium" },
            "cursors for DIFFERENT declarations must not compare equal, or the equality above is vacuous",
        )
    }
}
