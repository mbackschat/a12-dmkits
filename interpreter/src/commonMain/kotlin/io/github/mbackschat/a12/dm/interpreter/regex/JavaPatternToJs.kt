package io.github.mbackschat.a12.dm.interpreter.regex

/**
 * Translates a kernel-legal **Java `Pattern`** regex into an equivalent **ES2018 JS `RegExp`** source, preserving
 * Java-Pattern (dynamic-Groovy-normative, IF136) SEMANTICS on the JS host — the owner decision for IG90
 * ("normative Java-pattern semantics on JS"; the kernel's own raw-`new RegExp` TS target is a recorded, never-adopted
 * codegen divergence — KERNEL-FINDINGS §7-supplement). The output is consumed only by the JS [CompiledPattern]
 * actual, wrapped `^(?:…)$`; the JVM actual uses the original pattern through `java.util.regex.Pattern` unchanged.
 *
 * **Browser-ready by construction (standing requirement — the JS target will run in modern browsers, not only Node).**
 * The output uses only broadly-available ES2018 features (character classes, ranges, `\xHH`/`\uHHHH` escapes) — no
 * ES2024 `v`-flag set operations and no ES2025 pattern modifiers — so it runs identically on Node and modern browsers.
 *
 * **The kernel already forbids almost every JS-incompatible construct** (`PatternUtils.isPatternValid`, A12K-30 —
 * KERNEL-FINDINGS §7-supplement): possessive `a++`, atomic `(?>…)`, lookbehind `(?<…`, the Java-only escapes
 * `\A \G \Z \z \a \e \p \Q \E`, and char-class intersection `[…[…]]` are **model-rejected** (`MVK_INVALID_PATTERN`)
 * for BOTH condition and declared-field patterns, so they never reach a loadable model — parity by construction, no
 * translation needed.
 *
 * **Covered (the kernel-LEGAL constructs that JS reads as identity escapes / different classes — the silent-divergence
 * class):** `\R \h \H \v \V` (Java's exact JVM-21 code-point sets). A tokenizer skips character-class interiors and
 * escaped characters so only genuine occurrences are rewritten; `\h`/`\v` inside a class expand to the class body,
 * and the ordinary majority of patterns pass through untouched.
 *
 * **NOT yet covered (the IG90 residual — still open):** inline flags `(?i)`/`(?s)`/`(?m)`/`(?x)` and scoped `(?i:…)`
 * (kernel-legal — `isPatternValid` does not forbid them — but they throw in JS `RegExp`, and a browser-safe global-flag
 * hoist / scoped-flag expansion is not yet built). These pass through unchanged; [containsResidual] reports them.
 */
internal object JavaPatternToJs {

    /** Java `\h` (horizontal whitespace), exact JVM-21 code points incl. U+180E — as a JS char-class body. */
    private const val H = "\\t\\x20\\xA0\\u1680\\u180E\\u2000-\\u200A\\u202F\\u205F\\u3000"

    /** Java `\v` (vertical whitespace), exact JVM-21 code points — as a JS char-class body. */
    private const val V = "\\n\\x0B\\f\\r\\x85\\u2028\\u2029"

    /** Translate [java] to an ES2018 JS RegExp source with Java-Pattern semantics for the covered constructs. */
    fun translate(java: String): String {
        val out = StringBuilder(java.length + 16)
        var i = 0
        var inClass = false
        while (i < java.length) {
            val c = java[i]
            if (c == '\\' && i + 1 < java.length) {
                val n = java[i + 1]
                val rep = rewriteEscape(n, inClass)
                if (rep != null) out.append(rep) else out.append('\\').append(n)
                i += 2
                continue
            }
            when {
                c == '[' && !inClass -> inClass = true
                c == ']' && inClass -> inClass = false
            }
            out.append(c)
            i++
        }
        return out.toString()
    }

    /** The JS replacement for `\<esc>` in the given class context, or `null` to pass `\<esc>` through unchanged.
     *  Only the kernel-LEGAL divergent escapes are rewritten (`\A`/`\z`/`\Z` etc. are kernel-forbidden, never seen).
     *  `\H`/`\V` inside a class are the residual (negation is not simply expandable there) — rare and passed through. */
    private fun rewriteEscape(esc: Char, inClass: Boolean): String? = when (esc) {
        'R' -> if (inClass) null else "(?:\\r\\n|[$V])"    // CRLF as a unit, else any single vertical-ws char
        'h' -> if (inClass) H else "[$H]"
        'H' -> if (inClass) null else "[^$H]"
        'v' -> if (inClass) V else "[$V]"
        'V' -> if (inClass) null else "[^$V]"
        else -> null
    }

    /**
     * Whether [java] contains the IG90 residual — a kernel-LEGAL construct that JS `RegExp` throws on and that
     * [translate] does not yet handle: an **inline-flag group** `(?i)`/`(?s)`/`(?m)`/`(?x)`/scoped `(?i:…)`
     * (`isPatternValid` does not forbid these, unlike possessive/atomic/lookbehind/`\A…`/intersection, which the
     * kernel rejects). Character-class interiors and escapes are skipped. Used by tests to assert scope.
     */
    fun containsResidual(java: String): Boolean {
        var i = 0
        var inClass = false
        while (i < java.length) {
            val c = java[i]
            if (c == '\\' && i + 1 < java.length) { i += 2; continue }   // skip an escaped char
            when {
                c == '[' && !inClass -> inClass = true
                c == ']' && inClass -> inClass = false
                !inClass && c == '(' && i + 2 < java.length && java[i + 1] == '?' && java[i + 2] in FLAG_LETTERS -> return true
            }
            i++
        }
        return false
    }

    /** The inline-flag letters that may lead a `(?<flags>…)` group — the JS-incompatible residual markers. */
    private val FLAG_LETTERS = setOf('i', 's', 'm', 'x', 'u', 'U', 'd')
}
