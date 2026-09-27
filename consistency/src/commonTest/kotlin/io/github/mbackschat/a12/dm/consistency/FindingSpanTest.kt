package io.github.mbackschat.a12.dm.consistency

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [Finding.span] — which characters of which member a finding is about (slice I-b).
 *
 * **Every row asserts the SLICE, not the offsets.** An expected `start`/`end` pair is unreadable and, worse,
 * a wrong pair that happens to be self-consistent looks right; taking the text's own substring and comparing
 * it against what the finding is about cannot pass for the wrong range. That is also how an editor uses it.
 *
 * The one property that is not about a span at all is [aFindingWhoseTextMerelyMOVEDIsNotANewFinding]: adding
 * a location to a published finding makes findings compare unequal when nothing about the defect changed, so
 * the attribution had to be keyed on the defect. Without that row, every consumer of `checkEdit` would see
 * spurious resolved/introduced pairs on an ordinary keystroke.
 */
class FindingSpanTest {

    @Test
    fun anUnresolvedReferenceIsLocatedAtItsOwnCharacters() {
        val condition = "FieldFilled(Code) And FieldFilled(NoSuchField)"
        val finding = single(condition)

        assertEquals("MVK_INVALID_ENTITY", finding.code)
        assertEquals("errorCondition", finding.span?.member)
        assertEquals("NoSuchField", slice(condition, finding))
    }

    /** The FIRST occurrence, because A12 stops at the first defect in a text and so must a report. */
    @Test
    fun areferenceNamedTwiceIsLocatedAtItsFirstOccurrence() {
        val condition = "FieldFilled(NoSuchField) And FieldNotFilled(NoSuchField)"
        val finding = single(condition)

        assertEquals("NoSuchField", slice(condition, finding))
        assertEquals(12, finding.span?.start, "the first one, not the second: ${finding.span}")
    }

    @Test
    fun aPathReferenceIsLocatedOverTheWHOLERunRatherThanItsLastSegment() {
        val condition = "FieldFilled(../Nope/Deeper)"
        val finding = single(condition)

        // The run a reader has to edit, `..` step included — not the normalized spelling and not one segment.
        assertEquals("../Nope/Deeper", slice(condition, finding))
    }

    @Test
    fun halfTypedTextIsLocatedAtItsEnd() {
        val condition = "FieldFilled(Code) And"
        val finding = single(condition)

        assertEquals("MVK_INCOMPLETE_INPUT", finding.code)
        assertEquals(TextSpan("errorCondition", condition.length, condition.length), finding.span)
    }

    @Test
    fun textTheLexerCannotReadIsLocatedAtTheCharacterItStoppedOn() {
        val condition = "FieldFilled(Code) And §"
        val finding = single(condition)

        assertEquals("MVK_LEXER_STANDARD_ERROR", finding.code)
        assertEquals("§", slice(condition, finding))
    }

    @Test
    fun aShapeTheGrammarRefusesIsLocatedWhereItStands() {
        val condition = "FieldFilled(Code) And And FieldFilled(Code)"
        val finding = single(condition)

        assertEquals("MVK_UNEXPECTED_TOKEN", finding.code)
        assertEquals("And", slice(condition, finding))
        assertEquals(22, finding.span?.start, "the SECOND `And`, which is the one out of place")
    }

    /** The wildcard classes are located at the reference they judge, which is what lets them be ordered. */
    @Test
    fun aMisplacedStarIsLocatedAtItsWholeReference() {
        val condition = "FieldFilled(Code) And GroupFilled(Details*)"
        val finding = single(condition)

        assertEquals("MVK_INVALID_WILDCARD", finding.code)
        assertEquals("Details*", slice(condition, finding))
    }

    @Test
    fun anUnstarredRepeatableReadIsLocatedAtItsWholeReference() {
        val condition = "FieldFilled(Code) And FieldFilled(Lines/Qty)"
        val finding = single(condition)

        assertEquals("MVK_NO_WILDCARD", finding.code)
        assertEquals("Lines/Qty", slice(condition, finding))
    }

