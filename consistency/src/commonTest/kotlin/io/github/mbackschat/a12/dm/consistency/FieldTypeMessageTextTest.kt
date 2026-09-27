package io.github.mbackschat.a12.dm.consistency

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The checker's half of "a field type's own message is text A12's field-message check admits".
 *
 * Every row mirrors one measured against the real Kernel by `rulekit.validate.laws.FieldTypeMessageTextLawsTest`,
 * or by the field column of `…ErrorTextCharacterGateLawsTest` for the characters. The fixtures are built as JSON
 * trees rather than as text, so a control character or a lone surrogate reaches the checker as itself.
 */
class FieldTypeMessageTextTest {

    @Test
    fun whatThePatternsMessageMayCarryBeyondItsCharacters() {
        val actual = linkedMapOf(
            "plain, the control" to onThePattern("capitals only"),
            "\$field\$" to onThePattern("${p("field")} takes capitals"),
            "\$field.value\$" to onThePattern("${p("field.value")} is not capitals"),
            "an odd dollar count" to onThePattern("${D}field takes capitals"),
            "\$Field\$, the wrong case" to onThePattern("${p("Field")} takes capitals"),
            "the field's own name" to onThePattern("${p("Code")} takes capitals"),
            "an empty pair" to onThePattern("cost $D$D in capitals"),
            "the German spelling in an English model" to onThePattern("${p("Feld")} takes capitals"),
            "a tab beside an odd dollar count" to onThePattern("${D}field${TAB}takes capitals"),
            "an odd count beside a wrong name" to onThePattern("${p("Code")} and ${D}field"),
            "German: \$Feld\$" to codes(model(patterned(en("${p("Feld")} takes capitals")), language = "de_DE")),
            "German: \$Feld.Wert\$" to codes(model(patterned(en("${p("Feld.Wert")} is not capitals")), language = "de_DE")),
            "German: \$field\$" to codes(model(patterned(en("${p("field")} takes capitals")), language = "de_DE")),
        )
        val expected = linkedMapOf(
            "plain, the control" to none,
            "\$field\$" to none,
            "\$field.value\$" to none,
            "an odd dollar count" to listOf(DOLLARS),
            "\$Field\$, the wrong case" to listOf(PARAMETER),
            "the field's own name" to listOf(PARAMETER),
            "an empty pair" to listOf(PARAMETER),
            "the German spelling in an English model" to listOf(PARAMETER),
            // The gates are ordered, so the tab answers and the dollar count beside it is never asked.
            "a tab beside an odd dollar count" to listOf(CHARACTERS),
            "an odd count beside a wrong name" to listOf(DOLLARS),
            "German: \$Feld\$" to none,
            "German: \$Feld.Wert\$" to none,
            // The names follow the CONDITION language, so the English spelling is refused in an en_US text.
            "German: \$field\$" to listOf(PARAMETER),
        )
        assertEquals(expected, actual, "the pattern message's parameter gates")
    }

