package io.github.mbackschat.a12.dm.adapter.laws

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.databind.node.ObjectNode
import io.github.mbackschat.a12.dm.consistency.ModelSource
import io.github.mbackschat.a12.dm.consistency.check
import io.github.mbackschat.a12.dm.core.ModelConsistencyException
import io.github.mbackschat.a12.dm.core.ModelIndex
import io.github.mbackschat.a12.dm.core.ModelLoader
import io.github.mbackschat.a12.dm.core.ReferenceResolvers
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import io.github.mbackschat.a12.dm.predicates.a12LanguageTag
import io.github.mbackschat.a12.dm.predicates.a12LocaleKey
import io.github.mbackschat.a12.dm.predicates.a12LocaleKeyHasLanguage
import org.apache.commons.lang3.LocaleUtils
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import kotlin.random.Random

/**
 * The checker against A12: which locale spellings a message entry and a model header may carry (KERNEL-FINDINGS
 * KF649).
 *
 * Both reach Commons Lang's `LocaleUtils.toLocale`, which builds no model from a spelling it refuses. Over 2,410
 * spellings A12's verdict on a label entry was that jar's on every one (the Kernel gate, 2026-10-29), so the entry
 * test takes the jar as its oracle over spellings generated here, and checks A12 itself on one row per clause.
 * An entry spelling holding a non-ASCII character is the one deliberate departure: the checker reads it as
 * written rather than ship Java's character tables, so it is expected read, and the row A12 refuses is pinned.
 * A header code must also name a language, and name a locale no other code names; it demands no well-formed
 * language tag, but two locales writing one JDK tag draw `MVK_SUPP_LANGUAGES_DUPLICATE`, so the checker's tag is
 * held to the jar over generated header spellings and its class to the gate on every pair of [TAG_POOL]. The header rows were first taken through `BoundModel.ofJson`, whose own index threw after the
 * Kernel had admitted the model, and so recorded eleven refusals A12 never makes (a12-kernel-lean's LF224); they
 * are held to the Kernel gate alone now, and our index is held to read every header the gate admits.
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
    fun aHeaderCodeNeedsALanguageAndALocaleOfItsOwn() {
        val rows = HEADER_ROWS.keys.associateWith { declared ->
            val json = declaring(*declared.split(" + ").toTypedArray())
            a12Verdict(json) to ourVerdict(json)
        }
        assertEquals(HEADER_ROWS.mapValues { (_, v) -> v to v }, rows, "as (A12, ours)")
    }

    @Test
    fun ourIndexReadsEveryHeaderTheGateAdmits() {
        val unread = HEADER_ROWS.filterValues { it == BUILT }.keys.filter { declared ->
            runCatching { ModelIndex.of(declaring(*declared.split(" + ").toTypedArray()), ReferenceResolvers.selfContained()) }.isFailure
        }
        assertEquals(emptyList<String>(), unread, "headers A12 builds that our index refuses")
    }

    @Test
    fun aHeaderLocaleWritesTheJdksLanguageTag() {
        val spellings = generatedHeaderSpellings().filter { a12LocaleKey(it)?.let(::a12LocaleKeyHasLanguage) == true }
        assertEquals(
            spellings.associateWith { LocaleUtils.toLocale(it).toLanguageTag() },
            spellings.associateWith { a12LanguageTag(a12LocaleKey(it)!!) },
            "the checker's tag against the jar",
        )
    }

    @Test
    fun twoLocalesWritingOneTagDrawTheDuplicateClass() {
        val rows = TAG_POOL.flatMapIndexed { i, a -> TAG_POOL.drop(i + 1).map { b -> "$a + $b" } }.associateWith { pair ->
            val json = declaring(*pair.split(" + ").toTypedArray())
            kernelModelClasses(json) to rejectingCodes(json).toSet()
        }
        assertEquals(rows.mapValues { (_, v) -> v.first to v.first }, rows, "as (A12, ours)")
    }

    @Test
    fun eachCollidingTagIsNamedOnceInTheOrderItIsFirstDeclared() {
        val declarations = listOf(
            listOf("de_CH", "en_US", "en_US_abcdefghi", "de_CH_abcdefghi"),
            listOf("en_US", "de_CH", "de_CH_abcdefghi", "en_US_abcdefghi"),
            listOf("en_US", "en_US_abcdefghi", "en_US_abcdefghij"),
        )
        val rows = declarations.associate { codes ->
            val json = declaring(*codes.toTypedArray())
            codes.joinToString(" + ") to (kernelTagsNamed(json) to ourTagsNamed(json))
        }
        assertEquals(rows.mapValues { (_, v) -> v.first to v.first }, rows, "as (A12, ours)")
        assertEquals(listOf("de-CH", "en-US"), rows.values.first().first, "the first-declared tag leads")
    }

    @Test
    fun aNonAsciiCodeIsLeftOutOfTheTagComparison() {
        // The one deliberate departure: a non-ASCII code keys as written (a12LocaleKey), so its tag is not claimed.
        // Two of them write `und` and `de`, which A12 builds: the codes the checker leaves untagged are not one tag.
        val rows = listOf("de + de_ÄÖ", "ée + de_ÄÖ").associateWith { pair ->
            val json = declaring(*pair.split(" + ").toTypedArray())
            kernelModelClasses(json) to ourVerdict(json)
        }
        assertEquals(mapOf("de + de_ÄÖ" to (setOf(DUPLICATE) to BUILT), "ée + de_ÄÖ" to (emptySet<String>() to BUILT)), rows, "as (A12, ours)")
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

    /** The tags A12's duplicate notifications name, in its order. */
    private fun kernelTagsNamed(json: String): List<String> = try {
        ModelLoader.loadSelfContained(json)
        emptyList()
    } catch (rejected: ModelConsistencyException) {
        rejected.notifications().mapNotNull { A12_DUPLICATE_SENTENCE.find(it.message())?.groupValues?.get(1) }
    }

    /** The tags the checker's duplicate findings name, in its order. */
    private fun ourTagsNamed(json: String): List<String> =
        check(ModelSource(json)).findings.filter { it.code == DUPLICATE }.mapNotNull { OUR_TAG.find(it.explain)?.groupValues?.get(1) }

    /** Every header spelling of a language, an optional country or area, and up to three variant segments. */
    private fun generatedHeaderSpellings(): List<String> {
        val languages = listOf("de", "DE", "no", "ja", "th", "iw", "deu", "1", "x", "abcdefghi")
        val countries = listOf("", "CH", "NO", "JP", "TH", "419")
        val segments = listOf("", "1996", "1abc", "POSIX", "posix", "x", "X", "abcd", "abcdefgh", "abcdefghi", "WIN32", "NY", "JP", "TH", "Latn", "a-b")
        val variants = listOf("") + segments.filter { it.isNotEmpty() } + segments.flatMap { a -> segments.map { b -> "${a}_$b" } } +
            List(400) { Random(it).let { r -> (1..3).joinToString("_") { segments[r.nextInt(segments.size)] } } }
        return languages.flatMap { l -> countries.flatMap { c -> variants.map { v -> if (v.isEmpty()) "${l}_$c".trimEnd('_') else "${l}_${c}_$v" } } }
            .flatMap { listOf(it, it.replace('_', '-')) }.distinct()
    }

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

        /** One row per clause of the grammar, measured through the Kernel gate, kernel 30.8.1, 2026-10-29. */
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
         * Header declarations, ` + ` separating two codes, measured through the Kernel gate, kernel 30.8.1,
         * 2026-10-29. Each single code stands beside `fr_CH` so that the model keeps one language A12 builds.
         */
        val HEADER_ROWS = mapOf(
            "fr_CH + de" to BUILT, "fr_CH + deu" to BUILT, "fr_CH + de-CH" to BUILT, "fr_CH + es_419" to BUILT,
            "fr_CH + de-CH-1996" to BUILT, "fr_CH + de_CH_1996" to BUILT, "fr_CH + de_CH_POSIX" to BUILT, "fr_CH + de_CH_1abc" to BUILT,
            "fr_CH + de_CH_abcdefgh" to BUILT, "fr_CH + de_CH_POSIX_WIN32" to BUILT, "fr_CH + DE_419" to BUILT,
            "fr_CH + abcd_419" to BUILT, "fr_CH + abcdefgh_419" to BUILT, "fr_CH + und" to BUILT,
            // No well-formed language tag is demanded: each of these is no tag the JDK's builder takes.
            "fr_CH + ée" to BUILT, "fr_CH + de_ÄÖ" to BUILT, "fr_CH + de_٣٣٣" to BUILT, "fr_CH + de_CH_1996_x" to BUILT,
            "fr_CH + de__1996" to BUILT, "fr_CH + de_CH_x" to BUILT, "fr_CH + de_CH_abcd" to BUILT,
            "fr_CH + de_CH_abcdefghi" to BUILT, "fr_CH + abcdefghi_419" to BUILT, "fr_CH + 1_419" to BUILT, "fr_CH + d_419" to BUILT,
            "fr_CH + _CH" to UNBUILT, "fr_CH + -CH" to UNBUILT, "fr_CH + _CH_x" to UNBUILT, "fr_CH + CH" to UNBUILT,
            "fr_CH + US" to UNBUILT, "fr_CH + EN" to UNBUILT, "fr_CH + zh_Hans_CN" to UNBUILT, "fr_CH + de_CH-x" to UNBUILT,
            "iw + he" to UNBUILT, "in + id" to UNBUILT, "de_CH + de-CH" to UNBUILT, "DE_419 + de_419" to UNBUILT,
            "iw + de" to BUILT,
        )

        const val DUPLICATE = "MVK_SUPP_LANGUAGES_DUPLICATE"
        val A12_DUPLICATE_SENTENCE = Regex("^The language '(.+)' is specified multiple times\\.")
        val OUR_TAG = Regex("language tag `([^`]+)`")

        /**
         * Header codes paired with each other through the Kernel gate, kernel 30.8.1, 2026-10-29. Each pairs of a
         * language tag's rivals: a variant the JDK writes, writes as private use or drops; one in another case; an
         * ill-formed language it writes as `und`; and the JDK's three legacy locales, of which only `no_NO_NY` meets
         * another (`nn_NO`). A pair of one locale is A12's classless refusal, and `de_CH` beside `de_CH_1996` builds,
         * which a comparison of language and country alone would refuse.
         */
        val TAG_POOL = listOf(
            "en_US", "en_US_abcdefghi", "en_US_abcdefghij", "de", "deu", "de_CH", "de-CH-1996", "de_CH_1996", "de_CH_POSIX",
            "de_CH_posix", "de_CH_POSIX-x", "de_CH_1abc", "de_CH_abcdefgh", "de_CH_abcdefghi", "de_CH_x", "de_CH_X", "de_CH_abcd",
            "de_CH_1996_x", "de_CH_1996_abcdefghi", "de_CH_x_abcdefghi", "de__1996", "de__POSIX", "DE_419", "de_419", "abcd_419",
            "abcdefghi_419", "1_419", "d_419", "x_419", "a-b_419", "und", "ja_JP", "ja_JP_JP", "ja_JP_jp", "th_TH", "th_TH_TH",
            "no_NO", "no_NO_NY", "nn_NO", "sr_RS_Latn", "de-CH-1996-x", "es_419", "en", "he",
        )
    }
}
