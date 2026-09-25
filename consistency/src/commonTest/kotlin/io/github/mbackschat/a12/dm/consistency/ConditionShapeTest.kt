package io.github.mbackschat.a12.dm.consistency

import io.github.mbackschat.a12.dm.consistency.syntax.ConditionLanguage
import io.github.mbackschat.a12.dm.consistency.syntax.SyntaxEntry
import io.github.mbackschat.a12.dm.consistency.syntax.shapeFailure
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The condition parser, held to the two bars that matter and in that order.
 *
 * **It must accept everything the Kernel accepts.** The corpus is the catalog's own DSL, executed against the
 * real Kernel in CI, so a rejection here is a condition A12 admits — the one direction this checker may not
 * take. That bar is 100% and unconditional, in both condition languages.
 *
 * **Then it must reject what the Kernel rejects**, on the shapes measured through `checkConsistency`
 * ([KERNEL-FINDINGS KF300], `rulekit.validate.laws.RuleConditionSyntaxLawsTest`). Without this half a parser
 * that accepted every token sequence would pass the first bar perfectly, which is exactly the failure a
 * deliberately loose parser invites.
 */
class ConditionShapeTest {

    @Test
    fun everyTextTheKernelAcceptsParses() {
        val rejected = mutableMapOf<String, String>()
        fun reject(id: String, text: String, language: ConditionLanguage, entry: SyntaxEntry) {
            shapeFailure(text, language, entry)?.let { rejected["$language $id"] = report(text, it) }
        }
        for (row in ConditionCorpus.conditions()) {
            reject(row.id, row.en, ConditionLanguage.EN, SyntaxEntry.CONDITION)
            reject(row.id, row.de, ConditionLanguage.DE, SyntaxEntry.CONDITION)
        }
        for (row in ConditionCorpus.operations()) {
            reject(row.id, row.en, ConditionLanguage.EN, SyntaxEntry.OPERATION)
            reject(row.id, row.de, ConditionLanguage.DE, SyntaxEntry.OPERATION)
        }
        assertEquals(emptyMap(), rejected, "every one of these texts is DSL the Kernel accepts")
    }

    /**
     * The shapes A12 refuses, with the code its parser gives, and a valid control for each.
     *
     * The `ranOut` column is what picks between the two codes: only the shape that reaches the end of the
     * text with a delimiter still open draws `MVK_EXPECTED_TOKEN_NOT_FOUND`. A bare reference reaches the end
     * too, with nothing open, and is `MVK_UNEXPECTED_TOKEN` — which is why the end of the text is not by
     * itself enough to pick the code.
     */
    @Test
    fun theMeasuredRejectShapesAreRejectedWithTheCodeA12Gives() {
        val actual = mutableMapOf<String, String>()
        for ((label, text) in REJECTED) {
            actual[label] = verdict(text)
        }
        for ((label, text) in ACCEPTED) {
            actual[label] = verdict(text)
        }

        val expected = mutableMapOf<String, String>()
        expected["punctuation that is no condition"] = "MVK_UNEXPECTED_TOKEN"
        expected["an unknown name used as a call"] = "MVK_UNEXPECTED_TOKEN"
        expected["a German keyword in an English model"] = "MVK_UNEXPECTED_TOKEN"
        expected["a bare field reference"] = "MVK_UNEXPECTED_TOKEN"
        expected["an extra closing parenthesis"] = "MVK_UNEXPECTED_TOKEN"
        expected["an unclosed value bracket"] = "MVK_UNEXPECTED_TOKEN"
        expected["a bracket closing a parenthesis"] = "MVK_UNEXPECTED_TOKEN"
        expected["a stray closing bracket"] = "MVK_UNEXPECTED_TOKEN"
        expected["an unclosed argument list"] = "MVK_EXPECTED_TOKEN_NOT_FOUND"
        expected["a predicate call — the control"] = ACCEPTS
        expected["a comparison — the control"] = ACCEPTS
        expected["a parenthesised condition — the control"] = ACCEPTS
        expected["a filtered aggregate — the control"] = ACCEPTS
        expected["division — the symbol no corpus carries"] = ACCEPTS
        expected["exponentiation — the symbol no corpus carries"] = ACCEPTS
        assertEquals(expected, actual, "the parser's verdict on the measured shapes")
    }

