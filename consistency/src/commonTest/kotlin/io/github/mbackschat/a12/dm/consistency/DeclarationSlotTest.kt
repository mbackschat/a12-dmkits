package io.github.mbackschat.a12.dm.consistency

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * C's first family: the declaration SLOTS of a stored rule or computation.
 *
 * Every row's verdict is the Kernel's, measured on both carriers by `RuleSlotLawsTest` and
 * `ComputationSlotLawsTest` (kernel 30.8.1, 2026-09-12) and asserted here against the checker. The two rows
 * A12 ADMITS carry as much weight as the refusals: the Java builders refuse both, and reproducing a builder's
 * policy as an A12 rejection is the failure this family exists to avoid.
 *
 * The table below states one verdict per shape rather than one test per shape, because the claim is about
 * which shapes are separated — a per-row test set can be individually green while collapsing two shapes onto
 * one answer.
 */
class DeclarationSlotTest {

    /** Both carriers, so no row can be measured on one kind and assumed for the other. */
    private val kinds = listOf("Rule", "Computation")

    /**
     * A one-declaration model: the entry declares [locales], and the single executable carries [name] and
     * the supplied `errorMessage` entries verbatim.
     */
    private fun model(kind: String, name: String, messages: String, locales: List<String> = EN_AND_DE) =
        prepare(
            assertIs<Admission.Admitted>(
                admit(
                    ModelSource(
                        """
                        {"header":{"id":"catalog","locales":[${locales.joinToString(",") {
                            """{"code":"$it"}"""
                        }}]},
                         "content":{"modelRoot":{"rootGroups":[
                          {"type":"Group","id":"g0","name":"Catalog","Group":{"elements":[
                            {"type":"Field","id":"f0","name":"Price","Field":{}},
                            {"type":"$kind","id":"x0","name":"$name","$kind":{"errorMessage":[$messages]}}
                          ]}}
                         ]}}}
                        """.trimIndent(),
                    ),
                ),
            ),
        )

    /** The codes reported for one shape, in report order, so a missing and an extra row both show. */
    private fun codesFor(kind: String, name: String, messages: String, locales: List<String> = EN_AND_DE) =
        model(kind, name, messages, locales).declarationSlotFindings().map { it.code }

    @Test
    fun eachSlotShapeDrawsTheVerdictTheKernelGivesIt() {
        for (kind in kinds) {
            val actual = mutableMapOf<String, List<String>>()
            actual["both locales covered — the control"] = codesFor(kind, "PriceCheck", BOTH)
            actual["reserved vk_vk prefix"] = codesFor(kind, "vk_vk_probe", BOTH)
            actual["reserved prefix in another letter case"] = codesFor(kind, "VK_VK_probe", BOTH)
            actual["no message entries at all"] = codesFor(kind, "PriceCheck", "")
            actual["every text blank"] = codesFor(kind, "PriceCheck", """$EN_BLANK,$DE_BLANK""")
            actual["one declared locale blank"] = codesFor(kind, "PriceCheck", """$EN,$DE_BLANK""")
            actual["one declared locale absent"] = codesFor(kind, "PriceCheck", EN)
            actual["undeclared locale beside both"] = codesFor(kind, "PriceCheck", """$BOTH,$FR""")
            actual["duplicate locale"] = codesFor(kind, "PriceCheck", """$EN,$EN,$DE""")

            val expected = mapOf(
                "both locales covered — the control" to listOf(),
                "reserved vk_vk prefix" to listOf("MVK_RULE_NAME_INVALID"),
                "reserved prefix in another letter case" to listOf("MVK_RULE_NAME_INVALID"),
                "no message entries at all" to listOf("MVK_ERROR_MESSAGE_MISSING"),
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
     * The message demand survives a model that declares no locale — the one row on the other side of the
     * axis every two-locale fixture above shares, so "a declaration needs a message" is separated from "a
     * declaration needs a message per declared locale" rather than inferred from rows where both hold.
     *
     * <p>The second row is the reason the undeclared-locale advisory is gated on a non-empty declared set:
     * without the gate every message of such a model would draw one, turning a single fact about the model
     * into an advisory per declaration.
     */
    @Test
    fun aMessagelessDeclarationIsRefusedEvenWithNoLocaleDeclared() {
        for (kind in kinds) {
            assertEquals(listOf("MVK_ERROR_MESSAGE_MISSING"), codesFor(kind, "PriceCheck", "", locales = listOf()),
                "no declared locale, no message, on a $kind")
            assertEquals(listOf<String>(), codesFor(kind, "PriceCheck", EN, locales = listOf()),
                "with no declared locale, one message is enough on a $kind")
        }
    }

    /** Only an A12 constraint may reject: the two shapes A12 admits must leave the model checkable. */
    @Test
    fun theAdmittedShapesAreAdvisoryRatherThanRejections() {
        for (kind in kinds) {
            val advisory = model(kind, "PriceCheck", """$EN,$EN,$DE,$FR""").declarationSlotFindings()

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
     * A finding names the declaration it concerns. Without it a workspace report lists codes a consumer
     * cannot act on, which is the whole reason `Finding.at` exists.
     */
    @Test
    fun everyFindingNamesItsDeclaration() {
        val findings = model("Computation", "vk_vk_probe", "").declarationSlotFindings()

        assertEquals(listOf("MVK_RULE_NAME_INVALID", "MVK_ERROR_MESSAGE_MISSING"), findings.map { it.code })
        assertTrue(findings.all { it.at == "/Catalog/vk_vk_probe" }, "$findings")
    }

    private companion object {
        val EN_AND_DE = listOf("en_US", "de_DE")
        const val EN = """{"locale":"en_US","text":"too high"}"""
        const val DE = """{"locale":"de_DE","text":"zu hoch"}"""
        const val FR = """{"locale":"fr_FR","text":"trop haut"}"""
        const val EN_BLANK = """{"locale":"en_US","text":""}"""
        const val DE_BLANK = """{"locale":"de_DE","text":"  "}"""
        const val BOTH = """$EN,$DE"""
    }
}
