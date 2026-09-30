package io.github.mbackschat.a12.dm.adapter.laws

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

/**
 * The harvest reports each text's OWN declared condition language — the column the real corpora cannot vary.
 *
 * Every one of the 59 `a12-kernel-mm` models declares `en_US` (measured 2026-09-13), so a sweep over them is
 * green whether the harvest reads the declaration or simply returns English, and the constant hides the
 * difference in exactly the direction that already cost a run: assuming German there reported 655 of 657
 * stored conditions as parser over-rejections. Hence a fixture that declares BOTH languages and one that
 * declares none, which is the discriminating case the corpora cannot supply.
 *
 * The models are our own minimal insurance-domain shapes rather than corpus copies — only the A12 keys under
 * test are real.
 */
class ParserCorporaTest {

    @Test
    fun onlyDocumentModelsContributeDslText(@TempDir root: Path) {
        writeModel(root.resolve("policy.json"), "en_US", errorCondition = "FieldFilled(Premium)")
        Files.writeString(root.resolve("display.json"),
            """{"header":{"modelType":"object"},"content":{"operation":"sum","errorCondition":"display-only"}}""")
        Files.writeString(root.resolve("settings.json"),
            """{"operation":"total","precondition":"settings-only"}""")

        val harvested = ParserCorpora.harvest(root)
        assertEquals(setOf("FieldFilled(Premium)" to "en_US"), harvested.conditions.map { (text, code) -> text to code }.toSet())
        assertEquals(emptySet<Pair<String, String>>(), harvested.operations.map { (text, code) -> text to code }.toSet())
    }

    @Test
    fun eachTextCarriesTheConditionLanguageOfTheModelThatStoresIt(@TempDir root: Path) {
        writeModel(root.resolve("policy.json"), "de_DE", errorCondition = "FeldAngegeben(Beitrag)")
        writeModel(root.resolve("claim.json"), "en_US", errorCondition = "FieldFilled(Premium)")

        val harvested = ParserCorpora.harvest(root)

        assertEquals(
            setOf("FeldAngegeben(Beitrag)" to "de_DE", "FieldFilled(Premium)" to "en_US"),
            harvested.conditions.map { (text, code) -> text to code }.toSet(),
        )
    }

    /**
     * A model that declares nothing is English, because that is what an absent `conditionLanguage` means to
     * A12 — the default `DocumentModelConfig` carries `en_US`, mirrored by `ConditionLanguage.DEFAULT`.
     */
    @Test
    fun aModelDeclaringNoLanguageIsHarvestedAsEnglish(@TempDir root: Path) {
        Files.writeString(
            root.resolve("renewal.json"),
            """{"header":{"modelType":"document"},"content":{"modelConfig":{"decimalSeparator":"."},"rules":[{"errorCondition":"FieldFilled(Premium)"}]}}""",
        )

        assertEquals(setOf("FieldFilled(Premium)" to "en_US"), ParserCorpora.harvest(root).conditions.map { (text, code) -> text to code }.toSet())
    }

    /** Operations are harvested under their own model's language too, not only conditions. */
    @Test
    fun anOperationCarriesItsModelsLanguage(@TempDir root: Path) {
        writeModel(root.resolve("premium.json"), "de_DE", operation = "Summe(Positionen*/Betrag)")

        assertEquals(setOf("Summe(Positionen*/Betrag)" to "de_DE"), ParserCorpora.harvest(root).operations.map { (text, code) -> text to code }.toSet())
    }

    @Test
    fun identicalTextInDifferentLanguagesRetainsBothObservations(@TempDir root: Path) {
        for (code in listOf("en_US", "de_DE")) {
            writeModel(root.resolve("condition-$code.json"), code, errorCondition = "[Premium] > 0")
            writeModel(root.resolve("operation-$code.json"), code, operation = "[Premium] + 1")
        }
        val harvested = ParserCorpora.harvest(root)
        assertEquals(setOf("[Premium] > 0" to "en_US", "[Premium] > 0" to "de_DE"),
            harvested.conditions.map { (text, code) -> text to code }.toSet())
        assertEquals(setOf("[Premium] + 1" to "en_US", "[Premium] + 1" to "de_DE"),
            harvested.operations.map { (text, code) -> text to code }.toSet())
    }

    @Test
    fun computationPreconditionsBelongToTheConditionPopulation(@TempDir root: Path) {
        Files.writeString(root.resolve("common.json"),
            """{"header":{"modelType":"document"},"content":{"Computation":{"commonPrecondition":"FieldFilled(Common)","computationAlternatives":[{"precondition":"FieldFilled(Alternative)","operation":"[Source]"}]}}}""")
        val harvested = ParserCorpora.harvest(root)
        assertEquals(setOf("FieldFilled(Common)" to "en_US", "FieldFilled(Alternative)" to "en_US"),
            harvested.conditions.map { (text, code) -> text to code }.toSet())
        assertEquals(setOf("[Source]" to "en_US"),
            harvested.operations.map { (text, code) -> text to code }.toSet())
    }

    private fun writeModel(file: Path, code: String, errorCondition: String? = null, operation: String? = null) {
        val member = when {
            errorCondition != null -> """"errorCondition":"$errorCondition""""
            else -> """"operation":"$operation""""
        }
        Files.writeString(
            file,
            """{"header":{"modelType":"document"},"content":{"modelConfig":{"conditionLanguage":{"code":"$code"}},"modelRoot":{"members":[{$member}]}}}""",
        )
    }
}
