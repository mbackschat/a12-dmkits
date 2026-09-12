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

    /** The same name twice is one reference: it names one element, and the consumer resolves names. */
    @Test
    fun aRepeatedNameIsReportedOnce() {
        assertEquals(
            listOf("Code"),
            referencesIn("FieldFilled(Code) And FieldNotFilled(Code)", ConditionLanguage.EN),
        )
    }
}
