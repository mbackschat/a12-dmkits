package io.github.mbackschat.a12.dm.consistency

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * C's first family: the declaration SLOTS of a stored rule or computation.
 *
 * Every row's verdict is the Kernel's, measured on both carriers by `RuleSlotLawsTest` and
 * `ComputationSlotLawsTest`, and on the raw stored shapes by `MessageSlotRawShapeLawsTest` (kernel 30.8.1,
 * 2026-09-12). The rows A12 ADMITS carry as much weight as the refusals: the Java builders refuse several of
 * them, and reproducing a builder's policy as an A12 rejection is the failure this family exists to avoid.
 *
 * Each table states one verdict per shape rather than one test per shape, because the claim is about which
 * shapes are SEPARATED — a per-row test set can be individually green while collapsing two shapes onto one
 * answer.
 */
class DeclarationSlotTest {

    /** Both carriers, so no row can be measured on one kind and assumed for the other. */
    private val kinds = listOf("Rule", "Computation")

    /**
     * A one-declaration model.
     *
     * @param locales the body of `header.locales`, raw, because the entry shapes A12 admits are part of what
     *     is under test — an object carrying `code`, a bare string, or a spelling its reader refuses.
     * @param messages the whole `errorMessage` member, raw for the same reason, or null to omit it.
     * @param slots the element's OTHER members, raw. It defaults to what the kind needs to be legal — a rule
     *     owes an `errorCode`, a computation owes nothing — so a row that does not name this parameter is
     *     varying only its message slot.
     */
    private fun model(
        kind: String,
        name: String,
        messages: String?,
        locales: String = EN_AND_DE,
        slots: String = legalSlotsFor(kind),
    ) = prepare(
        assertIs<Admission.Admitted>(
            admit(
                ModelSource(
                    """
                    {"header":{"id":"catalog","locales":[$locales]},
                     "content":{"modelRoot":{"rootGroups":[
                      {"type":"Group","id":"g0","name":"Catalog","Group":{"elements":[
                        {"type":"Field","id":"f0","name":"Price","Field":{"fieldType":{"type":"StringType"}}},
                        {"type":"$kind","id":"x0","name":"$name","$kind":{${
                        listOfNotNull(messages?.let { """"errorMessage":$it""" }, slots.ifEmpty { null })
                            .joinToString(",")
                    }}}
                      ]}}
                     ]}}}
                    """.trimIndent(),
                ),
            ),
        ),
    )

    /** The codes reported for one shape, in report order, so a missing and an extra row both show. */
    private fun codesFor(
        kind: String,
        name: String,
        messages: String?,
        locales: String = EN_AND_DE,
        slots: String = legalSlotsFor(kind),
    ) = model(kind, name, messages, locales, slots).declarationSlotFindings().map { it.code }

    @Test
    fun eachSlotShapeDrawsTheVerdictTheKernelGivesIt() {
        for (kind in kinds) {
            val actual = mutableMapOf<String, List<String>>()
            actual["both locales covered — the control"] = codesFor(kind, "PriceCheck", BOTH)
            actual["reserved vk_vk prefix"] = codesFor(kind, "vk_vk_probe", BOTH)
            actual["reserved prefix in another letter case"] = codesFor(kind, "VK_VK_probe", BOTH)
            actual["no message entries at all"] = codesFor(kind, "PriceCheck", "[]")
            actual["no errorMessage member at all"] = codesFor(kind, "PriceCheck", null)
            actual["every text blank"] = codesFor(kind, "PriceCheck", """[$EN_BLANK,$DE_BLANK]""")
            actual["one declared locale blank"] = codesFor(kind, "PriceCheck", """[$EN,$DE_BLANK]""")
            actual["one declared locale absent"] = codesFor(kind, "PriceCheck", "[$EN]")
            actual["undeclared locale beside both"] = codesFor(kind, "PriceCheck", """[$EN,$DE,$FR]""")
            actual["duplicate locale"] = codesFor(kind, "PriceCheck", """[$EN,$EN,$DE]""")

            val expected = mapOf(
                "both locales covered — the control" to listOf(),
                "reserved vk_vk prefix" to listOf("MVK_RULE_NAME_INVALID"),
                "reserved prefix in another letter case" to listOf("MVK_RULE_NAME_INVALID"),
                "no message entries at all" to listOf("MVK_ERROR_MESSAGE_MISSING"),
                "no errorMessage member at all" to listOf("MVK_ERROR_MESSAGE_MISSING"),
                "every text blank" to listOf("MVK_ERROR_MESSAGE_MISSING"),
                "one declared locale blank" to listOf("MVK_ERROR_MESSAGE_FOR_LANGUAGE_MISSING"),
                "one declared locale absent" to listOf("MVK_ERROR_MESSAGE_FOR_LANGUAGE_MISSING"),
                "undeclared locale beside both" to listOf("RK_UNDECLARED_MESSAGE_LOCALE"),
                "duplicate locale" to listOf("MVK_LANGUAGE_DUPLICATE"),
            )
            assertEquals(expected, actual, "declaration slots on a $kind")
        }
    }

