package io.github.mbackschat.a12.dm.adapter.laws

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.databind.node.ObjectNode
import io.github.mbackschat.a12.dm.consistency.ModelSource
import io.github.mbackschat.a12.dm.consistency.check
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import org.apache.commons.lang3.LocaleUtils
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import kotlin.random.Random

/**
 * The checker against A12: which locale spellings a message entry and a model header may carry (KERNEL-FINDINGS
 * KF649).
 *
 * Both reach Commons Lang's `LocaleUtils.toLocale`, which builds no model from a spelling it refuses. Over 2,410
 * spellings A12's verdict on a label entry was that jar's on every one (`BoundModel`, 2026-10-29), so the entry
 * test takes the jar as its oracle over spellings generated here, and checks A12 itself on one row per clause.
 * An entry spelling holding a non-ASCII character is the one deliberate departure: the checker reads it as
 * written rather than ship Java's character tables, so it is expected read, and the row A12 refuses is pinned.
 * A header then demands a well-formed language tag of the parsed locale, which the jar does not judge, so the
 * header test is held to `BoundModel` alone.
 *
 * Own domain (an insurance policy with a reference).
 */
class CheckerLocaleSpellingAgreementTest {

    private val mapper = ObjectMapper()

    private val base = DocumentModelDraft.named("policy-locale").locale("en_US")
        .rootGroup("Policy") { p -> p.stringField("Ref") { } }
        .build().toJson()

    @Test
    fun anEntryTakesExactlyTheSpellingsTheParserTakes() {
        val spellings = generatedSpellings()
        val expected = spellings.associateWith { if (parses(it) || it.any { c -> c > '\u007F' }) BUILT else UNBUILT }
        assertEquals(expected, spellings.associateWith { ourVerdict(labelled(it)) }, "the checker against the jar")
    }

    @Test
    fun anEntryClauseByClauseAgainstA12() {
        val rows = ENTRY_ROWS.keys.associateWith { a12Verdict(labelled(it)) to ourVerdict(labelled(it)) }
        assertEquals(ENTRY_ROWS.mapValues { (_, v) -> v to v }, rows, "as (A12, ours)")
    }

    @Test
    fun aNonAsciiEntrySpellingIsReadAsWrittenEvenWhereA12RefusesIt() {
        assertEquals(UNBUILT to BUILT, a12Verdict(labelled("ÉÉ")) to ourVerdict(labelled("ÉÉ")), "as (A12, ours)")
    }

    @Test
    fun aHeaderAlsoDemandsAWellFormedTag() {
        val rows = HEADER_ROWS.keys.associateWith { declared ->
            val json = declaring(*declared.split(" + ").toTypedArray())
            a12Verdict(json) to ourVerdict(json)
        }
        assertEquals(HEADER_ROWS.mapValues { (_, v) -> v to v }, rows, "as (A12, ours)")
    }

    private fun labelled(spelling: String): String {
        val root = mapper.readTree(base) as ObjectNode
        val field = (root.path("content").path("modelRoot").path("rootGroups").get(0).path("Group").path("elements") as ArrayNode)
            .single { it.path("name").asText() == "Ref" }.path("Field") as ObjectNode
        field.putArray("label").also {
            it.addObject().put("locale", "en_US").put("text", "Reference")
            it.addObject().put("locale", spelling).put("text", "Referenz")
        }
        return root.toString()
    }

    private fun declaring(vararg codes: String): String {
        val root = mapper.readTree(base) as ObjectNode
        (root.path("header").path("locales") as ArrayNode).also { locales ->
            locales.removeAll()
            codes.forEach { locales.addObject().put("code", it) }
        }
        return root.toString()
    }

    private fun parses(spelling: String): Boolean = runCatching { LocaleUtils.toLocale(spelling) }.isSuccess

    /** A12's verdict, an escape past its typed channels read as the model it never built. */
    private fun a12Verdict(json: String): String = try {
        if (kernelModelClasses(json).isEmpty()) BUILT else UNBUILT
    } catch (escaped: RuntimeException) {
        UNBUILT
    }

    private fun ourVerdict(json: String): String = when (check(ModelSource(json)).findings.filter { it.justifiesRejection }.map { it.code }.toSet()) {
        emptySet<String>() -> BUILT
        setOf("CK_MODEL_NOT_BUILDABLE") -> UNBUILT
        else -> "other"
    }

