package io.github.mbackschat.a12.dm.predicates

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The shared blacklist, on both hosts.
 *
 * The KERNEL grid lives with its oracle (`rulekit.validate.laws.DeclaredPatternGateLawsTest`, which holds
 * `KernelPatternPolicy` to it row for row); these rows exist because this leaf also runs on JS, where that
 * grid cannot. They mirror it exactly, so a host-specific divergence shows up as a disagreement between two
 * suites rather than as a silent one.
 */
class PatternPredicatesTest {

    @Test
    fun theBlacklistIsReadOnTheSourceTextOfThePattern() {
        val actual = mutableMapOf<String, Boolean>()
        val listed = { pattern: String -> kernelPatternBlacklist(pattern) != null }
        actual["an ordinary pattern — the control"] = listed("[A-Z]{2}[0-9]+")
        actual["a possessive quantifier"] = listed("a++")
        actual["a possessive bounded quantifier"] = listed("a{2}+")
        actual["a lookbehind"] = listed("(?<=a)b")
        actual["a NAMED capture group"] = listed("(?<name>a)b")
        actual["an atomic group"] = listed("(?>a)b")
        actual["an input-start escape"] = listed("\\Aabc")
        actual["a lowercase property escape"] = listed("\\p{L}")
        actual["a nested unescaped bracket"] = listed("[a[b]")
        // The rows that separate reading the TEXT from reading the expression it spells.
        actual["an ESCAPED star followed by a quantifier"] = listed("a\\*+")
        actual["escaped brackets, one after another"] = listed("\\[a\\[b")
        actual["two escaped plus signs"] = listed("a\\+\\+")
        // Uppercase \P is not listed; `\P{L}+` is refused by its `}+` ending instead.
        actual["an UPPERCASE property escape, unquantified"] = listed("\\P{L}")
        actual["an UPPERCASE property escape, quantified"] = listed("\\P{L}+")

        val expected = mutableMapOf<String, Boolean>()
        expected["an ordinary pattern — the control"] = false
        expected["a possessive quantifier"] = true
        expected["a possessive bounded quantifier"] = true
        expected["a lookbehind"] = true
        expected["a NAMED capture group"] = true
        expected["an atomic group"] = true
        expected["an input-start escape"] = true
        expected["a lowercase property escape"] = true
        expected["a nested unescaped bracket"] = true
        expected["an ESCAPED star followed by a quantifier"] = true
        expected["escaped brackets, one after another"] = false
        expected["two escaped plus signs"] = false
        expected["an UPPERCASE property escape, unquantified"] = false
        expected["an UPPERCASE property escape, quantified"] = true
        assertEquals(expected, actual, "A12's pattern blacklist, read on source text")
    }

    /** The description names the construct, because a caller renders it into an author-facing message. */
    @Test
    fun theAnswerNamesTheListedConstruct() {
        assertEquals(true, kernelPatternBlacklist("a\\*+")!!.contains("possessive quantifier"))
        assertEquals(true, kernelPatternBlacklist("\\Aabc")!!.contains("escape"))
        assertEquals(true, kernelPatternBlacklist("(?<=a)b")!!.contains("lookbehind"))
        assertEquals(true, kernelPatternBlacklist("[a[b]")!!.contains("unescaped"))
    }
}