    @Test
    fun whichMessagesItReachesAndHowManyLocalesReport() {
        val both = listOf("en_US", "de_DE")
        val actual = linkedMapOf(
            "both locales defective" to
                codes(model(patterned(en("a${TAB}b"), "de_DE" to "a${LF}b"), locales = both)),
            "the second locale alone defective" to
                codes(model(patterned(en("ab"), "de_DE" to "a${TAB}b"), locales = both)),
            "the enum's invalid-value message, a tab" to codes(model(enumerated("a${TAB}b"), field = "Status"), "Status"),
            "the enum's invalid-value message, a wrong name" to
                codes(model(enumerated("${p("Status")} is not allowed"), field = "Status"), "Status"),
            "the enum's invalid-value message, \$field\$" to
                codes(model(enumerated("${p("field")} is not allowed"), field = "Status"), "Status"),
            "a required field's message, a tab" to codes(model(plainString(), requiredMessage = "a${TAB}b")),
            "both locales defective, declared the other way round" to
                codes(model(patterned(en("a${TAB}b"), "de_DE" to "a${LF}b"), locales = both.reversed())),
            "a message with no pattern, a tab" to codes(model(stringType(null, en("a${TAB}b")))).sorted(),
            "a tab in an undeclared locale's message" to
                codes(model(patterned(en("capitals only"), "fr_FR" to "a${TAB}b"))),
        )
        val expected = linkedMapOf(
            "both locales defective" to listOf(CHARACTERS),
            "the second locale alone defective" to listOf(CHARACTERS),
            "the enum's invalid-value message, a tab" to listOf(CHARACTERS),
            "the enum's invalid-value message, a wrong name" to listOf(PARAMETER),
            "the enum's invalid-value message, \$field\$" to none,
            "a required field's message, a tab" to none,
            "both locales defective, declared the other way round" to listOf(LINE_BREAK),
            // Judged with no pattern beside it, and reported with the pairing class STRING_PATTERN_MESSAGE owns.
            "a message with no pattern, a tab" to listOf(CHARACTERS, "MVK_ERROR_MESSAGE_AND_NO_PATTERN"),
            "a tab in an undeclared locale's message" to none,
        )
        assertEquals(expected, actual, "which field messages the checker reaches")
    }

    /** The two-by-two of declared order against entry order; the entry order alone never decides. */
    @Test
    fun theLocalesAreAskedInDeclaredOrderNotEntryOrder() {
        val enFirst = arrayOf("en_US" to "a${TAB}b", "de_DE" to "a${LF}b")
        val deFirst = arrayOf("de_DE" to "a${LF}b", "en_US" to "a${TAB}b")
        val actual = linkedMapOf(
            "de_DE declared first, the en_US entry written first" to
                codes(model(patterned(*enFirst), locales = listOf("de_DE", "en_US"))),
            "en_US declared first, the de_DE entry written first" to
                codes(model(patterned(*deFirst), locales = listOf("en_US", "de_DE"))),
        )
        val expected = linkedMapOf(
            "de_DE declared first, the en_US entry written first" to listOf(LINE_BREAK),
            "en_US declared first, the de_DE entry written first" to listOf(CHARACTERS),
        )
        assertEquals(expected, actual, "which order the locales are asked in")
    }

    @Test
    fun howTheTextIsCutAndWhichEntryIsExamined() {
        val actual = linkedMapOf(
            "an empty pair at the end" to onThePattern("capitals only $D$D"),
            "an empty pair alone" to onThePattern("$D$D"),
            "an empty pair at the start" to onThePattern("$D$D capitals only"),
            "two parameters back to back" to onThePattern(p("field") + p("field.value")),
            "a lone low surrogate" to onThePattern("a" + Char(0xDE00) + "b").toSet(),
            "two en_US entries, the first defective" to
                codes(model(patterned("en_US" to "a${TAB}b", "en_US" to "capitals only"))),
            "two en_US entries, the second defective" to
                codes(model(patterned("en_US" to "capitals only", "en_US" to "a${TAB}b"))),
        )
        val expected = linkedMapOf(
            "an empty pair at the end" to none,
            "an empty pair alone" to none,
            "an empty pair at the start" to listOf(PARAMETER),
            "two parameters back to back" to none,
            "a lone low surrogate" to setOf("WRONG_ENCODING", RK_UNSUPPORTED_CHARACTER),
            "two en_US entries, the first defective" to none,
            "two en_US entries, the second defective" to listOf(CHARACTERS),
        )
        assertEquals<Map<String, Any>>(expected, actual, "how the text is cut, and which entry is examined")
    }

    /**
     * A12's code generation checks the element container it builds for the default variant, from which an `ONLY`
     * filter deletes the field ([KERNEL-FINDINGS] KF490, KF305), so the deleted field's message is never asked.
     * The kernel row is `rulekit.validate.laws.FieldTypeMessageTextLawsTest`'s "a tab on a field an ONLY filter
     * deletes"; the unfiltered control keeps the fixture able to fail.
     */
    @Test
    fun aFieldAnOnlyFilterDeletesIsNeverJudged() {
        val tabbed = patterned(en("a${TAB}b"))
        val actual = linkedMapOf(
            "unfiltered, the control" to codes(model(tabbed)),
            "deleted by an ONLY filter" to codes(model(tabbed, variantFilter = "ONLY")),
        )
        val expected = linkedMapOf(
            "unfiltered, the control" to listOf(CHARACTERS),
            "deleted by an ONLY filter" to none,
        )
        assertEquals(expected, actual, "a field A12 deletes at the default variant")
    }

