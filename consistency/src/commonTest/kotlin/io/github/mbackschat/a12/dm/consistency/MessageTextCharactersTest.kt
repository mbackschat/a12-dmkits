package io.github.mbackschat.a12.dm.consistency

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The checker's half of "an error message's text carries no control character".
 *
 * Every row mirrors one measured against the real Kernel by
 * `rulekit.validate.laws.MessageTextControlCharacterLawsTest`. The row with teeth is the one carrying BOTH
 * kinds: A12 runs the two gates in order and answers with the line-break class alone, so reporting each kind
 * found would name a verdict it did not give.
 */
class MessageTextCharactersTest {

    private fun codes(text: String): List<String> =
        checkRead(ModelSource(model(text))).findings.filter { it.at == "/Policy/Probe" }.map { it.code }

    private fun around(codePoint: Int): List<String> = codes("see" + codePoint.toChar() + "the reference")

    @Test
    fun whichControlCharactersThisFamilyReports() {
        val actual = mutableMapOf<String, List<String>>()
        actual["printable text — the control"] = codes("see the reference")
        actual["a line feed"] = around(0x0A)
        actual["a carriage return"] = around(0x0D)
        actual["a tab"] = around(0x09)
        actual["a NUL"] = around(0x00)
        actual["a DEL"] = around(0x7F)
        actual["the last C0 control"] = around(0x1F)
        actual["a plain space"] = around(0x20)
        actual["the first C1 control"] = around(0x80)
        actual["the last C1 control"] = around(0x9F)
        actual["a non-breaking space"] = around(0xA0)
        actual["a line break AND a control character"] =
            codes("see" + 0x0A.toChar() + "the" + 0x09.toChar() + "reference")
        actual["a control character BEFORE a line break"] =
            codes("see" + 0x09.toChar() + "the" + 0x0A.toChar() + "reference")

        val expected = mutableMapOf<String, List<String>>()
        expected["printable text — the control"] = emptyList()
        expected["a line feed"] = listOf(lineBreak)
        expected["a carriage return"] = listOf(lineBreak)
        expected["a tab"] = listOf(characters)
        expected["a NUL"] = listOf(characters)
        expected["a DEL"] = listOf(characters)
        expected["the last C0 control"] = listOf(characters)
        // Both ends of the control blocks are measured: the space and the non-breaking space sit outside
        // them, so a predicate reaching either would refuse text A12 admits.
        expected["a plain space"] = emptyList()
        expected["the first C1 control"] = listOf(characters)
        expected["the last C1 control"] = listOf(characters)
        expected["a non-breaking space"] = emptyList()
        // ONE finding, and the line-break one: the gates are ordered, so the tab beside it draws nothing.
        expected["a line break AND a control character"] = listOf(lineBreak)
        // The same pair reversed, which is the row that discriminates: the gate is keyed on the KIND
        // present, not on which character comes first, so taking the first offending one would report the
        // wrong class here while agreeing on every row where the break comes first.
        expected["a control character BEFORE a line break"] = listOf(lineBreak)
        assertEquals(expected, actual, "which control characters this family reports")
    }

    /**
     * The characters the ISO blocks miss, each row one of `rulekit.validate.laws.ErrorTextCharacterGateLawsTest`
     * on a rule's message. A12's two tests are LF, CR and the two Unicode separators, then `\p{C}` asked of each
     * code point, so a supplementary character outside that category draws only the charset refusal.
     */
    @Test
    fun theSetsAreA12sOwnPredicatesAskedOfCodePoints() {
        val rows = linkedMapOf(
            "U+2028 LINE SEPARATOR" to around(0x2028),
            "U+2029 PARAGRAPH SEPARATOR" to around(0x2029),
            "U+0085 NEXT LINE" to around(0x85),
            "U+200B ZERO WIDTH SPACE" to around(0x200B),
            "U+00AD SOFT HYPHEN" to around(0xAD),
            "U+FEFF ZERO WIDTH NO-BREAK SPACE" to around(0xFEFF),
            "U+200E LEFT-TO-RIGHT MARK" to around(0x200E),
            "U+E000 private use" to around(0xE000),
            "U+0378 unassigned" to around(0x378),
            "a lone high surrogate" to around(0xD83D),
            "a supplementary private-use character" to codes("see\uDB80\uDC00the reference"),
            "a supplementary character outside \\p{C}" to codes("see\uD83D\uDE00the reference"),
            "U+E0001 LANGUAGE TAG" to codes("see\uDB40\uDC01the reference"),
            "U+2028 beside a tab" to codes("see" + 0x09.toChar() + "the" + 0x2028.toChar() + "reference"),
        ).mapValues { it.value.toSet() }
        val expected = linkedMapOf(
            "U+2028 LINE SEPARATOR" to setOf(lineBreak),
            "U+2029 PARAGRAPH SEPARATOR" to setOf(lineBreak),
            "U+0085 NEXT LINE" to setOf(characters),
            "U+200B ZERO WIDTH SPACE" to setOf(characters),
            "U+00AD SOFT HYPHEN" to setOf(characters),
            "U+FEFF ZERO WIDTH NO-BREAK SPACE" to setOf(characters),
            "U+200E LEFT-TO-RIGHT MARK" to setOf(characters),
            "U+E000 private use" to setOf(characters),
            "U+0378 unassigned" to setOf(characters),
            // Asked of the encoding before \p{C} on kernel 31.1.1 (KERNEL-FINDINGS KF657).
            "a lone high surrogate" to setOf(unsupported, "WRONG_ENCODING"),
            "a supplementary private-use character" to setOf(unsupported, characters),
            "a supplementary character outside \\p{C}" to setOf(unsupported),
            "U+E0001 LANGUAGE TAG" to setOf(unsupported, characters),
            "U+2028 beside a tab" to setOf(lineBreak),
        )
        assertEquals(expected, rows, "the characters the ISO control blocks miss")
    }

