package io.github.mbackschat.a12.dm.consistency

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * [javaLowercase] answers as Java's `String.toLowerCase(Locale.ROOT)` does, on every target this test runs on.
 *
 * Each expected value was printed by that call on the pinned JDK 21.0.13 (2026-10-30). The JVM target reaches the
 * same call, so the rows that earn their place are the JS target's: the two-unit `İ` that A12's generated-code name
 * cut depends on (KERNEL-FINDINGS KF650), and the context-dependent final sigma.
 */
class JavaLowercaseTest {

    @Test
    fun lowerCasesAsJavasRootLocaleDoes() {
        val rows = mapOf(
            "Policy_V1" to "policy_v1",
            "XMLİ" to "xmli̇",
            "İİİ_b" to "i̇i̇i̇_b",
            "ΟΔΟΣ" to "οδος",
            "ΟΔΟΣ ΣΟ" to "οδος σο",
        )
        assertEquals(rows, rows.mapValues { (written, _) -> written.javaLowercase() })
    }
}
