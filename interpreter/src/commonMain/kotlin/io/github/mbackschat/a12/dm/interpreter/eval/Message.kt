package io.github.mbackschat.a12.dm.interpreter.eval

import io.github.mbackschat.a12.dm.interpreter.model.ConditionLanguage

/**
 * The rule-message parameter terminals for one condition language. The parameter grammar is bilingual and
 * follows the model's declared CONDITION language, not its display locale: on a `de_DE`-condition model
 * `$Referenzjahr$` and `$Beitrag.Wert$` are the terminals and the English spellings are looked up as FIELD
 * names instead (`INVALID_ENTITY`), and vice versa — measured in both directions ([KF198]).
 *
 * [ruleGroup] and [rootGroup] are two of the same five bilingual terminals, but they are reachable only from
 * the `$#…$` GROUP position, never from a rule-owned parameter name ([KF198], [KF214]).
 */
internal data class MessageTerminals(
    val baseYear: String,
    val value: String,
    val ruleGroup: String,
    val rootGroup: String,
) {
    internal companion object {
        val EN = MessageTerminals("BaseYear", "value", "RuleGroup", "RootGroup")
        val DE = MessageTerminals("Referenzjahr", "Wert", "RegelKontext", "LfdNr")

        fun of(language: ConditionLanguage): MessageTerminals =
            if (language == ConditionLanguage.DE) DE else EN
    }
}

/**
 * Message rendering — substitutes a rule's localized errorMessage template's placeholders, resolving
 * relative field paths against the rule's group [base]. commonMain, so it renders on JVM and JS alike.
 *
 * Token forms, matching the kernel (KERNEL-FINDINGS §3/§13 supplements, [KF198]):
 *  - `$$` → one literal `$`.
 *  - `$<relpath>.value$` → the original caller display value via [messageValueOf], with a missing or empty
 *    display selecting [defaultValueOf]. On an ENUMERATION the kernel renders the stored value's declared
 *    LABEL, so [enumValueOf] gets first refusal.
 *  - `$<relpath>->Category$` → the category token the stored value maps to, via [enumCategoryOf]; an empty
 *    or unmapped cell renders the empty string, which is what the kernel does.
 *  - `$BaseYear$`, `$BaseYear+3$`, `$BaseYear-1$` → the model's declared base year with the signed offset
 *    applied, via [baseYearOf]; unbounded, and spelled per [terminals].
 *  - `$#<abspath>$`, `$#RuleGroup$`, `$#RootGroup$` → the firing row's 1-based repetition index at the named
 *    group's level, via [groupIndexOf]; the two shorthands are spelled per [terminals]. This position takes an
 *    ABSOLUTE path that must contain the rule's group ([KF214]) — nothing relative reaches it.
 *  - `$<relpath>$` → the already-resolved display name via [labelOf].
 *
 * A name colliding with a terminal is written in the grammar's single-quote escape (`$'For'$`) and the quotes
 * are erased before any lookup, so an unnecessary quote is transparent. Only kernel-ADMITTED templates reach
 * this renderer, so the escape is accepted rather than re-validated here.
 *
 * All tokens are replaced in one pass, so dollar sequences introduced by a field value or label remain literal.
 *
 * [defaultValueOf], [labelOf], [messageValueOf], [enumValueOf] and [enumCategoryOf] take absolute field paths.
 * Their defaults preserve the scale-zero/no-model helper behavior and read the display value off [ctx];
 * [io.github.mbackschat.a12.dm.interpreter.DmInterpreter] supplies the model's exact admitted-profile default,
 * label cascade, enumeration texts, base year and — for a FULL/single-rule pass that stages an enum index
 * default — a [messageValueOf] that reads the ORIGINAL caller document so a staged cell interpolates as if
 * empty, exactly as the kernel's `getDaten().getValueForDisplay` does (IF221).
 */