    /**
     * Which entry survives for one locale, and what counts as a text.
     *
     * The middle row is the discriminator: a reader that keeps the FIRST usable entry sees a message where
     * A12 keeps the LAST and finds none. The row below it is the control that ORDER, not the duplication,
     * is what decides — both rows carry the same two entries, in opposite order.
     */
    @Test
    fun theLastEntryForOneLocaleIsTheOneA12Keeps() {
        for (kind in kinds) {
            val actual = mutableMapOf<String, List<String>>()
            actual["duplicate, both filled"] = codesFor(kind, "PriceCheck", """[$EN,$EN_OTHER,$DE]""")
            actual["duplicate, LATER blank"] = codesFor(kind, "PriceCheck", """[$EN,$EN_BLANK,$DE]""")
            actual["duplicate, EARLIER blank"] = codesFor(kind, "PriceCheck", """[$EN_BLANK,$EN,$DE]""")

            val expected = mapOf(
                "duplicate, both filled" to listOf("MVK_LANGUAGE_DUPLICATE"),
                "duplicate, LATER blank" to
                    listOf("MVK_ERROR_MESSAGE_FOR_LANGUAGE_MISSING", "MVK_LANGUAGE_DUPLICATE"),
                "duplicate, EARLIER blank" to listOf("MVK_LANGUAGE_DUPLICATE"),
            )
            assertEquals(expected, actual, "which entry for one locale survives, on a $kind")
        }
    }

