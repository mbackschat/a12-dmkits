package io.github.mbackschat.a12.dm.interpreter

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `format` and `parse` are two entry points on ONE domain, and this locks them to it in both directions — for
 * **both** A12 addressing types, so a domain rule can never again bind only one entry point.
 *
 * The rule used to live inside the exact `parse`'s loop, so `format` — a peer on the same domain — shared none of
 * it and had only an arity check. Four input classes made it through and produced strings `parse` then refused.
 * The existing `A12PointerFormatTest` could not see any of them: its round trip runs `parse` → `format` over
 * already-canonical strings, and a one-directional round trip only ever checks the function that runs SECOND.
 *
 * Two laws, because the two failure directions are different and a single one is satisfiable by cheating:
 *
 *  - **[formatNeverEmitsAPointerParseRefuses]** — everything `format` emits must parse back to its input.
 *    Catches `format` being LOOSER than `parse`; it found **4,321** such strings on the pre-fix exact code.
 *  - **[formatCanExpressEveryPointerParseAccepts]** — everything `parse` reads, `format` must express.
 *    Catches `format` becoming STRICTER than the domain, which is how an over-eager fix invents a restriction
 *    A12 does not have. That is not hypothetical: a proposal revision listed A12's **root pointer**
 *    (`format("", [])` → `""`) as a defect, while the kernel's own `DocumentPointerImpl.parseFullName("")`
 *    returns an empty part list and the class displays that value as `(empty DocumentPointer)`.
 *
 * Both laws run over both types, because the split into an exact and a partially-known type doubled the number
 * of `format`/`parse` pairs that can drift — and a law that covered only the type it was written for would have
 * left the new one unguarded from the day it landed. The type-specific facts (which sentinel each domain admits,
 * where) are `A12PointerDomainTest`'s; this file only asserts the two functions agree.
 *
 * **Recall limit, stated because it is not exhaustive over everything:** the vector sweep is exhaustive over
 * arities [ARITIES] and repetitions [REPETITION_BOUND], which is every vector *within those authored bounds* —
 * not over all `Int`. A sentinel outside the bound (say `-9`) is invisible to it, and so is the whole name
 * grammar, which is why [NAME_CORPUS] exists as a separate, independently authored axis.
 */
class A12PointerSymmetryTest {

    /**
     * One type's pair of entry points, reduced to what a symmetry law needs: read a string, write one back, and
     * compare pointers rather than spellings. Both types are checked through this one seam so neither law can be
     * written for one type and silently skip the other.
     */
    private class SymmetryPair(
        val label: String,
        val parse: (String) -> Any,
        val format: (String, List<Int>) -> String,
        val render: (Any) -> String,
    )

    /**
     * The pointer value is erased to `Any` so both types travel through one loop rather than one law per type.
     * The casts in `render` are safe by construction: within a pair, `parse` and `render` belong to the same
     * format object, and no other code puts a value into a pair.
     *
     * **This list is authored, not derived** — a third format type would have to be added here by hand. The
     * committed ABI dumps are what make such a type *visible* (a new public `…PointerFormat` object appears in
     * them), not this file.
     */
    private val pairs = listOf(
        SymmetryPair(
            "exact",
            A12PointerFormat::parse,
            A12PointerFormat::format,
            { A12PointerFormat.render(it as A12Pointer) },
        ),
        SymmetryPair(
            "partially-known",
            A12PartiallyKnownMultiPointerFormat::parse,
            A12PartiallyKnownMultiPointerFormat::format,
            { A12PartiallyKnownMultiPointerFormat.render(it as A12PartiallyKnownMultiPointer) },
        ),
    )

    @Test
    fun formatNeverEmitsAPointerParseRefuses() {
        val broken = mutableListOf<String>()
        for (pair in pairs) {
            forEachCase { names, repetitions ->
                val formatted = runCatching { pair.format(names.joinToString("/"), repetitions) }
                    .getOrElse { return@forEachCase }
                val reparsed = runCatching { pair.parse(formatted) }.getOrNull()
                if (reparsed == null) {
                    broken += "${pair.label}: format($names, $repetitions) = '$formatted', which parse refuses"
                } else if (pair.render(reparsed) != formatted) {
                    broken += "${pair.label}: format($names, $repetitions) = '$formatted', which parses back " +
                        "and re-renders as '${pair.render(reparsed)}'"
                }
            }
        }
        assertTrue(
            broken.isEmpty(),
            "format emitted ${broken.size} pointer(s) its own parser rejects or re-reads differently — the " +
                "domain rule is enforced in parse only:\n  " + broken.take(12).joinToString("\n  "),
        )
    }

