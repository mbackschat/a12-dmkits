package io.github.mbackschat.a12.dm.adapter.laws

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class MessageFormAlternativeAgreementTest {
    @Test
    fun formAlternativesRefuseKeysBeforeSubjectLookup() {
        val base = MessageParameterProbes.library()
        val rows = buildList {
            for (suffix in listOf("Vordruckzeile", "Vordruckname", "value")) {
                for (subject in listOf("Branch", "Loans", "Nope")) {
                    for ((tailId, tail) in listOf("none" to "", "literal" to " For \"k\"", "unknown" to " For Nope", "word" to " x")) {
                        val expected = when {
                            tailId == "word" -> listOf("MVK_UNEXPECTED_TOKEN")
                            // Kernel 31.1.1 dropped the suffix from its grammar, so `X.Vordruckname` is one dotted name
                            // that names nothing, looked up before any tail (KERNEL-FINDINGS KF657).
                            suffix == "Vordruckname" -> listOf("INVALID_ENTITY")
                            suffix != "value" && tailId != "none" -> listOf("MVK_UNEXPECTED_TOKEN")
                            subject == "Nope" || tailId == "unknown" -> listOf("INVALID_ENTITY")
                            tailId == "literal" -> listOf("MVK_INDEX_FOR_ERROR_TEXT_INVALID")
                            subject == "Loans" -> listOf(if (suffix == "value") "INVALID_FIELD" else "INVALID_FIELD_IN_PARAMETER")
                            else -> emptyList()
                        }
                        val model = MessageParameterProbes.rule(base, "/Library", "Branch",
                            MessageParameterProbes.en("\$$subject.$suffix$tail\$")) { edit ->
                            edit.group("/Library").str("Branch").filled()
                                .and(edit.group("/Library/Loans").str("Book").filled())
                        }
                        add(Triple("$suffix:$subject:$tailId", model, expected))
                    }
                }
            }
        }
        assertEquals(36, rows.size)
        assertEquals(rows.size, rows.map { it.first }.toSet().size)
        MessageParameterProbes.replay("form-alternative", rows, resource = "message-form-alternative.json")
        assertEquals(rows.map { it.first to it.third }, rows.map { it.first to MessageParameterProbes.kernel(it.second) }, "kernel classes")
        assertEquals(rows.map { it.first to it.third }, rows.map { it.first to MessageParameterProbes.portable(it.second) }, "portable classes")
    }
}
