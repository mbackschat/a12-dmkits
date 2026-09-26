package io.github.mbackschat.a12.dm.consistency

import kotlin.test.assertTrue

/**
 * [check], refusing a source it did not admit.
 *
 * A source the checker cannot admit reports no finding at all, so a row expecting none passes on a fixture that
 * never loaded — a stray quote in a JSON fixture made a mutant survive exactly that way (2026-09-26). An empty
 * [ConsistencyReport.checksCompleted] is the one signal that nothing was checked. A test about admission itself
 * calls [check] directly, and `CheckerFixtureReadGuardTest` holds the list of the files that may.
 */
internal fun checkRead(source: ModelSource): ConsistencyReport =
    check(source).also { report ->
        assertTrue(report.checksCompleted.isNotEmpty(), "the fixture was not admitted: ${report.diagnostics}")
    }