    /** The wider set withholds the parameter classes as the ISO blocks did, since A12 stops at its first gate. */
    @Test
    fun theWiderSetWithholdsTheParameterClassesToo() {
        assertEquals(
            listOf(listOf("INVALID_ENTITY"), listOf(characters), listOf(lineBreak), listOf(characters)),
            listOf(
                codes("see \$NoSuchField\$"),
                codes("see \$NoSuchField\$ a" + 0x200B.toChar() + "b"),
                codes("see \$NoSuchField\$ a" + 0x2028.toChar() + "b"),
                codes("see \$Reference a" + 0x200B.toChar() + "b"),
            ),
        )
    }

    /** A COMPUTATION's message meets the same gate, so a family checking only rules would under-report. */
    @Test
    fun aComputationsMessageMeetsTheSameGate() {
        val actual = mutableMapOf<String, List<String>>()
        actual["printable — the control"] = computing("see the reference")
        actual["a line feed"] = computing("see" + 0x0A.toChar() + "the reference")
        actual["a tab"] = computing("see" + 0x09.toChar() + "the reference")
        actual["a paragraph separator"] = computing("see" + 0x2029.toChar() + "the reference")
        actual["a zero width no-break space"] = computing("see" + 0xFEFF.toChar() + "the reference")

        val expected = mutableMapOf<String, List<String>>()
        expected["printable — the control"] = emptyList()
        expected["a line feed"] = listOf(lineBreak)
        expected["a tab"] = listOf(characters)
        expected["a paragraph separator"] = listOf(lineBreak)
        expected["a zero width no-break space"] = listOf(characters)
        assertEquals(expected, actual, "a computation's message meets the same gate")
    }

    /**
     * A declaration whose CONDITION is unsound has no character verdict either.
     *
     * The same gate as the parameter family, because it is A12 declining to examine the message member at
     * all — measured rather than carried over, since this check needs nothing from the condition and could
     * as easily have run regardless.
     */
    @Test
    fun aDeclarationWhoseConditionIsUnsoundHasNoCharacterVerdict() {
        val text = "see" + 0x09.toChar() + "the reference"
        assertEquals(emptyList(), checkRead(ModelSource(model(text, condition = "FieldFilled(NoSuchField)")))
            .findings.filter { it.at == "/Policy/Probe" && it.code == characters }.map { it.code })
        assertEquals(listOf(characters), codes(text))
    }

    /** The finding names the code point, since a control character is invisible in the text it came from. */
    @Test
    fun theFindingNamesTheCodePointBecauseTheCharacterCannotBeSeen() {
        val finding = checkRead(ModelSource(model("see" + 0x07.toChar() + "x")))
            .findings.single { it.code == characters }
        assertEquals(true, finding.explain.contains("U+0007"), finding.explain)
    }

    private val lineBreak = "INVALID_LINEBREAK"
    private val characters = "INVALID_CHARACTERS"
    private val unsupported = "RK_UNSUPPORTED_CHARACTER"

    private fun computing(text: String): List<String> =
        checkRead(ModelSource(model(text, computed = true))).findings
            .filter { it.at == "/Policy/Probe" }.map { it.code }

    private fun model(text: String, computed: Boolean = false,
                      condition: String = "FieldFilled(Reference)"): String {
        val escaped = text.map { character ->
            if (character.code < 0x20 || character.code == 0x7F) {
                "\\u" + character.code.toString(16).padStart(4, '0')
            } else {
                character.toString()
            }
        }.joinToString("")
        val declaration = if (computed) {
            """{"type":"Computation","id":"c0","name":"Probe","Computation":{
                 "computedFieldRelPath":"../Total",
                 "computationAlternatives":[{"operation":"1"}],
                 "errorMessage":[{"locale":"en_US","text":"$escaped"}]}}"""
        } else {
            """{"type":"Rule","id":"r0","name":"Probe","Rule":{
                 "errorCondition":"$condition","errorEntityRelPath":"../Reference",
                 "errorCode":"C1","errorMessage":[{"locale":"en_US","text":"$escaped"}]}}"""
        }
        return """
            {"header":{"modelVersion":"29.4.0","id":"policy","locales":[{"code":"en_US"}]},
             "content":{"modelInfo":{"name":"model"},"modelRoot":{"rootGroups":[
              {"type":"Group","id":"g0","name":"Policy","Group":{"elements":[
                {"type":"Field","id":"f0","name":"Reference","Field":{"fieldType":{"type":"StringType"}}},
                {"type":"Field","id":"f1","name":"Total","Field":{"fieldType":{"type":"NumberType"}}},
                $declaration
              ]}}
             ]}}}
        """.trimIndent()
    }
}
