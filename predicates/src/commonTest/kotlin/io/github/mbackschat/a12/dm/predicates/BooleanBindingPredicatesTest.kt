package io.github.mbackschat.a12.dm.predicates

import io.github.mbackschat.a12.dm.predicates.A12BooleanBinding.FALSE
import io.github.mbackschat.a12.dm.predicates.A12BooleanBinding.TRUE
import io.github.mbackschat.a12.dm.predicates.A12BooleanBinding.UNREADABLE
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The binding against every spelling A12 was asked about (`rulekit.validate.laws.ShortNameFlagSpellingLawsTest`
 * and `adapter.laws.CheckerDeclarationAdmissionAgreementTest`), on both runtimes. The padded rows separate a
 * trim from an exact match, and the no-break row separates Java's trim from Kotlin's `trim()`.
 */
class BooleanBindingPredicatesTest {

    @Test
    fun aStringBindsAfterJavasTrim() {
        val rows = linkedMapOf(
            "true" to TRUE, "True" to TRUE, "TRUE" to TRUE, " true" to TRUE, "\ttrue" to TRUE, "true\n" to TRUE,
            " TRUE" to TRUE,
            "false" to FALSE, "False" to FALSE, "FALSE" to FALSE,
            // Kernel 31.1.1's Jackson 3 coerces the empty text to null and refuses null for a primitive, so the
            // texts that bound as the default on 30.8.1 now refuse the reader (KERNEL-FINDINGS KF569).
            "" to UNREADABLE, " " to UNREADABLE, "null" to UNREADABLE, " null" to UNREADABLE,
            "yes" to UNREADABLE, "1" to UNREADABLE, "tRUE" to UNREADABLE, "FaLsE" to UNREADABLE,
            "NULL" to UNREADABLE, "\u00A0true" to UNREADABLE, "\u2003true" to UNREADABLE,
        )
        assertEquals<Map<String, A12BooleanBinding>>(rows, rows.keys.associateWith { a12BooleanOfString(it) })
    }

    @Test
    fun aNumberBindsOnlyAsAnInteger() {
        val rows = linkedMapOf(
            "1" to TRUE, "2" to TRUE, "-1" to TRUE, "9".repeat(26) to TRUE,
            "0" to FALSE, "-0" to FALSE,
            "0.5" to UNREADABLE, "1.5" to UNREADABLE, "1.0" to UNREADABLE, "1e0" to UNREADABLE,
        )
        assertEquals<Map<String, A12BooleanBinding>>(rows, rows.keys.associateWith { a12BooleanOfNumber(it) })
    }
}
