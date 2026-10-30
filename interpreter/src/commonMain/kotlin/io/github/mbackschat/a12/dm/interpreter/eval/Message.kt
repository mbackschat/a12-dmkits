package io.github.mbackschat.a12.dm.interpreter.eval

import io.github.mbackschat.a12.dm.interpreter.parse.A12Lexis
import io.github.mbackschat.a12.dm.interpreter.resolveRelative
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
    val semanticFor: String,
) {
    val tokenPattern: Regex by lazy { tokenPattern(this) }

    internal companion object {
        val EN = MessageTerminals("BaseYear", "value", "RuleGroup", "RootGroup", "For")
        val DE = MessageTerminals("Referenzjahr", "Wert", "RegelKontext", "LfdNr", "Fuer")

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
 *  - `$#<group-path>$`, `$#RuleGroup$`, `$#RootGroup$` → the firing row's 1-based repetition index at the named
 *    group's level, via [groupIndexOf]; the two shorthands are spelled per [terminals]. An
 *    absolute or declaring-group-relative path is resolved by the callback ([KF245]).
 *  - `$#<group> For <key>$` → the selected row number through the group callback; `For` follows [terminals].
 *  - `$index(<shortName>).value$` / `$index(<shortName>)->Category$` → the error ancestry's index token/category
 *    through the model-aware overload (SPEC11-01); this is separate from ordinary field label presentation.
 *  - `$<relpath>$` → the already-resolved display name via [labelOf].
 *  - A field name is absolute, below [base] after each leading `../`, or, through the model-aware [fieldPathOf], a
 *    unique short name; every spelling reads the same cell ([KF561]).
 *  - `$<path>.value For <key>$`, `$<path> For <key>$`, `$<path>->Category For <key>$` → the row the key selects,
 *    through [selectedCellOf]; see [selectedText] for a key that selects none.
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
    indexTextOf: (String, String?) -> String? = { _, _ -> null },
    selectedCellOf: (String) -> SelectedCell? = { null },
    fieldPathOf: (String) -> String = { name -> messageFieldPath(base, name) },
): String = if ('$' !in template) template else terminals.tokenPattern.replace(template) { token ->
    val name = unquoted(token.groupValues[NAME])
    val group = token.groupValues[GROUP]
    val index = unquoted(token.groupValues[INDEX])
    when {
        token.value == "\$\$" -> "\$"
        group.isNotEmpty() -> groupIndexOf(if (group.any(A12Lexis::isSpace)) group else unquoted(group)) ?: token.value
        index.isNotEmpty() -> indexTextOf(index, unquoted(token.groupValues[INDEX_CATEGORY]).takeIf { it.isNotEmpty() }) ?: token.value
        name.isEmpty() -> baseYearOf(offsetOf(token.groupValues[OFFSET])) ?: token.value
        token.groupValues[SELECTION].isNotEmpty() ->
            selectedCellOf(fieldPathOf(name) + token.groupValues[SELECTION])?.let { cell -> selectedText(token, cell, labelOf,
                enumValueOf, enumCategoryOf, defaultValueOf) } ?: token.value
        else -> {
            val path = fieldPathOf(name)
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

/**
 * The cell a key-selected field token reads: the field's path and, when the key selects a row, that row's stored
 * display value. [matched] is false when no row carries the key.
 */
internal class SelectedCell(val path: String, val stored: String?, val matched: Boolean)

/**
 * A key-selected token's text. A label needs no row; a category read through a key that selects none renders
 * empty; a value read through it fails the rule's evaluation, whose message then carries the fault text alone
 * (KERNEL-FINDINGS KF561, `adapter.laws.MessageParameterRowDiffTest`).
 */
private fun selectedText(
    token: MatchResult,
    cell: SelectedCell,
    labelOf: (String) -> String,
    enumValueOf: (String, String) -> String?,
    enumCategoryOf: (String, String, String) -> String?,
    defaultValueOf: (String) -> String,
): String {
    val category = token.groupValues[CATEGORY]
    return when {
        category.isNotEmpty() -> if (cell.matched) enumCategoryOf(cell.path, cell.stored ?: "", category) ?: "" else ""
        token.groupValues[VALUE_SUFFIX].isNotEmpty() ->
            if (cell.matched) valueText(cell.path, { cell.stored }, enumValueOf, defaultValueOf)
            else throw UnmatchedMessageKeyFailure(token.value)
        else -> labelOf(cell.path)
    }
}

/**
 * A field token's path below the rule's group: an absolute name as written, otherwise resolved against the group,
 * each `..` popping one name. The kernel reads every spelling of one field at the same cell, so a nested rule's `../X` and
 * `/Root/Group/X` render the enclosing row alike (KF561). A model-aware caller adds the short-name tier.
 */
internal fun messageFieldPath(base: String, name: String): String =
    if (name.startsWith('/')) name else resolveRelative(base, name) ?: name

/** The capture groups of the pattern below, in order: the signed offset, group, name, value suffix, category, key
 *  selection, index name and index category. */
private const val OFFSET = 1
private const val GROUP = 2
private const val NAME = 3
private const val VALUE_SUFFIX = 4
private const val CATEGORY = 5
private const val SELECTION = 6
private const val INDEX = 7
private const val INDEX_CATEGORY = 8

/**
 * The base-year alternative comes FIRST so its terminal is never read as a field name — which is exactly the
 * kernel's split: the own-language spelling is the terminal, the other language's falls through to a field
 * lookup, and only [terminals] decides which is which.
 *
 * Whitespace may stand at every token boundary inside a parameter, [GAP] here: A12 admits `$ Note.value$`,
 * `$Note .value$`, `$Note. value$` and `$Status -> Band$` and renders them as their tight spellings
 * (KERNEL-FINDINGS KF214, KF561; `adapter.laws.MessageParameterRowDiffTest`).
 */
private fun tokenPattern(terminals: MessageTerminals) = Regex(
    "\\\$\\\$" +
        "|\\\$" + GAP + terminals.baseYear + GAP + "([+-]" + GAP + "\\d+)?" + GAP + "\\\$" +
        "|\\\$#" + GAP + "([" + NAME_PART + "/.']+(?:" + RUN + terminals.semanticFor + RUN + "(?:\"(?:[^\"]|\"\")*\"|[" + NAME_PART + "/.']+))?)" +
        GAP + "\\\$" +
        "|\\\$" + GAP + "((?:\\.\\./)*[" + NAME_PART + "/']+)(?:" + GAP + "\\." + GAP + "(" + terminals.value + ")|" + GAP + "->" + GAP +
        "([" + NAME_PART + "]+))?" + "(" + RUN + terminals.semanticFor + RUN + "(?:\"(?:[^\"]|\"\")*\"|[" + NAME_PART + "/.']+))?" + GAP + "\\\$" +
        "|\\\$" + GAP + "index" + GAP + "\\(" + GAP + "([" + NAME_PART + "']+)" + GAP + "\\)" + GAP + "(?:\\." + GAP + terminals.value + "|->" + GAP +
        "([" + NAME_PART + "']+))" + GAP + "\\\$",
)

/** A character of a name inside a parameter: the error-text lexer's own letters, not a platform's `\w`. */
private const val NAME_PART = A12Lexis.NAME_PART_CLASS_BODY

/** Optional whitespace between two tokens of a parameter. */
private const val GAP = A12Lexis.SPACE_CLASS + "*"

/** Required whitespace between two tokens of a parameter. */
private const val RUN = A12Lexis.SPACE_CLASS + "+"

/**
 * The grammar's single-quote escape is PER SEGMENT, not per token — `$#/'Policy'/'Cover'$` reaches the same
 * group as its bare spelling ([KF214]), so the quotes are erased wherever they sit rather than only at the
 * ends. Erasing is safe here because only kernel-ADMITTED templates reach this renderer: a quoted spelling
 * that the kernel would have read as a NAME rather than a terminal is refused before it arrives.
 */
private fun unquoted(captured: String): String = captured.replace("'", "")

/** A bare terminal carries no offset; `+3` / `-1` parse as a signed integer, with no bound. */
private fun offsetOf(sign: String): Int = if (sign.isEmpty()) 0 else sign.filterNot(A12Lexis::isSpace).toInt()

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
