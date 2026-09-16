package io.github.mbackschat.a12.dm.consistency

import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The checker's own build identity is present and usable.
 *
 * **What this can and cannot establish.** [CHECKER_VERSION] is generated from `project.version` on every
 * build, so it cannot drift from the artifact it describes — that property comes from the wiring, not from a
 * test, and no test inside the artifact can independently observe the Gradle version without reading the same
 * constant. What a test CAN refuse is the failure that wiring does not prevent: a generator that emits a
 * blank, a placeholder, or something no consumer can parse as a version. The receipt that publishes this
 * would carry such a value without complaint, which is exactly why the shape is checked here rather than
 * trusted.
 *
 * The cross-artifact oracle — that the version dmtool resolves equals the one the npm package publishes —
 * needs both artifacts in view and belongs to F1-c, not here.
 */
class CheckerIdentityTest {

    @Test
    fun theCheckerNamesItsOwnBuildInAShapeAConsumerCanRead() {
        assertTrue(
            CHECKER_VERSION.matches(Regex("""\d+\.\d+\.\d+(-[0-9A-Za-z.-]+)?""")),
            "the checker's baked version must be a readable release version, and is '$CHECKER_VERSION'",
        )
    }

    @Test
    fun theBakedVersionIsNotAPlaceholder() {
        // A generator that loses its input typically emits one of these rather than failing, and a receipt
        // would publish it as an engine identity — a wrong claim about which build answered.
        assertTrue(
            CHECKER_VERSION !in setOf("0.0.0", "unspecified", "unknown"),
            "the checker's baked version is the placeholder '$CHECKER_VERSION', so the generator lost its input",
        )
    }
}
