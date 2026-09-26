package io.github.mbackschat.a12.dm.consistency.syntax

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Which names in a condition are ENTITY references and which are keywords.
 *
 * A12's lexer answers that per language rather than with fixed literals, so every case here names the
 * language it is measured under. The cases that matter are the ones a structural guess gets wrong: an infix
 * operator is a bare name with no parentheses, exactly like a field.
 */
class ConditionReferenceTest {

    @Test
    fun aPredicatesArgumentIsAReferenceAndItsKeywordIsNot() {
        assertEquals(listOf("Code"), referencesIn("FieldFilled(Code)", ConditionLanguage.EN))
    }

    /**
     * The discriminating case against "a name not followed by `(` is a reference": `And` is exactly that,
     * and it is an operator. Without the keyword table this condition reports three references.
     */
    @Test
    fun anInfixKeywordIsNotAReference() {
        assertEquals(
            listOf("Note", "Code"),
            referencesIn("FieldFilled(Note) And FieldFilled(Code)", ConditionLanguage.EN),
        )
    }

    @Test
    fun aConstantKeywordIsNotAReference() {
        assertEquals(listOf("Approved"), referencesIn("[Approved] == True", ConditionLanguage.EN))
    }

    @Test
    fun theGermanSpellingOfAKeywordIsAKeywordInAGermanModel() {
        assertEquals(
            listOf("Note", "Code"),
            referencesIn("FeldAngegeben(Note) Und FeldAngegeben(Code)", ConditionLanguage.DE),
        )
    }

    /**
     * The case a merged keyword table cannot get right, and the reason the tables are separate: each
     * language's spelling of `And` is an ordinary field name in the OTHER language, so a checker holding one
     * set for both would drop a live reference in whichever direction it guessed wrong.
     */
    @Test
    fun aKeywordOfTheOtherLanguageIsAnOrdinaryName() {
        assertEquals(listOf("Und"), referencesIn("FieldFilled(Und)", ConditionLanguage.EN))
        assertEquals(listOf("And"), referencesIn("FeldAngegeben(And)", ConditionLanguage.DE))
    }

    @Test
    fun aBracketedFieldIsAReference() {
        assertEquals(listOf("Qty"), referencesIn("[Qty] > 5", ConditionLanguage.EN))
    }

