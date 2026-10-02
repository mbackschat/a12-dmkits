package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.adapter.laws.MessageParameterProbes.en
import io.github.mbackschat.a12.dm.adapter.laws.MessageParameterProbes.kernel
import io.github.mbackschat.a12.dm.adapter.laws.MessageParameterProbes.library
import io.github.mbackschat.a12.dm.adapter.laws.MessageParameterProbes.rule
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * SPEC-2026-10-01-03 on our own Library carrier: the name position's relative and bare refusals, a group named
 * there, and how each name-carrying form refuses one.
 *
 * The root rule reads `Loans/Book` beside a second `Book` under `Returns`, so a bare `Book` is the short-name
 * ambiguity; `Borrower` is unique and unread unless a row says so. The flag-off rows carry their own control:
 * a bare short name in a CONDITION is refused there and admitted on the flag-on twin, which shows the flag
 * took effect before any message row is read against it.
 */
class MessageNamePositionAgreementTest {
    data class Row(val id: String, val model: String, val classes: List<String>)

    private enum class Reads { ROOT, LOANS, BORROWER }

    private fun model(base: String, reads: Reads, parameter: String): String {
        val declaring = if (reads == Reads.LOANS) LOANS else ROOT
        return rule(base, declaring, if (reads == Reads.LOANS) "Book" else "Branch", en(parameter)) { edit ->
            val loans = edit.group(LOANS)
            when (reads) {
                Reads.ROOT -> edit.group(ROOT).str("Branch").filled().and(loans.str("Book").filled())
                Reads.LOANS -> loans.str("Book").filled()
                Reads.BORROWER -> edit.group(ROOT).str("Branch").filled()
                    .and(edit.group("/Library/Returns").str("Borrower").filled())
            }
        }
    }

    /** A bare short name in a condition, which the typed renderer cannot spell: it always emits a full path. */
    private fun shortNameCondition(base: String): String = RuntimeLaws.withRule(base, ROOT, "ShortNameControl",
        "../Branch", "SHORT_NAME_CONTROL", "FieldFilled(Branch) And FieldFilled(Borrower)")

