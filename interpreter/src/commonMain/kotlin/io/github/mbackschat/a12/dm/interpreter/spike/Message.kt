package io.github.mbackschat.a12.dm.interpreter.spike

/**
 * SPIKE message rendering — substitutes a rule's localized errorMessage template's `$<relpath>.value$`
 * placeholders with the field's display value, resolving relative paths against the rule's group [base].
 * commonMain, so it renders on JVM and JS alike.
 *
 * Scope: value interpolation (the kernel's `$field.value$`, rendered via getValueForDisplay). The
 * `$field$` display-name placeholder (the kernel's identifierToString — needs the field's label
 * metadata) and the formal-error message bundles are out of scope for the spike.
 */
private val VALUE_PLACEHOLDER = Regex("\\\$([\\w/]+)\\.value\\\$")

fun renderMessage(template: String, base: String, ctx: EvalContext): String =
    VALUE_PLACEHOLDER.replace(template) { m -> ctx.displayValue("$base/${m.groupValues[1]}") ?: "" }
