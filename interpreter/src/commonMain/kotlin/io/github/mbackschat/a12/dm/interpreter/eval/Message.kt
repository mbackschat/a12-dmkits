package io.github.mbackschat.a12.dm.interpreter.eval

/**
 * Message rendering — substitutes a rule's localized errorMessage template's placeholders, resolving
 * relative field paths against the rule's group [base]. commonMain, so it renders on JVM and JS alike.
 *
 * Two placeholder tiers, matching the kernel (KERNEL-FINDINGS §3/§13 supplements):
 *  - `$<relpath>.value$` → the original caller display value via [EvalContext.messageDisplayValue], with
 *    a missing or empty display selecting [defaultValueOf].
 *  - `$<relpath>$` → the already-resolved display name via [labelOf].
 *
 * [defaultValueOf] and [labelOf] take absolute field paths. Their defaults preserve the scale-zero/no-model
 * helper behavior; [DmInterpreter] supplies the model's exact admitted-profile default and label cascade.
 */
private val PLACEHOLDER = Regex("\\\$([\\w/]+)(\\.value)?\\\$")

fun renderMessage(
    template: String,
    base: String,
    ctx: EvalContext,
    defaultValueOf: (String) -> String = { path ->
        if (ctx.declaredKind(path) == io.github.mbackschat.a12.dm.interpreter.model.Kind.NUMBER) "0" else ""
    },
    labelOf: (String) -> String = { it.trimEnd('/').substringAfterLast('/') },
): String = PLACEHOLDER.replace(template) { m ->
    val path = "$base/${m.groupValues[1]}"
    if (m.groupValues[2].isNotEmpty()) {
        ctx.messageDisplayValue(path)?.takeIf { it.isNotEmpty() } ?: defaultValueOf(path)
    } else {
        labelOf(path)
    }
}