    /**
     * A locale is matched as a parsed locale KEY on both sides, never as text.
     *
     * Comparing the spellings would report a coverage gap for a model A12 admits — the direction the Kernel
     * is never asked about, and therefore the one no consumer can detect. The `errorMessage` member's own
     * shape rides here too: an OBJECT yields entries exactly as an array does, because A12's reader iterates
     * the node's elements and an object's elements are its values.
     */
    @Test
    fun aLocaleIsMatchedAsAParsedLocaleAndNotAsItsSpelling() {
        for (kind in kinds) {
            val actual = mutableMapOf<String, List<String>>()
            actual["declared en-US, message en_US"] = codesFor(kind, "PriceCheck", "[$EN]", """{"code":"en-US"}""")
            actual["declared en_US, message en-US"] =
                codesFor(kind, "PriceCheck", """[{"locale":"en-US","text":"too high"}]""", """{"code":"en_US"}""")
            actual["declared as bare strings"] = codesFor(kind, "PriceCheck", BOTH, """"en_US","de_DE"""")
            actual["duplicate spelled two ways"] =
                codesFor(kind, "PriceCheck", """[$EN,{"locale":"en-US","text":"high"},$DE]""")
            actual["errorMessage as an object"] = codesFor(kind, "PriceCheck", """{"a":$EN,"b":$DE}""")

            val expected = mapOf(
                "declared en-US, message en_US" to listOf(),
                "declared en_US, message en-US" to listOf(),
                "declared as bare strings" to listOf(),
                "duplicate spelled two ways" to listOf("MVK_LANGUAGE_DUPLICATE"),
                "errorMessage as an object" to listOf(),
            )
            assertEquals(expected, actual, "locale matching on a $kind")
        }
    }

    /**
     * A shape A12's own READER refuses is not a verdict about the model, so it may never draw an `MVK_*`
     * code: no rule was examined and no consistency result exists.
     *
     * This is the tier a checker collapses by accident. Reading a locale-less entry as "no message" and
     * reporting `MVK_ERROR_MESSAGE_MISSING` is locally plausible, individually green, and presents this
     * checker's own reading as A12's about a model A12 never loaded.
     */
    @Test
    fun aShapeA12sReaderRefusesIsReportedAsSuchRatherThanAsAMissingMessage() {
        for (kind in kinds) {
            val actual = mutableMapOf<String, List<String>>()
            actual["entry without a locale"] = codesFor(kind, "PriceCheck", """[{"text":"x"}]""")
            actual["entry without a text"] = codesFor(kind, "PriceCheck", """[{"locale":"en_US"}]""")
            actual["locale as a number"] = codesFor(kind, "PriceCheck", """[{"locale":5,"text":"x"}]""")
            actual["text as a number"] = codesFor(kind, "PriceCheck", """[{"locale":"en_US","text":5}]""")
            actual["entry that is not an object"] = codesFor(kind, "PriceCheck", """["x"]""")
            actual["locale spelled en_us"] = codesFor(kind, "PriceCheck", """[{"locale":"en_us","text":"x"}]""")
            actual["the empty locale code"] = codesFor(kind, "PriceCheck", """[{"locale":"","text":"x"}]""")

            val refused = listOf("CK_MODEL_NOT_BUILDABLE")
            val expected = mapOf(
                "entry without a locale" to refused,
                "entry without a text" to refused,
                "locale as a number" to refused,
                "text as a number" to refused,
                "entry that is not an object" to refused,
                "locale spelled en_us" to refused,
                // The one spelling A12 READS without producing a locale: the entry covers nothing, so the
                // declaration is message-less — a verdict, not a refusal, and the row that keeps the table
                // above from being stated one notch too wide.
                "the empty locale code" to listOf("MVK_ERROR_MESSAGE_MISSING"),
            )
            assertEquals(expected, actual, "shapes A12's reader refuses, on a $kind")
        }
    }

    /**
     * What the model's own `header.locales` costs it, which decides what every message slot answers to.
     *
     * The no-locale rows are the ones on the other side of the axis every two-locale fixture above shares.
     * They are also the rows that corrected this checker: a model declaring no locale collects NO message at
     * all, so a rule carrying one is message-less just the same — "one message is enough" was this author's
     * prediction and the Kernel's answer was `MVK_SUPP_LANGUAGES_MISSING,MVK_ERROR_MESSAGE_MISSING`.
     */
    @Test
    fun theDeclaredLocaleSetIsJudgedBeforeAnyMessageIs() {
        for (kind in kinds) {
            val actual = mutableMapOf<String, List<String>>()
            actual["no declared locale, one message"] = codesFor(kind, "PriceCheck", "[$EN]", locales = "")
            actual["no declared locale, no message"] = codesFor(kind, "PriceCheck", "[]", locales = "")
            actual["declared code en_us"] = codesFor(kind, "PriceCheck", "[$EN]", """{"code":"en_us"}""")
            actual["declared code empty"] = codesFor(kind, "PriceCheck", "[$EN]", """{"code":""}""")

            val noLocale = listOf("MVK_SUPP_LANGUAGES_MISSING", "MVK_ERROR_MESSAGE_MISSING")
            // A code A12 cannot use leaves the key set empty too, so the model-level row names the member
            // that is actually wrong and the per-declaration row rides as the checker's own reading — which
            // is what the report's coverage gap says about every finding beside a CK_MODEL_NOT_BUILDABLE.
            val unusableCode = listOf("CK_MODEL_NOT_BUILDABLE", "MVK_ERROR_MESSAGE_MISSING")
            val expected = mapOf(
                "no declared locale, one message" to noLocale,
                "no declared locale, no message" to noLocale,
                "declared code en_us" to unusableCode,
                "declared code empty" to unusableCode,
            )
            assertEquals(expected, actual, "the declared locale set on a $kind")
        }
    }

    /** A rule's `errorCode` — required non-blank, coerced from a scalar, and refused as an explicit null. */
    @Test
    fun aRulesErrorCodeIsRequiredNonBlank() {
        val actual = mutableMapOf<String, List<String>>()
        actual["a code — the control"] = codesForRule(""""errorCode":"PROBE"""")
        actual["no errorCode member"] = codesForRule("")
        actual["a blank errorCode"] = codesForRule(""""errorCode":"   """")
        actual["a numeric errorCode"] = codesForRule(""""errorCode":7""")
        actual["an explicitly null errorCode"] = codesForRule(""""errorCode":null""")

        val expected = mapOf(
            "a code — the control" to listOf(),
            "no errorCode member" to listOf("MVK_ERROR_CODE_MISSING"),
            "a blank errorCode" to listOf("MVK_ERROR_CODE_MISSING"),
            // Coerced by A12 to the code "7", so refusing it would reject a model the Kernel admits.
            "a numeric errorCode" to listOf(),
            "an explicitly null errorCode" to listOf("CK_MODEL_NOT_BUILDABLE"),
        )
        assertEquals(expected, actual, "a rule's error code slot")
    }

    /**
     * A rule's `severity` is an ENUM binding, so none of `errorCode`'s leniencies above carry across.
     *
     * Every row here was measured (`rulekit.validate.laws.RuleCodeAndSeverityLawsTest`), because each is a
     * place the sibling slot behaves differently and a checker sharing one predicate would be wrong: there is
     * no coercion, blank does not count as absent, and the match is case-SENSITIVE. The verdict for every
     * refused spelling is the READER's, not A12's opinion of the rule, so it is [CK_MODEL_NOT_BUILDABLE] and
     * never `MVK_SEVERITY_MISSING` — which `Rule.Builder` raises and the Kernel raises for no shape at all.
     */
    @Test
    fun aRulesSeverityIsAStrictEnumAndOnlyItsAbsenceIsLegal() {
        fun withSeverity(raw: String) = codesForRule(""""errorCode":"PROBE","severity":$raw""")
        val actual = mutableMapOf<String, List<String>>()
        actual["ERROR"] = withSeverity(""""ERROR"""")
        actual["WARNING"] = withSeverity(""""WARNING"""")
        actual["INFO"] = withSeverity(""""INFO"""")
        // The one shape A12 accepts without the member: it defaults to ERROR.
        actual["absent"] = codesForRule(""""errorCode":"PROBE"""")
        actual["FATAL — outside the enum"] = withSeverity(""""FATAL"""")
        actual["Error — the right constant, wrong case"] = withSeverity(""""Error"""")
        actual["lowercase error"] = withSeverity(""""error"""")
        actual["blank"] = withSeverity("""""""")
        actual["whitespace only"] = withSeverity(""""   """")
        actual["a number"] = withSeverity("7")
        actual["an explicit null"] = withSeverity("null")
        actual["a JSON container"] = withSeverity("""{"code":"ERROR"}""")

        val unreadable = listOf("CK_MODEL_NOT_BUILDABLE")
        val expected = mapOf(
            "ERROR" to listOf(),
            "WARNING" to listOf(),
            "INFO" to listOf(),
            "absent" to listOf(),
            "FATAL — outside the enum" to unreadable,
            "Error — the right constant, wrong case" to unreadable,
            "lowercase error" to unreadable,
            "blank" to unreadable,
            "whitespace only" to unreadable,
            "a number" to unreadable,
            "an explicit null" to unreadable,
            // The one member of this checker's standing container gap that IS decided.
            "a JSON container" to unreadable,
        )
        assertEquals(expected, actual, "a rule's severity slot")
    }

    /** A computation declares no `severity`, so one spelled there is not this family's to judge. */
    @Test
    fun aComputationsSeverityIsNotJudged() {
        assertEquals(
            listOf(),
            codesFor("Computation", "PriceCheck", BOTH, slots = """"severity":"FATAL""""),
            "a computation carries no severity member for A12 to bind",
        )
    }

    /** A computation carries neither member, and one carrying neither is admitted — so nothing is reported. */
    @Test
    fun aComputationHasNoErrorCodeSlotToCheck() {
        assertEquals(listOf(), codesFor("Computation", "PriceCheck", BOTH), "a computation owes no code")
    }

    /** A rule whose non-message slots are exactly [slots], so one member moves per row. */
    private fun codesForRule(slots: String) = codesFor("Rule", "PriceCheck", BOTH, slots = slots)

    /** Only an A12 constraint may reject: the two shapes A12 admits must leave the model checkable. */
    @Test
    fun theAdmittedShapesAreAdvisoryRatherThanRejections() {
        for (kind in kinds) {
            val advisory = model(kind, "PriceCheck", """[$EN,$EN,$DE,$FR]""").declarationSlotFindings()

            assertEquals(2, advisory.size, "a duplicate and an undeclared locale on a $kind: $advisory")
            assertTrue(advisory.none { it.justifiesRejection }, "neither may reject on a $kind: $advisory")
            assertEquals(setOf(FindingCategory.AUTHORING_POLICY), advisory.map { it.category }.toSet())
        }
    }

    /** A field or a group carries no such slots, so the family must not read one into them. */
    @Test
    fun onlyTheExecutableKindsCarryTheseSlots() {
        val prepared = model("Rule", "PriceCheck", BOTH)

        assertEquals(
            listOf("/Catalog/PriceCheck"),
            prepared.declarationSlotFindings().map { it.at } +
                prepared.elements.filter { it.kind == "Rule" }.map { it.path },
            "the only executable in the fixture is the rule, and the control reports nothing about it",
        )
    }

    /**
     * A finding names the declaration it concerns, and a model-level one deliberately names none.
     *
     * Without the first a workspace report lists codes a consumer cannot act on, which is the whole reason
     * `Finding.at` exists. The second matters as much in the other direction: attributing "this model
     * declares no locale" to whichever rule happened to be read first would send a reader to edit a rule
     * over a header member.
     */
    @Test
    fun everyFindingNamesItsDeclaration() {
        val findings = model("Computation", "vk_vk_probe", "[]").declarationSlotFindings()

        assertEquals(listOf("MVK_RULE_NAME_INVALID", "MVK_ERROR_MESSAGE_MISSING"), findings.map { it.code })
        assertTrue(findings.all { it.at == "/Catalog/vk_vk_probe" }, "$findings")

        val modelLevel = model("Computation", "PriceCheck", BOTH, locales = "").declarationSlotFindings()
        assertEquals(listOf(null, "/Catalog/PriceCheck"), modelLevel.map { it.at }, "$modelLevel")
    }

    private companion object {
        /** What this kind must carry beside its messages to be a legal A12 declaration ([KF291]). */
        fun legalSlotsFor(kind: String) = if (kind == "Rule") """"errorCode":"PROBE"""" else ""

        const val EN_AND_DE = """{"code":"en_US"},{"code":"de_DE"}"""
        const val EN = """{"locale":"en_US","text":"too high"}"""
        const val EN_OTHER = """{"locale":"en_US","text":"above the cap"}"""
        const val DE = """{"locale":"de_DE","text":"zu hoch"}"""
        const val FR = """{"locale":"fr_FR","text":"trop haut"}"""
        const val EN_BLANK = """{"locale":"en_US","text":""}"""
        const val DE_BLANK = """{"locale":"de_DE","text":"  "}"""
        const val BOTH = """[$EN,$DE]"""
    }
}
