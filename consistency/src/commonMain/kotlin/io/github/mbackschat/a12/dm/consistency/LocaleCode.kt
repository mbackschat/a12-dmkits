package io.github.mbackschat.a12.dm.consistency

import io.github.mbackschat.a12.dm.predicates.a12LanguageTag
import io.github.mbackschat.a12.dm.predicates.a12LocaleKey
import io.github.mbackschat.a12.dm.predicates.a12LocaleKeyHasLanguage

/**
 * A message entry's locale code, reduced to the key A12 compares locales by ([a12LocaleKey]), or null when the
 * code covers no locale.
 *
 * **A12 does not compare the spelling.** It parses both a header's `locales[].code` and a message's `locale`
 * into a locale value and compares those, so `en-US` and `en_US` are ONE locale and a checker comparing text
 * reports a coverage gap for a model A12 admits — the direction the Kernel is never asked about. Measured
 * 2026-09-12 by `rulekit.validate.laws.MessageSlotRawShapeLawsTest`, on both the header and the message side.
 *
 * **And the codes it refuses are refused outright**, not read leniently: a spelling the parser refuses makes
 * the model UNREADABLE, so normalizing it here would analyse a model that does not load.
 */
internal fun localeKeyOrNull(code: String): String? {
    if (code.isEmpty()) {
        // A12 READS the empty code — it is the "any locale" value — and refuses the model a tier later,
        // when its own build finds no language declared. It covers nothing, so it is not a locale key; the
        // caller reports it with the spellings the reader refuses, both being a model A12 will not use.
        return null
    }
    return a12LocaleKey(code)?.takeUnless(::lostByTheModelCopy)
}

/**
 * A header's declared locale [code], reduced to its key, or null when A12 builds no model from it: a spelling
 * its parser refuses, or one naming no language, which the parser reads and the build then fails on
 * (KERNEL-FINDINGS KF649). A header demands nothing more of the parsed locale, so `de_CH_x` and `ée` declare
 * locales A12 builds.
 */
internal fun headerLocaleKeyOrNull(code: String): String? =
    a12LocaleKey(code)?.takeIf(::a12LocaleKeyHasLanguage)?.takeUnless(::lostByTheModelCopy)

/**
 * Whether kernel 31.1.1 builds no model naming the locale [key] names, though its parser reads it: the two JDK
 * locales whose language tag carries a Unicode extension, `ja_JP_JP` and `th_TH_TH`. Its expand step copies the
 * model through its serializer ([KERNEL-FINDINGS KF658]), and that copy fails on either wherever the model names it,
 * as a header code or as a message entry's locale ([KERNEL-FINDINGS KF662], `adapter.laws.CheckerLocaleSpellingAgreementTest`).
 */
private fun lostByTheModelCopy(key: String): Boolean = a12LanguageTag(key) in EXTENDED_TAGS

/** The language tags `java.util.Locale.toLanguageTag` writes for those two locales, the only ones carrying an extension. */
private val EXTENDED_TAGS = setOf("ja-JP-u-ca-japanese-x-lvariant-JP", "th-TH-u-nu-thai-x-lvariant-TH")
