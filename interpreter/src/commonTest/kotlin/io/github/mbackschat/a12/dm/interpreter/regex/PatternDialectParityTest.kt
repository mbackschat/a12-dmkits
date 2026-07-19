package io.github.mbackschat.a12.dm.interpreter.regex

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * IG90 — a kernel-legal Java `Pattern` must carry the SAME (dynamic-Groovy-normative, IF136) semantics on the JS
 * host. These vectors run on both actuals (`:interpreter:check` → jvmTest + jsNodeTest): the JVM actual IS Java
 * `Pattern` (the oracle), so an expected value is Java's real result, and the JS actual (the [JavaPatternToJs]
 * translation) must reproduce it. Before the translation, JS `RegExp` read `\h`/`\v`/`\R`/`\A`/`\z` as identity
 * escapes / different classes — silently WRONG; these lock the covered constructs at parity. Non-printable test
 * characters are written as `\u` escapes (U+2028/U+2029/NEL would otherwise break source parsing).
 */
class PatternDialectParityTest {

    private fun m(pattern: String, text: String) = compilePattern(pattern).matches(text)

    // ── \h : Java horizontal whitespace (exact JVM-21 set, incl. U+180E) ─────────────────────────────────────
    @Test fun horizontalWhitespace() {
        assertTrue(m("\\h", " "), "space")
        assertTrue(m("\\h", "\t"), "tab")
        assertTrue(m("\\h", "\u00A0"), "NBSP")
        assertTrue(m("\\h", "\u180E"), "U+180E is in Java's \\h")
        assertTrue(m("\\h", "\u3000"), "ideographic space")
        assertFalse(m("\\h", "a"), "letter is not \\h")
        assertFalse(m("\\h", "\n"), "newline is not \\h")
        assertTrue(m("[\\h]+", " \t"), "\\h inside a class expands to the class body")
    }

    // ── \v : Java vertical whitespace (differs from JS \v = just \x0B) ────────────────────────────────────────
    @Test fun verticalWhitespace() {
        assertTrue(m("\\v", "\n"), "LF")
        assertTrue(m("\\v", "\u000B"), "VT")
        assertTrue(m("\\v", "\r"), "CR")
        assertTrue(m("\\v", "\u2028"), "line separator")
        assertFalse(m("\\v", " "), "space is not \\v")
        assertTrue(m("[\\v]", "\n"), "\\v inside a class")
    }

    // ── \R : any line break, CRLF as a UNIT ──────────────────────────────────────────────────────────────────
    @Test fun lineBreak() {
        assertTrue(m("a\\Rb", "a\nb"), "LF")
        assertTrue(m("a\\Rb", "a\r\nb"), "CRLF as one unit")
        assertTrue(m("a\\Rb", "a\rb"), "CR")
        assertTrue(m("a\\Rb", "a\u0085b"), "NEL")
        assertFalse(m("a\\Rb", "a b"), "space is not a line break")
    }

    // ── \H \V : negated classes ──────────────────────────────────────────────────────────────────────────────
    @Test fun negatedClasses() {
        assertTrue(m("\\H", "a"), "letter IS non-horizontal-ws")
        assertFalse(m("\\H", " "), "space is not \\H")
        assertTrue(m("\\V", " "), "space IS non-vertical-ws")
        assertFalse(m("\\V", "\n"), "newline is not \\V")
    }

    // (\A \z \Z and possessive/atomic/lookbehind/\p{}/intersection are KERNEL-FORBIDDEN — MVK_INVALID_PATTERN,
    //  A12K-30 — so they never reach a loadable model; the interpreter needs no translation. Proven kernel-rejected
    //  by adapter.laws.PatternDialectDiffTest.)

    // ── ordinary regex is untouched (the majority — must still work identically) ─────────────────────────────
    @Test fun ordinaryPatternsUnaffected() {
        assertTrue(m("\\d{3}-\\d{4}", "123-4567"), "digits")
        assertFalse(m("\\d{3}", "12"), "too short")
        assertTrue(m("[^@\\s]+@[^@\\s]+\\.[^@\\s]+", "a@b.co"), "the EMail predefined pattern")
        assertFalse(m("[^@\\s]+@[^@\\s]+\\.[^@\\s]+", "ab.co"), "no @")
        assertTrue(m("(?:ab|cd)", "cd"), "alternation, whole-value")
        assertFalse(m("ab|cd", "abcd"), "top-level alternation is whole-value anchored (the ^(?:…)$ wrap)")
    }

    // ── the translation output (documented) + the IG90 residual detector ─────────────────────────────────────
    @Test fun translationAndResidual() {
        assertEquals("[\\t\\x20\\xA0\\u1680\\u180E\\u2000-\\u200A\\u202F\\u205F\\u3000]", JavaPatternToJs.translate("\\h"),
            "\\h translates to Java's exact horizontal-ws class body")
        assertEquals("\\d+", JavaPatternToJs.translate("\\d+"), "ordinary escapes pass through")
        // the still-open IG90 residual is inline-flag groups (kernel-LEGAL but JS-throwing, not yet translated)
        for (r in listOf("(?i)abc", "(?s).*", "(?m)^x", "(?i:abc)", "(?x) a b"))
            assertTrue(JavaPatternToJs.containsResidual(r), "inline-flag residual flagged: $r")
        // covered / ordinary / non-capturing / lookahead / kernel-forbidden are NOT the interpreter's residual
        for (ok in listOf("\\d+", "a\\Rb", "[\\h\\v]", "(?:ab)", "(?=x)", "(?<name>x)", "a++"))
            assertFalse(JavaPatternToJs.containsResidual(ok), "not an inline-flag residual: $ok")
    }
}