    @Test
    fun aParserThatRanOutWithABracketOpenPlacesACaretRatherThanARange() {
        val condition = "FieldFilled(Code"
        val finding = single(condition)

        assertEquals("MVK_EXPECTED_TOKEN_NOT_FOUND", finding.code)
        // Nothing is left to underline, so the range is empty and an editor puts a caret at the end.
        assertEquals("", slice(condition, finding))
        assertEquals(condition.length, finding.span?.end)
    }

    /**
     * A finding about a declaration's STRUCTURE has no characters to point at, and publishes no span rather
     * than an approximate one. Without this row, "spans are populated" could mean "populated with anything".
     */
    @Test
    fun aFindingAboutTheDECLARATIONCarriesNoSpanAtAll() {
        val noTarget = model(condition = "FieldFilled(Code)", errorTarget = "../NoSuchField")
        val findings = checkRead(ModelSource(noTarget)).findings

        assertEquals(TARGET_PAIR, findings.map { it.code })
        findings.forEach { assertNull(it.span, "`errorEntityRelPath` is a structured member, not text with an offset") }
    }

    /**
     * The regression a span would otherwise cause: typing a character EARLIER in a text moves every span
     * after it, so an attribution comparing whole findings would report the same defect as one resolved and
     * one introduced. The defect did not change, and neither may the answer.
     */
    @Test
    fun aFindingWhoseTextMerelyMOVEDIsNotANewFinding() {
        val before = model(condition = "FieldFilled(Code) And FieldFilled(NoSuchField)")
        val after = model(condition = "FieldFilled(Code)  And FieldFilled(NoSuchField)")
        val report = checkEdit(ModelSource(before), ModelSource(after))

        assertEquals(emptyList(), report.introduced, "nothing about the defect changed: ${report.introduced}")
        assertEquals(emptyList(), report.resolved)
        // The control that keeps this row honest: the span DID move, so the findings are genuinely unequal
        // and only the defect-keyed comparison can see past it.
        val spans = listOf(before, after).map { checkRead(ModelSource(it)).findings.single().span?.start }
        assertTrue(spans[0] != spans[1], "the two spans must differ, or this row measures nothing: $spans")
    }

    @Test
    fun aDraftsFindingIsLocatedInTheDraftsOwnText() {
        val report = checkDraft(ModelSource(model()), "/Order/CodeRequired", "errorCondition",
            "FieldFilled(Code) And FieldFilled(Typo)")

        val span = assertNotNull(report.introduced.single().span)
        assertEquals("Typo", "FieldFilled(Code) And FieldFilled(Typo)".substring(span.start, span.end))
    }

    private fun single(condition: String): Finding {
        val findings = checkRead(ModelSource(model(condition = condition))).findings
        assertEquals(1, findings.size, "this fixture must isolate ONE finding: ${findings.map { it.code }}")
        return findings.single()
    }

    /** What the finding says it is about, read out of the text it points into. */
    private fun slice(condition: String, finding: Finding): String {
        val span = finding.span
        assertTrue(span != null, "the finding carries no span: $finding")
        return condition.substring(span.start, span.end)
    }

    private fun model(
        condition: String = "FieldFilled(Code)",
        errorTarget: String = "../Code",
    ) = """
        {"header":{"id":"orders","locales":[{"code":"en_US"}]},
         "content":{"modelRoot":{"rootGroups":[
          {"type":"Group","id":"g0","name":"Order","Group":{"elements":[
            {"type":"Field","id":"f-code","name":"Code","Field":{"fieldType":{"type":"StringType"}}},
            {"type":"Group","id":"g-details","name":"Details","Group":{"elements":[
              {"type":"Field","id":"f-note","name":"Note","Field":{"fieldType":{"type":"StringType"}}}
            ]}},
            {"type":"Group","id":"g-lines","name":"Lines","Group":{"repeatability":9,"elements":[
              {"type":"Field","id":"f-qty","name":"Qty","Field":{"fieldType":{"type":"NumberType"}}}
            ]}},
            {"type":"Rule","id":"r-code","name":"CodeRequired","Rule":{
              "errorCondition":"$condition","errorEntityRelPath":"$errorTarget",
              "errorCode":"C1","errorMessage":[{"locale":"en_US","text":"the order code"}]}}
          ]}}
         ]}}}
    """.trimIndent()
}