    /**
     * The two infixes that join an operand to a value list, in the language each belongs to.
     *
     * `In` is carried by the catalog corpus; `To`/`Zu` is carried by NOTHING — not the corpus, not the real
     * models — which is the same unlocked-widening class a mutation check found in the arithmetic set.
     * Source: the kernel grammar's `inOp: 'In'|'IN'|'in'` and `toOp: 'Zu'|'ZU'|'zu'`, with the English
     * spellings in the EN terminal bundle, both pinned into the checker's generated table by
     * `ConditionKeywordProjectionTest`.
     *
     * **The cross-language rows are the ones that matter.** The parser's spelling set is language-BLIND and
     * holds all six; what keeps `Zu` from being read as an infix in an English model is the keyword table
     * lookup ahead of it, not the set. So each language gets the other's spelling as a row, and both are
     * ADMITTED — as an ordinary NAME, which is exactly what A12 does with a word that is not its language's
     * terminal. A row that refused would be the over-rejection this parser may not commit.
     */
    @Test
    fun theValueListInfixesParseInTheLanguageThatOwnsThemAndAsNamesInTheOther() {
        val actual = mutableMapOf<String, String>()
        for (spelling in listOf("In", "IN", "in")) {
            actual["EN $spelling"] = verdict("""ValueNotConsistent(Tier $spelling Premium, "gold")""")
            actual["DE $spelling"] = verdict("""WertNichtKonsistent(Tier $spelling Premium, "gold")""", ConditionLanguage.DE)
        }
        for (spelling in listOf("To", "TO", "to")) {
            actual["EN $spelling"] = verdict("""ValueNotConsistent(Tier $spelling Premium, "gold")""")
            actual["DE $spelling — the other language's spelling"] =
                verdict("""WertNichtKonsistent(Tier $spelling Premium, "gold")""", ConditionLanguage.DE)
        }
        for (spelling in listOf("Zu", "ZU", "zu")) {
            actual["DE $spelling"] =
                verdict("""WertNichtKonsistent(Tier $spelling Premium, "gold")""", ConditionLanguage.DE)
            actual["EN $spelling — the other language's spelling"] =
                verdict("""ValueNotConsistent(Tier $spelling Premium, "gold")""")
        }

        assertEquals(
            actual.keys.associateWith { ACCEPTS },
            actual,
            "every value-list infix spelling parses, in both languages",
        )
    }

    /**
     * The rule-context entity is a REFERENCE, not an operator, and must survive the keyword exclusion.
     *
     * `regelKontextEntity` is its own grammar production and the catalog does not carry it, so the generated
     * table files both spellings as `UNCATALOGED` — the same kind as a genuine keyword. A path scan that
     * excluded every keyword NAME would therefore refuse `GroupFilled(RuleGroup)`, a condition A12 admits.
     *
     * Neither spelling is reachable from the generated corpus: the catalog has no example that uses it, so
     * `ConditionCorpus.keywordsNoExampleLocalizes()` names `RuleGroup` as its one un-exampled keyword. That
     * is the same unlocked-widening class the arithmetic and value-list mutants found, and it is why the
     * German spelling gets its own row rather than riding the English one.
     */
    @Test
    fun theRuleContextEntityParsesAsAReferenceInBothLanguages() {
        val actual = mutableMapOf<String, String>()
        actual["EN RuleGroup"] = verdict("GroupFilled(RuleGroup)")
        actual["DE RegelKontext"] = verdict("KontextAngegeben(RegelKontext)", ConditionLanguage.DE)
        // The liveness control: a name that IS a genuine keyword is still excluded from a path, so these
        // rows cannot be passing because the parser admits every name wherever it stands.
        actual["EN a genuine keyword in the same slot"] = verdict("GroupFilled(And)")

        val expected = mutableMapOf<String, String>()
        expected["EN RuleGroup"] = ACCEPTS
        expected["DE RegelKontext"] = ACCEPTS
        expected["EN a genuine keyword in the same slot"] = "MVK_UNEXPECTED_TOKEN"
        assertEquals(expected, actual, "the rule-context entity parses as a reference, a keyword does not")
    }