    /** Neither a string literal nor a comment contributes a name, however condition-shaped its content. */
    @Test
    fun literalsAndCommentsContributeNothing() {
        assertEquals(emptyList(), referencesIn(";; FieldFilled(Secret)", ConditionLanguage.EN))
        assertEquals(
            listOf("Status"),
            referencesIn("""FieldValueAsString(Status) == "FieldFilled(Secret)"""", ConditionLanguage.EN),
        )
    }

    /** A path reference is kept whole, because resolving only its last segment would find the wrong field. */
    @Test
    fun aPathReferenceIsKeptWhole() {
        assertEquals(listOf("../Warehouse/Sku"), referencesIn("FieldFilled(../Warehouse/Sku)", ConditionLanguage.EN))
        assertEquals(listOf("/Catalog/Sku"), referencesIn("FieldFilled(/Catalog/Sku)", ConditionLanguage.EN))
        assertEquals(listOf("../../Root/Code"), referencesIn("FieldFilled(../../Root/Code)", ConditionLanguage.EN))
        assertEquals(listOf("Lines*/Amount"), referencesIn("Sum(Lines*/Amount)", ConditionLanguage.EN))
    }

    /**
     * Whitespace rides the lexer's HIDDEN channel and is insignificant between ANY two tokens (KF76,
     * `.. / seg` ≡ `../seg`, and inside an up-run too). An adjacency rule reads spacing as structure and
     * splits one path into several bogus references; each spaced row here is paired with its tight control.
     */
    @Test
    fun spacingBetweenPathTokensIsInsignificant() {
        assertEquals(listOf("../Code"), referencesIn("FieldFilled(.. / Code)", ConditionLanguage.EN))
        assertEquals(listOf("Lines*/Amount"), referencesIn("Sum(Lines* / Amount)", ConditionLanguage.EN))
        assertEquals(listOf("Lines*/Amount"), referencesIn("Sum(Lines */Amount)", ConditionLanguage.EN))
        assertEquals(listOf("/Order/Code"), referencesIn("FieldFilled(/ Order / Code)", ConditionLanguage.EN))
        assertEquals(listOf("../../Code"), referencesIn("FieldFilled(.. .. / Code)", ConditionLanguage.EN))
    }

    /**
     * The case that whitespace-insensitivity has to survive: `/` between two BRACKETED operands is division,
     * not a path. It is told apart by the operands, not by the spacing — a bare identifier is never an
     * arithmetic operand, so a run that names nothing yields nothing.
     */
    @Test
    fun divisionBetweenBracketedOperandsIsNotAPath() {
        assertEquals(listOf("A", "B"), referencesIn("[A] / [B] > 1", ConditionLanguage.EN))
        assertEquals(listOf("A", "B"), referencesIn("[A]/[B] > 1", ConditionLanguage.EN))
    }

    /** A digit-LEADING name is one identifier: `NameOhnePunktZifferAmAnfang` is `ZiffernFolge STR …`. */
    @Test
    fun aDigitLeadingNameIsOneReference() {
        assertEquals(listOf("1City"), referencesIn("FieldFilled(1City)", ConditionLanguage.EN))
        assertEquals(emptyList(), referencesIn("[Qty] > 5", ConditionLanguage.EN).filter { it == "5" })
    }

    /** A keyword ends a path run, because unquoted the Kernel lexes it as the keyword and not as a segment. */
    @Test
    fun aKeywordEndsAPathRun() {
        assertEquals(listOf("A"), referencesIn("FieldFilled(A/And)", ConditionLanguage.EN))
    }

    /** A trailing separator is a malformed tail, never part of the name it follows. */
    @Test
    fun aTrailingSeparatorIsNotPartOfTheName() {
        assertEquals(listOf("A"), referencesIn("FieldFilled(A/)", ConditionLanguage.EN))
    }

    /**
     * The lexer's `STR` alphabet is letters plus `_`, the German umlauts and eszett, and `:` — not just
     * ASCII letters. A narrower alphabet splits a legal German field name into two references.
     */
    @Test
    fun theNameAlphabetIncludesUnderscoresAndUmlauts() {
        assertEquals(listOf("Größe_1"), referencesIn("FieldFilled(Größe_1)", ConditionLanguage.EN))
    }

    /** A name quoted to escape a keyword collision IS a reference, which is the point of quoting it. */
    @Test
    fun aQuotedKeywordNameIsAReference() {
        assertEquals(listOf("And"), referencesIn("FieldFilled('And')", ConditionLanguage.EN))
    }

    /**
     * `->` names a value CATEGORY, not an entity (`entitySpec … ('->' categoryName=nameOhnePunkt)?`), so the
     * name after it must not be resolved as a field — it would always be reported undeclared.
     */
    @Test
    fun aCategoryNameAfterTheArrowIsNotAReference() {
        assertEquals(listOf("Premium"), referencesIn("[Premium->Gross] > 0", ConditionLanguage.EN))
    }

    /**
     * `@From` is ONE terminal in the lexer, so the checker must not read its second half as a name — `From`
     * on its own is not a keyword in either language and would be reported as an undeclared field.
     */
    @Test
    fun theRepetitionOriginKeywordIsNotAReference() {
        assertEquals(
            listOf("Lines*/Code", "Lines"),
            referencesIn("RepetitionNotUnique(Lines*/Code @From Lines)", ConditionLanguage.EN),
        )
    }

    /**
     * `CustomCondition <Name>` names host code the consuming system registered, not an element.
     *
     * This is the one predicate spelled as a keyword followed by a BARE NAME rather than a call
     * (`applikationsBedingung`), so nothing about its shape separates the name from a field reference — and
     * reading it as one reports an undeclared field on every model that uses the escape hatch. Three
     * committed models do, and the Kernel builds all three
     * (`adapter.laws.CheckerSilenceOnCleanModelsTest`); KERNEL-SEMANTICS §14 is the canonical account.
     */
    @Test
    fun theNameAfterTheCustomConditionKeywordIsNotAReference() {
        assertEquals(
            listOf("CustomerName"),
            referencesIn("FieldFilled(CustomerName) And CustomCondition ExternalEligibility", ConditionLanguage.EN),
        )
        assertEquals(
            listOf("Name"),
            referencesIn("FeldAngegeben(Name) Und ApplikationsBedingung ExterneEignung", ConditionLanguage.DE),
        )
    }

    /**
     * …and ONLY that keyword takes a bare name — the row that says why the parser's broader test is not reused.
     *
     * The shape parser lets ANY callable-condition keyword not followed by `(` consume the name behind it,
     * which is right there because it applies only where a condition is expected positionally. This is a flat
     * token walk, so the same test swallows the right operand of the UNCATALOGED infix `In` and then reads the
     * remainder of the path as an absolute reference to its last segment. Measured on the reference corpus
     * (nine rules, five models) minutes after the broad form was written; this is its own-domain repro,
     * because a corpus that skips when absent cannot be the regression guard.
     */
    @Test
    fun anInfixListKeywordDoesNotSwallowItsRightOperand() {
        assertEquals(
            listOf("Chosen/code", "Rejected*/code"),
            referencesIn(
                "AtLeastOneFieldValueIncludedInValueList(Chosen/code In Rejected*/code)",
                ConditionLanguage.EN,
            ),
        )
    }

    /**
     * The rule-context entity is not a reference, and it is settled per language like every other keyword.
     *
     * It names the declaring element's own group rather than a path, so [referencesIn] cannot report it and a
     * consumer whose law turns on reach asks [namesRuleContext] instead. The cross-language rows are the
     * discriminating ones: each spelling is an ordinary field name in the other language, so a fixed pair of
     * literals would claim a reach an English model naming a field `RegelKontext` does not have.
     */
    @Test
    fun theRuleContextEntityIsAKeywordInItsOwnLanguageOnly() {
        assertEquals(emptyList(), referencesIn("GroupFilled(RuleGroup)", ConditionLanguage.EN))
        assertEquals(listOf("RegelKontext"), referencesIn("FieldFilled(RegelKontext)", ConditionLanguage.EN))
        assertEquals(true, namesRuleContext("GroupFilled(RuleGroup)", ConditionLanguage.EN))
        assertEquals(false, namesRuleContext("FieldFilled(RegelKontext)", ConditionLanguage.EN))
        assertEquals(true, namesRuleContext("KontextAngegeben(RegelKontext)", ConditionLanguage.DE))
        assertEquals(false, namesRuleContext("FeldAngegeben(RuleGroup)", ConditionLanguage.DE))
    }

    /** The same name twice is one reference: it names one element, and the consumer resolves names. */
    @Test
    fun aRepeatedNameIsReportedOnce() {
        assertEquals(
            listOf("Code"),
            referencesIn("FieldFilled(Code) And FieldNotFilled(Code)", ConditionLanguage.EN),
        )
    }

    /**
     * A marked path ends where a name follows a name: the keyword after it belongs to the next production. The
     * run once swallowed it, so `$Orders/Note For "A"` named nothing and its marker went unjudged.
     */
    @Test
    fun aMarkedPathEndsAtTheKeywordAfterIt() {
        val text = "[\$ Orders / Note For \"A\"] == [\$Reference]"
        assertEquals(
            listOf("Orders/Note" to "\$ Orders / Note", "Reference" to "\$Reference"),
            markedReferenceSpansIn(text).map { it.written to text.substring(it.start, it.end) },
        )
    }
}
