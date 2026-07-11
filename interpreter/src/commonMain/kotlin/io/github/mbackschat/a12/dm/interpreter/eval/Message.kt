package io.github.mbackschat.a12.dm.interpreter.eval

import io.github.mbackschat.a12.dm.interpreter.model.Kind

/**
 * Message rendering — substitutes a rule's localized errorMessage template's placeholders, resolving
 * relative field paths against the rule's group [base]. commonMain, so it renders on JVM and JS alike.
 *
 * Two placeholder tiers, matching the kernel (KERNEL-FINDINGS §3 supplement):
 *  - `$<relpath>.value$` → the field's display value (`getValueForDisplay`), via [EvalContext.displayValue].
 *  - `$<relpath>$` → the field's display NAME (`identifierToString`), via [labelOf] — the field's label for
 *    the locale, falling back to the field name. Resolved AFTER the value pass, so a `.value` token (whose
 *    `.` is not a name character) is never partially consumed by the name pass.
 *
 * An **unspecified** value interpolates the kind's empty display — **`0` for a NUMBER**, empty string for
 * every other kind (KERNEL-SEMANTICS §13; the kernel's `getValueForDisplay` of a not-given number is `0`).
 *
 * [labelOf] takes an ABSOLUTE field path and returns its display name; the default (last path segment) is
 * the no-model resolver used by direct unit callers. [DmInterpreter] passes `model.labelOf(path, locale)`.
 */
private val VALUE_PLACEHOLDER = Regex("\\\$([\\w/]+)\\.value\\\$")
private val NAME_PLACEHOLDER = Regex("\\\$([\\w/]+)\\\$")

fun renderMessage(
    template: String,
    base: String,
    ctx: EvalContext,
    labelOf: (String) -> String = { it.trimEnd('/').substringAfterLast('/') },
): String {
    val valued = VALUE_PLACEHOLDER.replace(template) { m ->
        val path = "$base/${m.groupValues[1]}"
        ctx.displayValue(path) ?: if (ctx.declaredKind(path) == Kind.NUMBER) "0" else ""
    }
    return NAME_PLACEHOLDER.replace(valued) { m -> labelOf("$base/${m.groupValues[1]}") }
}
