package io.github.mbackschat.a12.dm.interpreter.regex

/**
 * A compiled regex carrying **Java-`Pattern` semantics on both hosts** (IG90; the owner decision — normative
 * dynamic-Groovy semantics, IF136). [matches] is a WHOLE-VALUE (full-region) test — the kernel's
 * `matcher.matches()` (JVM) / `^(?:…)$` `.test()` (TS). Compile ONCE per model-static pattern and reuse
 * (the kernel caches globally; the interpreter caches per model/field/pass).
 *
 * - **JVM** actual: `java.util.regex.Pattern` on the ORIGINAL pattern — this IS the dynamic-Groovy/kernel-Java engine.
 * - **JS** actual: `RegExp` on the [JavaPatternToJs]-translated source, wrapped `^(?:…)$` — Java semantics for the
 *   covered constructs; the untranslated IG90 residual still behaves as native JS (see [JavaPatternToJs]).
 */
expect class CompiledPattern {
    /** Whether [text] matches this pattern in FULL (the whole value), Java `matcher.matches()` semantics. */
    fun matches(text: String): Boolean
}

/** Compile [pattern] (a kernel-legal Java regex) once. Reuse the returned handle; do not recompile per evaluation. */
expect fun compilePattern(pattern: String): CompiledPattern
