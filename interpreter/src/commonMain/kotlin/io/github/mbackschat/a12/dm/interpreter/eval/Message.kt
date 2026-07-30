package io.github.mbackschat.a12.dm.interpreter.eval

/**
 * Message rendering — substitutes a rule's localized errorMessage template's placeholders, resolving
 * relative field paths against the rule's group [base]. commonMain, so it renders on JVM and JS alike.
 *
 * Three token forms, matching the kernel (KERNEL-FINDINGS §3/§13 supplements):
 *  - `$$` → one literal `$`.
 *  - `$<relpath>.value$` → the original caller display value via [messageValueOf], with a missing or empty
 *    display selecting [defaultValueOf].
 *  - `$<relpath>$` → the already-resolved display name via [labelOf].
 *
 * All tokens are replaced in one pass, so dollar sequences introduced by a field value or label remain literal.
 *
 * [defaultValueOf], [labelOf], and [messageValueOf] take absolute field paths. Their defaults preserve the
 * scale-zero/no-model helper behavior and read the display value off [ctx]; [DmInterpreter] supplies the
 * model's exact admitted-profile default and label cascade, and — for a FULL/single-rule pass that stages an
 * enum index default — a [messageValueOf] that reads the ORIGINAL caller document so a staged cell interpolates
 * as if empty, exactly as the kernel's `getDaten().getValueForDisplay` does (IF221).
 */
private val MESSAGE_TOKEN = Regex("\\\$\\\$|\\\$([\\w/]+)(\\.value)?\\\$")

fun renderMessage(
    template: String,
    base: String,
    ctx: EvalContext,
    defaultValueOf: (String) -> String = { path ->
        if (ctx.declaredKind(path) == io.github.mbackschat.a12.dm.interpreter.model.Kind.NUMBER) "0" else ""
    },
    labelOf: (String) -> String = { it.trimEnd('/').substringAfterLast('/') },
    messageValueOf: (String) -> String? = ctx::messageDisplayValue,
): String = MESSAGE_TOKEN.replace(template) { token ->
    if (token.value == "\$\$") {
        "\$"
    } else {
        val path = "$base/${token.groupValues[1]}"
        if (token.groupValues[2].isNotEmpty()) {
            messageValueOf(path)?.takeIf { it.isNotEmpty() } ?: defaultValueOf(path)
        } else {
            labelOf(path)
        }
    }
}
