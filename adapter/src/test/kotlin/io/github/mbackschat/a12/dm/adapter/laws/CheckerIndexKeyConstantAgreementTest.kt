package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.consistency.ModelSource
import io.github.mbackschat.a12.dm.consistency.check
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import io.github.mbackschat.a12.dm.rulekit.validate.DraftRule
import io.github.mbackschat.a12.dm.rulekit.validate.RuleValidator
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * What a CONSTANT semantic-index key is judged by: the index field's own value validation, one rule per index
 * kind, with A12's class written out on every row and the portable checker required to name the same one.
 *
 * A12 runs `isValueApplicable` of the index field's type over the constant and refuses an empty one outright
 * (`CheckSemanticIndexImpl`, source-read). Each kind's rule is a SHAPE test, so the rows that discriminate it
 * from a looser or a stricter reading are the ones a value-level reading would decide differently: a date
 * that is no calendar day, a number in the other decimal separator, a length or a digit count the kind
 * declares but does not ask. The probe `Sum(<group>/Amount For "<constant>") > 0` names the arity class once
 * the key is admitted, so an admitted key and a refused one draw different classes.
 *
 * Own domain (insurance policies); every indexed group holds its key `K` and a Number `Amount`.
 */
class CheckerIndexKeyConstantAgreementTest {

    private val policy = DocumentModelDraft.named("policy-index-kinds").locale("en_US")
        .rootGroup("Policy") { p -> p
            .stringField("Reference") { }
            .group("Codes") { g -> keyed(g) { it.stringField("K") { } } }
            .group("Tags") { g -> keyed(g) { it.stringField("K") { s -> s.minLength(2).maxLength(3) } } }
            .group("Refs") { g -> keyed(g) { it.stringField("K") { s -> s.pattern("[A-Z]{2}[0-9]", "two letters, a digit") } } }
            .group("Lots") { g -> keyed(g) { it.stringField("K") { s -> s.pattern("[A-Z]+-[0-9]{2,3}", "a lot number") } } }
            .group("Words") { g -> keyed(g) { it.stringField("K") { s -> s.pattern("[^0-9]*", "no digits") } } }
            .group("Mixed") { g -> keyed(g) { it.stringField("K") { s -> s.pattern("[a-z]?[0-9]*", "a letter, digits") } } }
            .group("Runs") { g -> keyed(g) { it.stringField("K") { s -> s.pattern("[0-9]{3,}", "three digits or more") } } }
            .group("Caps") { g -> keyed(g) { it.stringField("K") { s -> s.maxLength(3).pattern("[A-Z]+", "capitals") } } }
            .group("Loose") { g -> keyed(g) { it.stringField("K") { s -> s.pattern(".{2}", "two characters") } } }
            .group("Pairs") { g -> keyed(g) { it.stringField("K") { s -> s.pattern("[^0-9]{2}", "two non-digits") } } }
            .group("Brief") { g -> keyed(g) { it.stringField("K") { s -> s.maxLength(1).pattern(".{2}", "two characters") } } }
            .group("Seqs") { g -> keyed(g) { it.numberField("K") { n -> n.fractionDigits(0) } } }
            .group("Rates") { g -> keyed(g) { it.numberField("K") { n -> n.fractionDigits(0, 2) } } }
            .group("Prices") { g -> keyed(g) { it.numberField("K") { n -> n.fractionDigits(2, 2) } } }
            .group("Counts") { g -> keyed(g) { it.numberField("K") { } } }
            .group("Bounded") { g -> keyed(g) { it.numberField("K") { n -> n.fractionDigits(0).maxIntegerDigits(2) } } }
            .group("Flags") { g -> keyed(g) { it.booleanField("K") { } } }
            .group("Marks") { g -> keyed(g) { it.booleanField("K") { b -> b.trueValue("J").falseValue("N") } } }
            .group("Confirms") { g -> keyed(g) { it.confirmField("K") { } } }
            .group("Dates") { g -> keyed(g) { it.dateField("K") { d -> d.format("yyyy-MM-dd") } } }
            .group("DotDates") { g -> keyed(g) { it.dateField("K") { d -> d.format("dd.MM.yyyy") } } }
            .group("Stamps") { g -> keyed(g) { it.dateTimeField("K") { d -> d.format("yyyy-MM-dd'T'HH:mm:ss") } } }
            .group("Times") { g -> keyed(g) { it.timeField("K") { t -> t.format("HH:mm:ss") } } }
            .group("Months") { g -> keyed(g) { it.dateFragmentField("K") { f -> f.fragmentFormat("yyyy-MM") } } }
            .group("Periods") { g -> keyed(g) { it.dateRangeField("K") { r -> r.format("dd.MM.yyyy").separator("-") } } } }
        .build().toJson()

    private val validator = RuleValidator()