    /** Every spelling of up to three symbols, every uppercase pair, and longer random ones, over the parser's classes. */
    private fun generatedSpellings(): List<String> {
        val symbols = listOf("a", "e", "x", "Z", "U", "_", "-", "1", "é", "É", "٣")
        val short = (1..3).fold(listOf("")) { shorter, _ -> shorter + shorter.filter { it.length == shorter.maxOf(String::length) }.flatMap { s -> symbols.map { s + it } } }
        val pairs = ('A'..'Z').flatMap { a -> ('A'..'Z').map { b -> "$a$b" } }
        val random = Random(20261029)
        val long = List(800) { (1..random.nextInt(4, 10)).joinToString("") { symbols[random.nextInt(symbols.size)] } }
        return (short + pairs + long + ENTRY_ROWS.keys).distinct()
    }

    private companion object {
        const val BUILT = "built"
        const val UNBUILT = "no model"

        /** One row per clause of the grammar, measured through `BoundModel`, kernel 30.8.1, 2026-10-29. */
        val ENTRY_ROWS = mapOf(
            "de" to BUILT, "deu" to BUILT, "de_CH" to BUILT, "de-CH" to BUILT, "es_419" to BUILT,
            "de_CH_1996" to BUILT, "de_CH_1996_x" to BUILT, "de_CH_POSIX-x" to BUILT, "de-CH-1996" to BUILT, "de__1996" to BUILT,
            "_CH" to BUILT, "-CH" to BUILT, "_CH_x" to BUILT, "CH" to BUILT, "ée" to BUILT, "de_ÄÖ" to BUILT, "de_٣٣٣" to BUILT,
            "DE_419" to BUILT, "1_419" to BUILT,
            "EN" to UNBUILT, "QQ" to UNBUILT, "de_ch" to UNBUILT, "DE_CH" to UNBUILT, "d" to UNBUILT, "de_C" to UNBUILT,
            "de_CHE" to UNBUILT, "de_CH_" to UNBUILT, "de_CH-x" to UNBUILT, "de-CH_x" to UNBUILT, "de-CH-x_y" to UNBUILT, "_CH_" to UNBUILT,
            "DE_419_x" to UNBUILT, "zh_Hans_CN" to UNBUILT, " de" to UNBUILT, "de " to UNBUILT,
        )

        /**
         * Header declarations, ` + ` separating two codes, measured through `BoundModel`, kernel 30.8.1, 2026-10-29.
         * Each single code stands beside `fr_CH` so that the model keeps one language A12 builds.
         */
        val HEADER_ROWS = mapOf(
            "fr_CH + de" to BUILT, "fr_CH + deu" to BUILT, "fr_CH + de-CH" to BUILT, "fr_CH + es_419" to BUILT,
            "fr_CH + de-CH-1996" to BUILT, "fr_CH + de_CH_1996" to BUILT, "fr_CH + de_CH_POSIX" to BUILT, "fr_CH + de_CH_1abc" to BUILT,
            "fr_CH + de_CH_abcdefgh" to BUILT, "fr_CH + de_CH_POSIX_WIN32" to BUILT, "fr_CH + DE_419" to BUILT,
            "fr_CH + abcd_419" to BUILT, "fr_CH + abcdefgh_419" to BUILT, "fr_CH + und" to BUILT,
            "fr_CH + _CH" to UNBUILT, "fr_CH + -CH" to UNBUILT, "fr_CH + _CH_x" to UNBUILT, "fr_CH + CH" to UNBUILT,
            "fr_CH + US" to UNBUILT, "fr_CH + EN" to UNBUILT, "fr_CH + ée" to UNBUILT, "fr_CH + de_ÄÖ" to UNBUILT,
            "fr_CH + de_٣٣٣" to UNBUILT, "fr_CH + de_CH_1996_x" to UNBUILT, "fr_CH + zh_Hans_CN" to UNBUILT,
            "fr_CH + de__1996" to UNBUILT, "fr_CH + de_CH_x" to UNBUILT, "fr_CH + de_CH_abcd" to UNBUILT,
            "fr_CH + de_CH_abcdefghi" to UNBUILT, "fr_CH + abcdefghi_419" to UNBUILT, "fr_CH + 1_419" to UNBUILT,
            "fr_CH + d_419" to UNBUILT, "fr_CH + de_CH-x" to UNBUILT,
            "iw + he" to UNBUILT, "in + id" to UNBUILT, "de_CH + de-CH" to UNBUILT, "DE_419 + de_419" to UNBUILT,
            "iw + de" to BUILT,
        )
    }
}
