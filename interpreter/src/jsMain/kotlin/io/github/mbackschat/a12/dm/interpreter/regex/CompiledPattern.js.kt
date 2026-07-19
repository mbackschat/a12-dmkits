package io.github.mbackschat.a12.dm.interpreter.regex

import kotlin.js.RegExp

/** JS actual — a `RegExp` on the [JavaPatternToJs]-translated source, wrapped `^(?:…)$` for whole-value match (the
 *  kernel-TS anchoring; the non-capturing group is load-bearing for top-level alternation). Compiled once; `.test`
 *  is stateless (no `g` flag). Browser-ready: the translated source uses only ES2018 features. */
actual class CompiledPattern(private val re: RegExp) {
    actual fun matches(text: String): Boolean = re.test(text)
}

actual fun compilePattern(pattern: String): CompiledPattern =
    CompiledPattern(RegExp("^(?:" + JavaPatternToJs.translate(pattern) + ")$"))