fun renderMessage(
    template: String,
    base: String,
    ctx: EvalContext,
    defaultValueOf: (String) -> String = { path ->
        if (ctx.declaredKind(path) == io.github.mbackschat.a12.dm.interpreter.model.Kind.NUMBER) "0" else ""
    },
    labelOf: (String) -> String = { it.trimEnd('/').substringAfterLast('/') },
    messageValueOf: (String) -> String? = ctx::messageDisplayValue,
    enumValueOf: (String, String) -> String? = { _, _ -> null },
    enumCategoryOf: (String, String, String) -> String? = { _, _, _ -> null },
    baseYearOf: (Int) -> String? = { null },
    groupIndexOf: (String) -> String? = { null },
): String = renderMessage(
    template, base, MessageTerminals.EN, defaultValueOf, labelOf, messageValueOf,
    enumValueOf, enumCategoryOf, baseYearOf, groupIndexOf,
)

/** The public overload above, with the model's condition-language terminal spellings supplied explicitly. */
internal fun renderMessage(
    template: String,
    base: String,
    terminals: MessageTerminals,
    defaultValueOf: (String) -> String,
    labelOf: (String) -> String,
    messageValueOf: (String) -> String?,
    enumValueOf: (String, String) -> String?,
    enumCategoryOf: (String, String, String) -> String?,
    baseYearOf: (Int) -> String?,
    groupIndexOf: (String) -> String?,
): String = tokenPattern(terminals).replace(template) { token ->
    val name = unquoted(token.groupValues[NAME])
    val group = unquoted(token.groupValues[GROUP])
    when {
        token.value == "\$\$" -> "\$"
        group.isNotEmpty() -> groupIndexOf(group) ?: token.value
        name.isEmpty() -> baseYearOf(offsetOf(token.groupValues[OFFSET])) ?: token.value
        else -> {
            val path = "$base/$name"
            val category = token.groupValues[CATEGORY]
            when {
                category.isNotEmpty() -> enumCategoryOf(path, storedAt(path, messageValueOf), category) ?: ""
                token.groupValues[VALUE_SUFFIX].isNotEmpty() ->
                    valueText(path, messageValueOf, enumValueOf, defaultValueOf)
                else -> labelOf(path)
            }
        }
    }
}

/** The signed-offset, group, name, value-suffix and category capture groups of the pattern below, in order. */
private const val OFFSET = 1
private const val GROUP = 2
private const val NAME = 3
private const val VALUE_SUFFIX = 4
private const val CATEGORY = 5

/**
 * The base-year alternative comes FIRST so its terminal is never read as a field name — which is exactly the
 * kernel's split: the own-language spelling is the terminal, the other language's falls through to a field
 * lookup, and only [terminals] decides which is which.
 */
private fun tokenPattern(terminals: MessageTerminals) = Regex(
    "\\\$\\\$" +
        "|\\\$" + terminals.baseYear + "([+-]\\d+)?\\\$" +
        "|\\\$#([\\w/']+)\\\$" +
        "|\\\$([\\w/']+)(?:\\.(" + terminals.value + ")|->(\\w+))?\\\$",
)

/**
 * The grammar's single-quote escape is PER SEGMENT, not per token — `$#/'Policy'/'Cover'$` reaches the same
 * group as its bare spelling ([KF214]), so the quotes are erased wherever they sit rather than only at the
 * ends. Erasing is safe here because only kernel-ADMITTED templates reach this renderer: a quoted spelling
 * that the kernel would have read as a NAME rather than a terminal is refused before it arrives.
 */
private fun unquoted(captured: String): String = captured.replace("'", "")

/** A bare terminal carries no offset; `+3` / `-1` parse as a signed integer, with no bound. */
private fun offsetOf(sign: String): Int = if (sign.isEmpty()) 0 else sign.toInt()

/** The stored token behind a cell, for the category lookup; an absent cell reads as empty. */
private fun storedAt(path: String, messageValueOf: (String) -> String?): String = messageValueOf(path) ?: ""

/**
 * The `.value` text: an ENUMERATION renders the stored value's declared label (falling back to the token
 * itself), any other kind renders the display value, and an absent or empty display selects the field
 * format's default.
 */
private fun valueText(
    path: String,
    messageValueOf: (String) -> String?,
    enumValueOf: (String, String) -> String?,
    defaultValueOf: (String) -> String,
): String {
    val stored = messageValueOf(path)?.takeIf { it.isNotEmpty() } ?: return defaultValueOf(path)
    return enumValueOf(path, stored) ?: stored
}