    /**
     * This isolates message reach; ElementPathStageLawsTest independently compares the full path/name
     * class sets against the kernel on the R4 carrier population (KF315).
     */
    @Test
    fun anInvalidPathStopsTheCheck() {
        val tabbed = patterned(en("a${TAB}b"))
        val actual = linkedMapOf(
            "a field name outside the path alphabet" to ours(model(tabbed, field = "Code" + Char(0xE9)), "Code" + Char(0xE9)),
            "a digit-initial field name" to ours(model(tabbed, field = "1Code"), "1Code"),
            "a dotted field name" to ours(model(tabbed, field = "Co.de"), "Co.de"),
            "beneath a group outside the path alphabet" to ours(model(tabbed, group = "Part" + Char(0xE9)), "Part" + Char(0xE9) + "/Code"),
            "beneath a group with an umlaut" to ours(model(tabbed, group = "Teil" + Char(0xE4)), "Teil" + Char(0xE4) + "/Code"),
            "a root group with an umlaut" to ours(model(tabbed, root = "Schaden" + Char(0xE4)), "Code", "Schaden" + Char(0xE4)),
            "a digit-initial root group" to ours(model(tabbed, root = "1Claim"), "Code", "1Claim"),
            "a field name with a colon" to ours(model(tabbed, field = "Co:de"), "Co:de"),
            "a field name with a leading underscore" to ours(model(tabbed, field = "_Code"), "_Code"),
            "beneath a digit-initial group" to ours(model(tabbed, group = "1Part"), "1Part/Code"),
            "a field name with a digit after its dot" to ours(model(tabbed, field = "Co.1de"), "Co.1de"),
            "a field name carrying a slash" to ours(model(tabbed, field = "Co/de"), "Co/de"),
            "beneath a group whose name carries a slash" to ours(model(tabbed, group = "Pa/rt"), "Pa/rt/Code"),
        )
        val expected = linkedMapOf(
            "a field name outside the path alphabet" to none,
            "a digit-initial field name" to none,
            "a dotted field name" to listOf(CHARACTERS),
            "beneath a group outside the path alphabet" to none,
            // The group path alphabet takes the German umlauts, the root's too, so these fields are reached.
            "beneath a group with an umlaut" to listOf(CHARACTERS),
            "a root group with an umlaut" to listOf(CHARACTERS),
            "a digit-initial root group" to listOf(CHARACTERS),
            "a field name with a colon" to listOf(CHARACTERS),
            "a field name with a leading underscore" to listOf(CHARACTERS),
            "beneath a digit-initial group" to none,
            "a field name with a digit after its dot" to none,
            // The separator in a name stops the walk as MVK_PATHS_INCONSISTENT, the enclosing group's included.
            "a field name carrying a slash" to none,
            "beneath a group whose name carries a slash" to none,
        )
        assertEquals(expected, actual, "whether an invalid path stops the check")
    }

    /**
     * A rule's refusal elsewhere in the model withholds nothing here, since a field has no condition to wait on:
     * these rows lock field-message independence from rule-condition execution.
     */
    @Test
    fun aRulesRefusalElsewhereWithholdsNothing() {
        fun beside(condition: String, target: String): List<String> {
            val rule = buildJsonObject {
                put("type", "Rule")
                put("id", "r0")
                put("name", "ProbeRule")
                putJsonObject("Rule") {
                    put("errorCondition", condition)
                    put("errorEntityRelPath", target)
                    put("errorCode", "PROBE")
                    putMessages(arrayOf(en("see the reference")))
                }
            }
            return codes(model(patterned(en("a${TAB}b")), extra = rule))
        }
        assertEquals(
            listOf(listOf(CHARACTERS), listOf(CHARACTERS)),
            listOf(
                beside("FieldFilled(Reference) And And FieldFilled(Code)", "../Reference"),
                beside("FieldFilled(Reference)", "../NoSuchField"),
            ),
        )
    }

