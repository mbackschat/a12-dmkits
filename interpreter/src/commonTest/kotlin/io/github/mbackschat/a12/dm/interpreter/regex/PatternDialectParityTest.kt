package io.github.mbackschat.a12.dm.interpreter.regex

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * IF164 — an admitted Java `Pattern` carries the SAME (dynamic-Groovy-normative, IF136) semantics on the JS
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
        assertTrue(m("[\\H]", "a"), "\\H inside a class")
        assertFalse(m("[\\H]", " "), "\\H inside a class excludes horizontal whitespace")
        assertTrue(m("[\\V]", " "), "\\V inside a class")
        assertFalse(m("[\\V]", "\n"), "\\V inside a class excludes vertical whitespace")
    }

    @Test fun defaultJavaClassesAndCodePoints() {
        assertFalse(m("\\s", "\u00A0"), "Java's default \\s is ASCII; JS's native \\s is not")
        assertTrue(m("\\s", "\t"), "ASCII tab remains whitespace")
        assertTrue(m("[^@\\s]+@[^@\\s]+\\.[^@\\s]+", "a\u00A0@b.co"), "Java's EMail pattern admits NBSP where JS's native \\s would not")
        assertFalse(m(".", "\u0085"), "Java dot excludes NEL")
        assertTrue(m(".", "😀"), "Java dot consumes one supplementary code point")
    }

    @Test fun captureParticipationAndClassIntersection() {
        assertFalse(m("(a|(b))\\2c", "ac"), "a backreference to an unmatched group fails in Java")
        assertTrue(m("(a|(b))\\2c", "bbc"), "the same backreference succeeds when its capture participated")
        assertFalse(m("[a-z&&c-e]+", "a"), "Java class intersection excludes a")
        assertTrue(m("[a-z&&c-e]+", "cde"), "Java class intersection retains c-e")
    }

    @Test fun javaUnicodePrimitiveConstructs() {
        assertTrue(m("\\P{L}+", "123"), "the primitive defensively supports uppercase Unicode-property complement")
        assertFalse(m("\\P{L}+", "abc"), "letters are excluded by \\P{L}")
        assertTrue(m("\\x{1F600}", "😀"), "Java code-point hex escape")
        assertTrue(m("\\N{LATIN CAPITAL LETTER A}", "A"), "Java named-character escape")
        assertTrue(m("\\X", "e\u0301"), "Java extended grapheme cluster")
        assertTrue(m("\\b{g}A\\b{g}", "A"), "Java grapheme boundaries")
    }

    @Test fun classBoundariesAreParsedRatherThanGuessed() {
        assertTrue(m("[]\\h]", "]"), "a leading ] is a literal class member")
        assertTrue(m("[]\\h]", " "), "translation stays inside the leading-] class")
        assertTrue(m("[^]\\h]", "a"), "negated leading-] class accepts an ordinary letter")
        assertFalse(m("[^]\\h]", "]"), "negated leading-] class excludes ]")
        assertFalse(m("[^]\\h]", " "), "negated leading-] class excludes horizontal whitespace")
    }

    @Test fun completeInlineFlagForms() {
        assertTrue(m("(?i)abc", "ABC"), "global enable")
        assertTrue(m("(?i:abc)DEF", "AbCDEF"), "scoped enable")
        assertFalse(m("(?i:abc)DEF", "AbCdef"), "scope restores case sensitivity")
        assertTrue(m("(?i)abc(?-i)DEF", "AbCDEF"), "global disable")
        assertFalse(m("(?i)abc(?-i)DEF", "AbCdef"), "disabled suffix stays case-sensitive")
        assertTrue(m("(?s)a.b", "a\nb"), "dotall")
        assertTrue(m("(?x)a  # ignored comment\n b", "ab"), "comments/extended mode")
        assertTrue(m("(?im)^abc$\n^def$", "ABC\ndef"), "combined flags")
    }

    @Test fun caseInsensitiveClassesAndRanges() {
        assertTrue(m("(?i)[abc]+", "CBA"), "global case folding applies inside a class")
        assertTrue(m("(?i)[a-c]+", "AbC"), "global case folding expands both sides of a range")
        assertFalse(m("(?i)[a-c]+", "D"), "range expansion must not admit characters outside the Java range")
        assertTrue(m("(?i)[a-z&&c-e]+", "CDE"), "case folding applies to both sides of an intersection")
        assertTrue(m("(?i:[a-c]+)DEF", "AbCDEF"), "scoped case folding applies inside a range")
        assertFalse(m("(?i:[a-c]+)DEF", "AbCdef"), "the scoped flag remains local after a class")
    }

    // ── ordinary regex is untouched (the majority — must still work identically) ─────────────────────────────
    @Test fun ordinaryPatternsUnaffected() {
        assertTrue(m("\\d{3}-\\d{4}", "123-4567"), "digits")
        assertFalse(m("\\d{3}", "12"), "too short")
        assertTrue(m("[^@\\s]+@[^@\\s]+\\.[^@\\s]+", "a@b.co"), "the EMail predefined pattern")
        assertFalse(m("[^@\\s]+@[^@\\s]+\\.[^@\\s]+", "ab.co"), "no @")
        assertTrue(m("(?:ab|cd)", "cd"), "alternation, whole-value")
        assertFalse(m("ab|cd", "abcd"), "top-level alternation is whole-value anchored (the ^(?:…)$ wrap)")
    }

    // ── the translation output and shared support classifier ─────────────────────────────────────────────────
    @Test fun translationAndResidual() {
        assertEquals("[\\t\\x20\\xA0\\u1680\\u180E\\u2000-\\u200A\\u202F\\u205F\\u3000]", JavaPatternToJs.translate("\\h"),
            "\\h translates to Java's exact horizontal-ws class body")
        assertEquals("\\d+", JavaPatternToJs.translate("\\d+"), "ordinary escapes pass through")
        for (supported in listOf(
            "(?i)abc", "(?s).*", "(?m)^x", "(?i:abc)", "(?-i)a", "(?x) a b",
            "\\P{L}+", "\\x{1F600}", "\\N{LATIN CAPITAL LETTER A}", "\\X", "\\b{g}",
        )) {
            assertFalse(JavaPatternToJs.containsResidual(supported), "shared primitive construct must be supported: $supported")
        }
    }
}