    /**
     * Everything `parse` reads, `format` must be able to express.
     *
     * Quantified over **strings**, deliberately. A first attempt quantified over `(names, repetitions)` pairs and
     * rebuilt the canonical string from them, which is unsound: for an ill-formed pair the rebuild names a
     * *different* pointer, so `(["Order[2]"], [1])` rebuilt to `Order[2]` — a string `parse` rightly accepts as
     * `Order` repetition 2 — and the law reported a correct refusal as over-strictness. The reconstruction step
     * was the bug, not the code under test. Starting from strings removes it: `parse` decides membership, and
     * the only question left is whether `format` can round-trip what `parse` produced.
     *
     * Equality is on the *pointer*, not the spelling, because canonicalisation is the function's job:
     * `Recipe[]` is admitted and formats to `Recipe`, which is the same pointer in canonical form.
     */
    @Test
    fun formatCanExpressEveryPointerParseAccepts() {
        val overStrict = mutableListOf<String>()
        for (pair in pairs) {
            for (candidate in stringCorpus(pair)) {
                val parsed = runCatching { pair.parse(candidate) }.getOrNull() ?: continue
                val reformatted = runCatching { pair.render(parsed) }.getOrElse {
                    overStrict += "${pair.label}: parse accepts '$candidate' but it cannot be rendered back: " +
                        "${it.message}"
                    continue
                }
                val again = runCatching { pair.parse(reformatted) }.getOrNull()
                if (again != parsed) {
                    overStrict += "${pair.label}: '$candidate' parsed, re-rendered to '$reformatted', which " +
                        "re-reads as $again"
                }
            }
        }
        assertTrue(
            overStrict.isEmpty(),
            "format cannot express ${overStrict.size} pointer(s) parse accepts, so it is stricter than the " +
                "domain — the shape that would make A12's root pointer unwritable:\n  "
                + overStrict.take(12).joinToString("\n  "),
        )
    }

    /**
     * Strings for [formatCanExpressEveryPointerParseAccepts]: every canonical string the vector sweep can
     * produce for *this* type, the name corpus fed in raw, both A12 spellings of a repeated path, the
     * empty-bracket form, and both spellings of the root pointer. `parse` filters this to its own domain, so an
     * entry that is not a pointer of that type costs nothing.
     */
    private fun stringCorpus(pair: SymmetryPair): List<String> = buildList {
        add("")
        add("/")
        add("Order")
        add("Recipe[]")
        add("Order/Items[2]/Count")
        add("/Order[1]/Items[2]/Count")
        add("Plan/Demand[-5]/Headcount")
        add("Plan[0]/Demand[2]")
        add("Order/Items[0]")
        addAll(NAME_CORPUS)
        for (arity in ARITIES) {
            forEachVector(arity, emptyList()) { repetitions ->
                val names = List(arity) { LEGAL_NAMES[it] }
                runCatching { pair.format(names.joinToString("/"), repetitions) }.onSuccess { add(it) }
            }
        }
    }

    /** A12's root pointer is legal on every entry point of both types. Pinned so it cannot regress silently. */
    @Test
    fun theRootPointerIsLegalOnBothEntryPoints() {
        assertEquals("", A12PointerFormat.format("", emptyList()))
        assertEquals(A12Pointer.of(emptyList()), A12PointerFormat.parse(""))
        assertEquals("", A12PartiallyKnownMultiPointerFormat.format("", emptyList()))
        assertEquals(
            A12PartiallyKnownMultiPointerFormat.of("", emptyList()),
            A12PartiallyKnownMultiPointerFormat.parse(""),
        )
    }

    /** Every vector within the authored bounds, then every name-grammar case at arity 1 and 2. */
    private fun forEachCase(check: (List<String>, List<Int>) -> Unit) {
        for (arity in ARITIES) {
            forEachVector(arity, emptyList()) { repetitions ->
                check(List(arity) { LEGAL_NAMES[it] }, repetitions)
            }
        }
        for (name in NAME_CORPUS) {
            check(listOf(name), listOf(1))
            check(listOf(name), listOf(3))
            check(listOf(name, "Tail"), listOf(2, 1))
        }
    }

    private fun forEachVector(remaining: Int, prefix: List<Int>, body: (List<Int>) -> Unit) {
        if (remaining == 0) {
            body(prefix)
            return
        }
        for (repetition in REPETITION_BOUND) {
            forEachVector(remaining - 1, prefix + repetition, body)
        }
    }

    private companion object {
        /** 1..3 covers "last part" being first, middle and last, which is what the `[0]` rule turns on. */
        private val ARITIES = 1..3

        /** Spans both A12 sentinels (`0`, `-5`) with room either side, so neither sits on an edge. */
        private val REPETITION_BOUND = -8..8

        private val LEGAL_NAMES = listOf("Order", "Items", "Count")

        /**
         * Names the vector sweep can never generate, each an independent way to be outside the part grammar.
         * `Order[2]` is the one that matters most: it is already-indexed, so `format` appending a second bracket
         * produced `Order[2][3]`, a string with no legal reading at all.
         *
         * **The empty name is deliberately absent, and its absence is a scope statement rather than an
         * exemption.** [formatNeverEmitsAPointerParseRefuses] builds the pointer string from
         * `(names, repetitions)`, which assumes each name renders to something distinguishable — and `[""]` with
         * `[1]` renders to exactly what `[]` with `[]` renders to, the empty string. The law then reads
         * arity-1-with-an-empty-name as a spelling mismatch when the two inputs are simply not distinct
         * pointers: an empty name at arity 1 *is* the arity-0 root pointer. That case is pinned by
         * [theRootPointerIsLegalOnBothEntryPoints] instead, where no reconstruction step is involved.
         */
        private val NAME_CORPUS = listOf("Order[2]", "Order\$", "Order Item", "Order/", "Order]")
    }
}
