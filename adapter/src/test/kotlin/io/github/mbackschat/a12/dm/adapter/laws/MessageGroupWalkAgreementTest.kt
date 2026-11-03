package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.adapter.laws.MessageParameterProbes.en
import io.github.mbackschat.a12.dm.adapter.laws.MessageParameterProbes.kernel
import io.github.mbackschat.a12.dm.adapter.laws.MessageParameterProbes.library
import io.github.mbackschat.a12.dm.adapter.laws.MessageParameterProbes.rule
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * SPEC-2026-10-01-02 on our own Library carrier: where a group argument's parent walk ends, how its turning
 * point is compared, and where an ordinary dotted continuation belongs.
 *
 * Each row carries the class a12-kernel-lean's grid reports for the corresponding shape, translated onto this
 * model's depths (`Loans` one level below the root, `Shelf` two, `Bin` three), so a disagreement is a refuted
 * row rather than a silently re-predicted one.
 */
class MessageGroupWalkAgreementTest {
    data class Row(val id: String, val model: String, val classes: List<String>)

    private enum class At(val path: String, val field: String) {
        ROOT("/Library", "Branch"), LOANS("/Library/Loans", "Book"),
        SHELF("/Library/Loans/Shelf", "Slot"), BIN("/Library/Loans/Shelf/Bin", "Tag")
    }

    private val base = library()

    private fun model(at: At, argument: String?): String = rule(base, at.path, at.field,
        en(argument?.let { "\$#$it\$" })) { edit ->
        val own = edit.group(at.path).str(at.field).filled()
        if (at == At.ROOT) own.and(edit.group(At.LOANS.path).str(At.LOANS.field).filled()) else own
    }

    fun rows(): List<Row> = buildList {
        fun row(id: String, at: At, argument: String?, vararg classes: String) =
            add(Row(id, model(at, argument), classes.toList()))
        row("control-root", At.ROOT, null)
        row("control-bin", At.BIN, null)
        row("control-bin-parent", At.BIN, "..")

        // The walk: a single up-step counts as written, two or more before a non-name token count one more.
        row("walk-root-one-up", At.ROOT, "../", ABOVE)
        row("walk-loans-one-up", At.LOANS, "../", INCOMPLETE)
        row("walk-shelf-two-up-end", At.SHELF, "../../", ABOVE)
        row("walk-shelf-two-up-double", At.SHELF, "../..//", ABOVE)
        row("walk-shelf-two-up-unknown", At.SHELF, "../../Nope", GROUP)
        row("walk-shelf-two-up-name", At.SHELF, "../../Loans")
        row("walk-shelf-two-up-lexer", At.SHELF, "../../%", LEXER)
        row("walk-shelf-two-up-sign", At.SHELF, "../../-x", ABOVE)
        row("walk-shelf-two-up-bracket", At.SHELF, "../../(", ABOVE)
        row("walk-shelf-two-up-digits", At.SHELF, "../../123", GROUP)
        row("walk-shelf-two-up-quoted", At.SHELF, "../../'Loans'")
        row("walk-bin-two-up-end", At.BIN, "../../", EXPECTED)
        row("walk-bin-two-up-rule", At.BIN, "../../RuleGroup", UNEXPECTED)
        row("walk-bin-two-up-double", At.BIN, "../..//", UNEXPECTED)
        row("walk-bin-three-up-rule", At.BIN, "../../../RuleGroup", ABOVE)

        // The turning point is compared by its first word.
        row("turning-dotted-correct", At.LOANS, "..Library.x")
        row("turning-dotted-wrong", At.LOANS, "..Wrong.x", ENTITY)
        row("turning-wrong-then-up", At.LOANS, "..Wrong/..", ENTITY)
        row("turning-wrong-lexer", At.LOANS, "..Wrong.%", LEXER)

        // An ordinary dotted continuation is part of its name segment.
        for ((id, argument) in listOf("field" to "Loans.Book", "then-child" to "Loans.x/Shelf", "two-dots" to "Loans.x.y",
            "value-inside" to "Loans.value.x", "rootgroup" to "RootGroup.x", "zeile" to "Zeile.x",
            "quoted-rootgroup" to "'RootGroup'.x", "form-name-inside" to "Loans.Vordruckname.x",
            "usb-inside" to "Loans.Usb.x", "rootgroup-inside" to "Loans.RootGroup.x")) {
            row("dotted-$id", At.ROOT, argument, GROUP)
        }
        row("dotted-value-end", At.ROOT, "Loans.value", UNEXPECTED)
        row("dotted-digits", At.ROOT, "Loans.1", UNEXPECTED)
        row("dotted-empty", At.ROOT, "Loans.", UNEXPECTED)
        row("dotted-offset", At.ROOT, "Zeile-1.x", RETIRED)
        row("dotted-lexer", At.ROOT, "Loans.%", LEXER)
        row("dotted-open-quote", At.ROOT, "Loans.'x", LEXER)
    }

    @Test
    fun parentWalksTurningPointsAndDottedNamesFollowTheEntry() {
        val rows = rows()
        assertEquals(38, rows.size, "frozen witness rows")
        assertEquals(rows.size, rows.map { it.id }.toSet().size, "observations retain independent identities")
        val observed = rows.map { it.id to kernel(it.model) }
        assertEquals(rows.map { it.id to it.classes }, observed, "kernel classes")
        val declined = setOf("dotted-value-inside", "dotted-usb-inside",
            "dotted-rootgroup-inside", "dotted-value-end", "dotted-digits", "dotted-empty")
        MessageParameterProbes.replay("group-walk", rows.map { Triple(it.id, it.model, it.classes) }, declined.associateWith {
            when (it) {
                "dotted-digits" -> "Numeric dotted tails remain outside the measured name grammar."
                "dotted-empty" -> "Missing dotted tails remain outside the measured name grammar."
                else -> "Unquoted reserved dotted tails remain outside the measured name grammar."
            }
        })
        assertEquals(rows.map { it.id to if (it.id in declined) emptyList() else it.classes },
            rows.map { it.id to MessageParameterProbes.portable(it.model) },
            "group events; reserved, numeric and missing dotted tails retain the explicit decline")
    }

    private companion object {
        const val LEXER = "MVK_LEXER_STANDARD_ERROR"
        const val UNEXPECTED = "MVK_UNEXPECTED_TOKEN"
        const val EXPECTED = "MVK_EXPECTED_TOKEN_NOT_FOUND"
        const val INCOMPLETE = "MVK_INCOMPLETE_INPUT"
        const val GROUP = "INVALID_GROUP"
        const val ENTITY = "INVALID_ENTITY"
        const val RETIRED = "PARAM_INVALID_IN_NEW_WORLD"
        const val ABOVE = "ERROR_TEXT_PARAMETER_TOO_MANY_PARENT_GROUPS"
    }
}