    /** The field column of `…ErrorTextCharacterGateLawsTest`, where the encoding is asked ahead of both gates. */
    @Test
    fun whichCharactersEachGateRefuses() {
        val rows = linkedMapOf(
            "plain" to "plain",
            "U+2028 LINE SEPARATOR" to around(0x2028),
            "U+2029 PARAGRAPH SEPARATOR" to around(0x2029),
            "a line feed" to around(0x0A),
            "a tab" to around(0x09),
            "U+0085 NEXT LINE" to around(0x85),
            "U+200B ZERO WIDTH SPACE" to around(0x200B),
            "U+00AD SOFT HYPHEN" to around(0xAD),
            "U+FEFF ZERO WIDTH NO-BREAK SPACE" to around(0xFEFF),
            "U+200E LEFT-TO-RIGHT MARK" to around(0x200E),
            "U+E000 private use" to around(0xE000),
            "U+0378 unassigned" to around(0x378),
            "U+00A0 NO-BREAK SPACE" to around(0xA0),
            "a lone high surrogate" to around(0xD83D),
            "a supplementary private-use character" to "a" + Char(0xDB80) + Char(0xDC00) + "b",
            "a supplementary character outside \\p{C}" to "a" + Char(0xD83D) + Char(0xDE00) + "b",
            "U+E0001 LANGUAGE TAG" to "a" + Char(0xDB40) + Char(0xDC01) + "b",
            "U+2028 beside a tab" to "a${TAB}b" + Char(0x2028) + "c",
        )
        val actual = rows.mapValues { (_, text) -> onThePattern(text).toSet() }
        val expected = rows.mapValues { setOf(CHARACTERS) }.toMutableMap()
        listOf("plain", "U+00A0 NO-BREAK SPACE").forEach { expected[it] = emptySet() }
        listOf("U+2028 LINE SEPARATOR", "U+2029 PARAGRAPH SEPARATOR", "a line feed", "U+2028 beside a tab")
            .forEach { expected[it] = setOf(LINE_BREAK) }
        expected["a lone high surrogate"] = setOf("WRONG_ENCODING", RK_UNSUPPORTED_CHARACTER)
        expected["a supplementary private-use character"] = setOf(CHARACTERS, RK_UNSUPPORTED_CHARACTER)
        // \p{C} is asked of a code point, so a pair outside it draws only the charset refusal.
        expected["a supplementary character outside \\p{C}"] = setOf(RK_UNSUPPORTED_CHARACTER)
        expected["U+E0001 LANGUAGE TAG"] = setOf(CHARACTERS, RK_UNSUPPORTED_CHARACTER)
        assertEquals<Map<String, Set<String>>>(expected, actual, "the field message's character gates")
    }

    /** The finding names the locale and the code point, since a control character cannot be seen in the text. */
    @Test
    fun theFindingNamesTheLocaleAndTheCodePoint() {
        val finding = checkRead(ModelSource(model(patterned(en("see" + Char(0x07) + "x"))))).findings
            .single { it.code == CHARACTERS }
        assertEquals(true, "`en_US`" in finding.explain && "U+0007" in finding.explain, finding.explain)
    }

    private fun onThePattern(text: String): List<String> = codes(model(patterned(en(text))))

    private fun codes(json: String, at: String = "Code"): List<String> =
        checkRead(ModelSource(json)).findings.filter { it.at == "/Claim/$at" }.map { it.code }

    private fun ours(json: String, at: String, root: String = "Claim"): List<String> =
        checkRead(ModelSource(json)).findings.filter { it.at == "/$root/$at" && it.code in OURS }.map { it.code }

    private fun around(codePoint: Int): String = "a" + Char(codePoint) + "b"