    fun rows(): List<Row> = buildList {
        val on = library()
        val off = library(shortNames = false)
        fun row(id: String, parameter: String, vararg classes: String, reads: Reads = Reads.ROOT, base: String = on) =
            add(Row(id, model(base, reads, parameter), classes.toList()))

        row("control-read-path", "\$Loans/Book\$")
        row("control-off-read-path", "\$Loans/Book\$", base = off)
        add(Row("control-condition-short-name-on", shortNameCondition(on), emptyList()))
        add(Row("control-condition-short-name-off", shortNameCondition(off), listOf("MVK_INVALID_ENTITY")))

        // Bare and relative refusal classes.
        row("absolute-unknown-root", "\$/Nope/Book\$", ROOT_GROUP)
        row("absolute-unknown-entity", "\$/Library/Nope/Book\$", ENTITY)
        row("bare-unknown", "\$Nope\$", ENTITY)
        row("bare-ambiguous", "\$Book\$", NOT_UNIQUE)
        row("bare-unread", "\$Borrower\$", FIELD)
        row("bare-group", "\$Loans\$", FIELD)
        row("relative-above-root", "\$../../Book\$", ABOVE, reads = Reads.LOANS)
        row("relative-wrong-turning-point", "\$..Wrong/Book\$", ENTITY, reads = Reads.LOANS)
        row("relative-unknown-child", "\$Nope/Book\$", ENTITY, reads = Reads.LOANS)
        row("relative-unread", "\$../Returns/Borrower\$", FIELD, reads = Reads.LOANS)

        // Each form that carries a name refuses a group on its own class.
        row("group-value", "\$Loans.value\$", FIELD)
        row("group-category", "\$Loans->Cat\$", FIELD)
        row("group-form-line", "\$Loans.Vordruckzeile\$", FIELD_IN_PARAMETER)
        row("group-absolute-form-name", "\$/Library/Loans.Vordruckname\$", FIELD_IN_PARAMETER)
        row("group-relative-form-line", "\$Shelf.Vordruckzeile\$", FIELD_IN_PARAMETER, reads = Reads.LOANS)
        row("group-relative-form-line-own-name", "\$Loans.Vordruckzeile\$", ENTITY, reads = Reads.LOANS)
        row("field-unread-form-line", "\$Returns/Borrower.Vordruckzeile\$")
        row("group-keyed-literal", "\$Loans For \"k\"\$", INDEX)
        row("group-as-key", "\$Branch For Loans\$", INDEX)
        row("group-keyed-unknown", "\$Loans For Nope\$", ENTITY)

        // An ambiguous first segment answers whatever path follows it; a lone absolute segment resolves.
        row("ambiguous-first-segment", "\$Book/Foo\$", NOT_UNIQUE)
        row("ambiguous-first-segment-value", "\$Book/Foo.value\$", NOT_UNIQUE)
        row("unique-first-segment", "\$Slot/Foo\$", ENTITY)
        row("lone-absolute-root", "\$/Library\$", FIELD)
        row("lone-absolute-unknown", "\$/Nope\$", ROOT_GROUP)

        // A nested key draws the nesting class only after every name resolves.
        row("nested-field-key", "\$Branch For Loans/Book For \"k\"\$", ILLEGAL_INDEX)
        row("nested-group-key", "\$Branch For Loans For \"k\"\$", ILLEGAL_INDEX)
        row("nested-field-keyed-by-field", "\$Branch For Loans/Book For Branch\$", ILLEGAL_INDEX)
        row("literal-then-key", "\$Branch For \"k1\" For \"k2\"\$", INDEX)
        row("nested-unknown-outer", "\$Nope For Loans/Book For \"k\"\$", ENTITY)
        row("nested-unknown-inner", "\$Branch For Nope For \"k\"\$", ENTITY)
        row("nested-unknown-innermost", "\$Branch For Loans/Book For Nope\$", ENTITY)

        // The message's short-name tier does not follow the model flag.
        row("off-ambiguous", "\$Book\$", NOT_UNIQUE, base = off)
        row("off-unread", "\$Borrower\$", FIELD, base = off)
        row("off-unread-deep", "\$Slot\$", FIELD, base = off)
        row("off-read", "\$Borrower\$", reads = Reads.BORROWER, base = off)
        row("off-form-line-unread", "\$Borrower.Vordruckzeile\$", base = off)
        row("off-form-line-ambiguous", "\$Book.Vordruckzeile\$", NOT_UNIQUE, base = off)
        row("off-ambiguous-first-segment", "\$Book/Foo\$", NOT_UNIQUE, base = off)
        row("off-value-unread", "\$Borrower.value\$", FIELD, base = off)
        row("off-keyed", "\$Borrower For \"k\"\$", INDEX, base = off)
    }

    @Test
    fun namePositionRefusalsCarryTheirOwnClasses() {
        val rows = rows()
        assertEquals(45, rows.size, "frozen witness rows")
        assertEquals(rows.size, rows.map { it.id }.toSet().size, "observations retain independent identities")
        assertEquals(rows.map { it.id to it.classes }, rows.map { it.id to kernel(it.model) }, "kernel classes")
        assertEquals(rows.map { it.id to it.classes }, rows.map { it.id to MessageParameterProbes.portable(it.model) }, "portable classes")
    }

    @Test
    fun unknownFormPositionNamesFailBeforeFieldCompletion() {
        val rows = listOf("Vordruckzeile", "Vordruckname").map { suffix ->
            Row(suffix, model(library(), Reads.ROOT, "\$Nope.$suffix\$"), listOf(ENTITY))
        }
        assertEquals(rows.map { it.id to it.classes }, rows.map { it.id to kernel(it.model) }, "kernel form-position lookup")
        assertEquals(rows.map { it.id to it.classes }, rows.map { it.id to MessageParameterProbes.portable(it.model) }, "portable form-position lookup")
    }

    private companion object {
        const val ROOT = "/Library"
        const val LOANS = "/Library/Loans"
        const val ROOT_GROUP = "INVALID_ROOT_GROUP"
        const val ENTITY = "INVALID_ENTITY"
        const val FIELD = "INVALID_FIELD"
        const val FIELD_IN_PARAMETER = "INVALID_FIELD_IN_PARAMETER"
        const val NOT_UNIQUE = "ERROR_TEXT_FIELDNAME_NOT_UNIQUE"
        const val ABOVE = "ERROR_TEXT_PARAMETER_TOO_MANY_PARENT_GROUPS"
        const val INDEX = "MVK_INDEX_FOR_ERROR_TEXT_INVALID"
        const val ILLEGAL_INDEX = "ILLEGAL_SEMANTIC_INDEX"
    }
}