    @Test
    fun theCheckerJudgesAConstantKeyAsA12Does() {
        val actual = ROWS.mapValues { (_, keyed) -> compare("Sum($keyed) > 0") }
        val expected = ROWS.mapValues { (row, _) -> A12.getValue(row).let { it to (OURS[row] ?: it) } }
        assertEquals(expected, actual, "a constant key on each index kind, as (A12, ours)")
    }

    private fun compare(probe: String): Pair<Set<String>, Set<String>> {
        val draft = DraftRule("/Policy", "ProbeRule", "../Reference", "FieldFilled(Reference) And $probe", "PROBE",
            "ERROR")
        val a12 = validator.check(policy, draft).let { result ->
            if (result.ok()) emptySet() else result.diagnostics().map { it.code().orElse("(no code)") }.toSet()
        }
        val ours = check(ModelSource(validator.inject(policy, draft))).findings
            .filter { it.justifiesRejection }.map { it.code }.toSet()
        return a12 to ours
    }

    private fun keyed(
        g: DocumentModelDraft.GroupBuilder,
        key: (DocumentModelDraft.GroupBuilder) -> DocumentModelDraft.GroupBuilder,
    ): DocumentModelDraft.GroupBuilder = key(g.repeatable(5).indexField("K")).numberField("Amount") { it.fractionDigits(2) }