    private fun p(name: String): String = "$D$name$D"

    private fun en(text: String): Pair<String, String> = "en_US" to text

    private fun patterned(vararg messages: Pair<String, String>): JsonObject = stringType("[A-Z]+", *messages)

    private fun plainString(): JsonObject = buildJsonObject { put("type", "StringType") }

    private fun stringType(pattern: String?, vararg messages: Pair<String, String>): JsonObject = buildJsonObject {
        put("type", "StringType")
        putJsonObject("StringType") {
            pattern?.let { put("pattern", it) }
            putMessages(messages)
        }
    }

    private fun enumerated(message: String): JsonObject = buildJsonObject {
        put("type", "EnumerationType")
        putJsonObject("EnumerationType") {
            putJsonArray("values") {
                addJsonObject { put("value", "OPEN") }
                addJsonObject { put("value", "CLOSED") }
            }
            putMessages(arrayOf(en(message)))
        }
    }

    private fun JsonObjectBuilder.putMessages(messages: Array<out Pair<String, String>>) {
        putJsonArray("errorMessage") {
            messages.forEach { (locale, text) ->
                addJsonObject {
                    put("locale", locale)
                    put("text", text)
                }
            }
        }
    }

    /**
     * A claims model whose field of the given name, in the root group or a group beneath it, has [type]; an
     * [extra] element joins the root group beside it.
     */
    private fun model(
        type: JsonObject,
        locales: List<String> = listOf("en_US"),
        language: String? = null,
        root: String = "Claim",
        group: String? = null,
        field: String = "Code",
        requiredMessage: String? = null,
        extra: JsonObject? = null,
        variantFilter: String? = null,
    ): String = buildJsonObject {
        putJsonObject("header") {
            put("id", "claims-field-messages")
            putJsonArray("locales") { locales.forEach { addJsonObject { put("code", it) } } }
        }
        putJsonObject("content") {
            language?.let { putJsonObject("modelConfig") { putJsonObject("conditionLanguage") { put("code", it) } } }
            putJsonObject("modelRoot") {
                putJsonArray("rootGroups") {
                    addJsonObject {
                        put("type", "Group")
                        put("id", "g0")
                        put("name", root)
                        putJsonObject("Group") {
                            putJsonArray("elements") {
                                addJsonObject {
                                    put("type", "Field")
                                    put("id", "f0")
                                    put("name", "Reference")
                                    putJsonObject("Field") { putJsonObject("fieldType") { put("type", "StringType") } }
                                }
                                val probe = buildJsonObject {
                                    put("type", "Field")
                                    put("id", "f1")
                                    put("name", field)
                                    putJsonObject("Field") {
                                        put("fieldType", type)
                                        variantFilter?.let { putJsonObject("variantFilter") { put("filterType", it) } }
                                        requiredMessage?.let { text ->
                                            putJsonObject("requirednessConfig") {
                                                put("mode", "absoluteOrRelativeToNextRepAncestor")
                                                putMessages(arrayOf(en(text)))
                                            }
                                        }
                                    }
                                }
                                if (group == null) {
                                    add(probe)
                                } else {
                                    addJsonObject {
                                        put("type", "Group")
                                        put("id", "g1")
                                        put("name", group)
                                        putJsonObject("Group") { putJsonArray("elements") { add(probe) } }
                                    }
                                }
                                extra?.let { add(it) }
                            }
                        }
                    }
                }
            }
        }
    }.toString()

    private companion object {
        const val D = '$'
        val TAB = Char(0x09).toString()
        val LF = Char(0x0A).toString()
        const val LINE_BREAK = "INVALID_LINEBREAK"
        const val CHARACTERS = "INVALID_CHARACTERS"
        const val DOLLARS = "WRONG_NUMBER_OF_DOLLARS"
        const val PARAMETER = "INVALID_PARAMETER"
        val none = emptyList<String>()
        val OURS = setOf("WRONG_ENCODING", LINE_BREAK, CHARACTERS, DOLLARS, PARAMETER)
    }
}