    /** Mirrors `rulekit.validate.laws.ConditionArithmeticSymbolLawsTest.aSignStandsOnlyOnANumberLiteral`. */
    @Test
    fun aSignStandsOnlyOnANumberLiteral() {
        val actual = linkedMapOf(
            "a signed literal — the control" to verdict("[BaseFee] > -1"),
            "a signed literal subtracted" to verdict("[BaseFee] - -1 > 1"),
            "a signed reference" to verdict("-[BaseFee] < 0"),
            "a signed reference on the right" to verdict("[BaseFee] > -[EffectiveFee]"),
            "a signed braced operation" to verdict("[BaseFee] > -{[EffectiveFee] + 1}"),
            "a signed call" to verdict("[BaseFee] > -Abs([EffectiveFee])"),
            "a sign twice" to verdict("[BaseFee] > --1"),
        )
        val expected = linkedMapOf(
            "a signed literal — the control" to ACCEPTS,
            "a signed literal subtracted" to ACCEPTS,
            "a signed reference" to "MVK_UNEXPECTED_TOKEN",
            "a signed reference on the right" to "MVK_UNEXPECTED_TOKEN",
            "a signed braced operation" to "MVK_UNEXPECTED_TOKEN",
            "a signed call" to "MVK_UNEXPECTED_TOKEN",
            "a sign twice" to "MVK_UNEXPECTED_TOKEN",
        )
        assertEquals(expected, actual, "what a leading minus may sign")
    }

    /** Mirrors `…ConditionArithmeticSymbolLawsTest.onTheOperationCarrierASignStandsOnlyOnANumberLiteralToo`. */
    @Test
    fun onTheOperationCarrierASignStandsOnlyOnANumberLiteralToo() {
        val operations = listOf(
            "-5", "0 - [BaseFee]", "-[BaseFee]", "[BaseFee] * -1", "[BaseFee] * -[BaseFee]", "{-[BaseFee]}",
            "RoundUp(0 - [BaseFee], 2)", "RoundUp(-[BaseFee], 2)",
        )
        val actual = operations.associateWith { verdict(it, entry = SyntaxEntry.OPERATION) }
        val refused = setOf("-[BaseFee]", "[BaseFee] * -[BaseFee]", "{-[BaseFee]}", "RoundUp(-[BaseFee], 2)")
        val expected = operations.associateWith { if (it in refused) "MVK_UNEXPECTED_TOKEN" else ACCEPTS }
        assertEquals(expected, actual, "what a leading minus may sign in an operation")
    }

    private fun verdict(
        text: String,
        language: ConditionLanguage = ConditionLanguage.EN,
        entry: SyntaxEntry = SyntaxEntry.CONDITION,
    ): String {
        val failure = shapeFailure(text, language, entry) ?: return ACCEPTS
        return if (failure.ranOutWithDelimiterOpen) "MVK_EXPECTED_TOKEN_NOT_FOUND" else "MVK_UNEXPECTED_TOKEN"
    }

    private fun report(text: String, failure: io.github.mbackschat.a12.dm.consistency.syntax.ShapeFailure) =
        "at ${failure.at} found '${failure.found}' in: $text"

    private companion object {
        const val ACCEPTS = "ACCEPTED"

        val REJECTED = listOf(
            "punctuation that is no condition" to "@@@",
            "an unknown name used as a call" to "NotAnOperator(Tier)",
            "a German keyword in an English model" to "FeldAngegeben(Tier)",
            "a bare field reference" to "Tier",
            "an extra closing parenthesis" to "FieldFilled(Tier))",
            "an unclosed value bracket" to "FieldFilled([Tier)",
            "a bracket closing a parenthesis" to "FieldFilled(Tier]",
            "a stray closing bracket" to "FieldFilled(Tier)]",
            "an unclosed argument list" to "FieldFilled(Tier",
        )

        val ACCEPTED = listOf(
            "a predicate call — the control" to "FieldFilled(Tier)",
            "a comparison — the control" to "[Tier] > 1",
            "a parenthesised condition — the control" to "(FieldFilled(Tier) And FieldFilled(AutoRenew))",
            "a filtered aggregate — the control" to
                "NumberOfFilledFields(Addons*/Name Having [Addons/Name] == \"x\") > 0",
            // The two infix symbols NO population reaches — not the catalog corpus, not the real models.
            // A mutation check removing `/` from the parser's arithmetic set left the whole estate green,
            // so both rest here instead of on a reading of the grammar. Admission measured through
            // `checkConsistency` by `rulekit.validate.laws.ConditionArithmeticSymbolLawsTest`, where `^`
            // over a literal is ADMITTED and `^` over a field draws the SEMANTIC `MVK_INVALID_NUMBER_FOR_EXP`
            // — a code that proves the text parsed, which is the only thing this parser decides.
            "division — the symbol no corpus carries" to "[BaseFee] / [EffectiveFee] > 1",
            "exponentiation — the symbol no corpus carries" to "[BaseFee] ^ 2 > 1",
        )
    }
}