    private companion object {
        /** Each row's keyed operand. */
        val ROWS = linkedMapOf(
            "String, any constant" to "Codes/Amount For \"ANY\"",
            "String, the empty constant" to "Codes/Amount For \"\"",
            "String 2..3, inside the bounds" to "Tags/Amount For \"AB\"",
            "String 2..3, too long" to "Tags/Amount For \"ABCD\"",
            "String 2..3, too short" to "Tags/Amount For \"A\"",
            "String pattern, matching" to "Refs/Amount For \"AB1\"",
            "String pattern, not matching" to "Refs/Amount For \"ab1\"",
            "String pattern, a match inside a longer text" to "Refs/Amount For \"XAB1\"",
            "String pattern with a literal dash, matching" to "Lots/Amount For \"AB-12\"",
            "String pattern with a literal dash, too many digits" to "Lots/Amount For \"AB-1234\"",
            "String pattern with a literal dash, no letters" to "Lots/Amount For \"-12\"",
            "String pattern, a negated class" to "Words/Amount For \"abc\"",
            "String pattern, a negated class meeting a digit" to "Words/Amount For \"a1\"",
            "String pattern, optional letter and digits" to "Mixed/Amount For \"5\"",
            "String pattern, the optional letter alone" to "Mixed/Amount For \"a\"",
            "String pattern, two letters for one optional" to "Mixed/Amount For \"ab\"",
            "String pattern, an open count met" to "Runs/Amount For \"123\"",
            "String pattern, an open count short" to "Runs/Amount For \"12\"",
            "String pattern and length, both met" to "Caps/Amount For \"AB\"",
            "String pattern and length, the pattern met and the length not" to "Caps/Amount For \"ABCD\"",
            "String pattern and length, the length met and the pattern not" to "Caps/Amount For \"ab\"",
            "String pattern, a supplementary character counted once" to "Pairs/Amount For \"\uD83D\uDE00\"",
            "String pattern, a supplementary character and a letter" to "Pairs/Amount For \"\uD83D\uDE00a\"",
            "String pattern outside the subset, matching" to "Loose/Amount For \"ab\"",
            "String pattern outside the subset, not matching" to "Loose/Amount For \"a\"",
            "String pattern outside the subset, the length not met" to "Brief/Amount For \"ab\"",
            "Number 0 digits, a whole number" to "Seqs/Amount For \"5\"",
            "Number 0 digits, negative" to "Seqs/Amount For \"-5\"",
            "Number 0 digits, a leading zero" to "Seqs/Amount For \"05\"",
            "Number 0 digits, a fraction" to "Seqs/Amount For \"5.0\"",
            "Number 0 digits, a plus sign" to "Seqs/Amount For \"+5\"",
            "Number 0 digits, a word" to "Seqs/Amount For \"x\"",
            "Number 0..2 digits, whole" to "Rates/Amount For \"5\"",
            "Number 0..2 digits, one digit" to "Rates/Amount For \"5.5\"",
            "Number 0..2 digits, a comma" to "Rates/Amount For \"5,55\"",
            "Number 0..2 digits, three digits" to "Rates/Amount For \"5.555\"",
            "Number 2..2 digits, whole" to "Prices/Amount For \"5\"",
            "Number 2..2 digits, two digits" to "Prices/Amount For \"5.00\"",
            "Number 2..2 digits, a comma" to "Prices/Amount For \"5,00\"",
            "Number 2..2 digits, one digit" to "Prices/Amount For \"5.0\"",
            "Number, no digits declared, whole" to "Counts/Amount For \"5\"",
            "Number, no digits declared, a fraction" to "Counts/Amount For \"5.5\"",
            "Number, 2 integer digits, five" to "Bounded/Amount For \"12345\"",
            "Boolean, true" to "Flags/Amount For \"true\"",
            "Boolean, false" to "Flags/Amount For \"false\"",
            "Boolean, another word" to "Flags/Amount For \"yes\"",
            "Boolean, true in capitals" to "Flags/Amount For \"TRUE\"",
            "Boolean J/N, J" to "Marks/Amount For \"J\"",
            "Boolean J/N, N" to "Marks/Amount For \"N\"",
            "Boolean J/N, true" to "Marks/Amount For \"true\"",
            "Confirm, true" to "Confirms/Amount For \"true\"",
            "Confirm, false" to "Confirms/Amount For \"false\"",
            "Date yyyy-MM-dd, a day" to "Dates/Amount For \"2020-01-31\"",
            "Date yyyy-MM-dd, no calendar day" to "Dates/Amount For \"2020-13-45\"",
            "Date yyyy-MM-dd, another format" to "Dates/Amount For \"31.01.2020\"",
            "Date yyyy-MM-dd, a short month" to "Dates/Amount For \"2020-1-31\"",
            "Date dd.MM.yyyy, a day" to "DotDates/Amount For \"31.01.2020\"",
            "Date dd.MM.yyyy, dashes for dots" to "DotDates/Amount For \"31-01-2020\"",
            "DateTime, a stamp" to "Stamps/Amount For \"2020-01-31T10:15:00\"",
            "DateTime, a day alone" to "Stamps/Amount For \"2020-01-31\"",
            "Time HH:mm:ss, a time" to "Times/Amount For \"10:15:00\"",
            "Time HH:mm:ss, no seconds" to "Times/Amount For \"10:15\"",
            "DateFragment yyyy-MM, a month" to "Months/Amount For \"2020-01\"",
            "DateFragment yyyy-MM, another order" to "Months/Amount For \"01.2020\"",
            "DateRange dd.MM.yyyy-, a range" to "Periods/Amount For \"01.01.2020-31.01.2020\"",
            "DateRange dd.MM.yyyy-, one day" to "Periods/Amount For \"01.01.2020\"",
        )

        val ADMITTED = setOf("MVK_PARAMSIZE_INVALIDN")
        val REFUSED = setOf("MVK_INDEX_VALUE_INVALID")

        /** The rows A12 refuses the key on; it admits every other one. */
        val REFUSED_ROWS = setOf(
            "String, the empty constant",
            "String 2..3, too long",
            "String 2..3, too short",
            "String pattern, not matching",
            "String pattern, a match inside a longer text",
            "String pattern with a literal dash, too many digits",
            "String pattern with a literal dash, no letters",
            "String pattern, a negated class meeting a digit",
            "String pattern, two letters for one optional",
            "String pattern, an open count short",
            "String pattern and length, the pattern met and the length not",
            "String pattern and length, the length met and the pattern not",
            "String pattern, a supplementary character counted once",
            "String pattern outside the subset, not matching",
            "String pattern outside the subset, the length not met",
            "Number 0 digits, a fraction",
            "Number 0 digits, a plus sign",
            "Number 0 digits, a word",
            "Number 0..2 digits, three digits",
            "Number 2..2 digits, whole",
            "Number 2..2 digits, one digit",
            "Number, no digits declared, a fraction",
            "Boolean, another word",
            "Boolean, true in capitals",
            "Boolean J/N, J",
            "Boolean J/N, N",
            "Confirm, false",
            "Date yyyy-MM-dd, another format",
            "Date yyyy-MM-dd, a short month",
            "Date dd.MM.yyyy, dashes for dots",
            "DateTime, a day alone",
            "Time HH:mm:ss, no seconds",
            "DateFragment yyyy-MM, another order",
            "DateRange dd.MM.yyyy-, one day",
        )

        /**
         * The rows whose constant carries a supplementary-plane character, which A12 also refuses in a condition
         * text with a finding carrying no code, beside the index class.
         */
        val CODELESS_ROWS = setOf(
            "String pattern, a supplementary character counted once",
            "String pattern, a supplementary character and a letter",
        )

        /** A12's class on each row. */
        val A12: Map<String, Set<String>> = ROWS.keys.associateWith {
            (if (it in REFUSED_ROWS) REFUSED else ADMITTED) + (if (it in CODELESS_ROWS) setOf("(no code)") else emptySet())
        }

        /**
         * The rows where the checker answers otherwise, with what it answers. A declared pattern outside the
         * subset both targets evaluate alike is Java regex this checker does not evaluate, so it leaves the key
         * unjudged and every gate behind it stands down. The checker names no codeless repertoire refusal of a
         * condition text (CONSISTENCY-GAPS DG109), so it gives those rows their index class alone.
         */
        val OURS: Map<String, Set<String>> = mapOf(
            "String pattern outside the subset, matching" to emptySet(),
            "String pattern outside the subset, not matching" to emptySet(),
            "String pattern, a supplementary character counted once" to REFUSED,
            "String pattern, a supplementary character and a letter" to ADMITTED,
        )
    }
}
