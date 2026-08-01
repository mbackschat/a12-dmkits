package io.github.mbackschat.a12.dm.interpreter

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `format` and `parse` are two entry points on ONE domain, and this locks them to it in both directions.
 *
 * The domain rule used to live inside `parse`'s loop, so `format` — a peer on the same domain — shared none of
 * it and had only an arity check. Four inputs made it through and produced strings `parse` then refused. The
 * existing `A12PointerFormatTest` could not see any of them: its round trip runs `parse` → `format` over
 * already-canonical strings, and a one-directional round trip only ever checks the function that runs SECOND.
 *
 * Two laws, because the two failure directions are different and a single one is satisfiable by cheating:
 *
 *  - **[formatNeverEmitsAPointerParseRefuses]** — everything `format` emits must parse back to its input.
 *    Catches `format` being LOOSER than `parse`; it found **4,321** such strings on the pre-fix code.
 *  - **[formatCanExpressEveryPointerParseAccepts]** — everything `parse` reads, `format` must express.
 *    Catches `format` becoming STRICTER than `parse`, which is how an over-eager fix invents a restriction A12
 *    does not have. That is not hypothetical: a proposal revision listed A12's **root pointer**
 *    (`format("", [])` → `""`) as a defect, while the kernel's own `DocumentPointerImpl.parseFullName("")`
 *    returns an empty part list and the class displays that value as `(empty DocumentPointer)`. Rejecting it
 *    would have broken the project's first interop rule in the name of fixing a defect.
 *
 * **Recall limit, stated because it is not exhaustive over everything:** the vector sweep is exhaustive over
 * arities [ARITIES] and repetitions [REPETITION_BOUND], which is every vector *within those authored bounds* —
 * not over all `Int`. A sentinel outside the bound (say `-9`) is invisible to it, and so is the whole name
 * grammar, which is why [NAME_CORPUS] exists as a separate, independently authored axis.
 */
class A12PointerSymmetryTest {

    @Test
    fun formatNeverEmitsAPointerParseRefuses() {
        val broken = mutableListOf<String>()
        forEachCase { names, repetitions ->
            val formatted = runCatching { A12PointerFormat.format(names.joinToString("/"), repetitions) }
                .getOrElse { return@forEachCase }
            val reparsed = runCatching { A12PointerFormat.parse(formatted) }.getOrNull()
            if (reparsed == null) {
                broken += "format($names, $repetitions) = '$formatted', which parse refuses"
            } else if (reparsed.names != names || reparsed.repetitions != repetitions) {
                broken += "format($names, $repetitions) = '$formatted', which parses back as " +
                    "${reparsed.names}/${reparsed.repetitions}"
            }
        }
        assertTrue(
            broken.isEmpty(),
            "format emitted ${broken.size} pointer(s) its own parser rejects or re-reads differently — the " +
                "domain rule is enforced in parse only:\n  " + broken.take(12).joinToString("\n  "),
        )
    }

    /**
     * Everything [A12PointerFormat.parse] reads, [A12PointerFormat.format] must be able to express.
     *
     * Quantified over **strings**, deliberately. A first attempt quantified over `(names, repetitions)` pairs
     * and rebuilt the canonical string from them, which is unsound: for an ill-formed pair the rebuild names a
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
        for (candidate in stringCorpus()) {
            val parsed = runCatching { A12PointerFormat.parse(candidate) }.getOrNull() ?: continue
            val reformatted = runCatching {
                A12PointerFormat.format(parsed.names.joinToString("/"), parsed.repetitions)
            }.getOrElse {
                overStrict += "parse accepts '$candidate' but format refuses it back: ${it.message}"
                continue
            }
            val again = runCatching { A12PointerFormat.parse(reformatted) }.getOrNull()
            if (again != parsed) {
                overStrict += "'$candidate' parsed, reformatted to '$reformatted', which re-reads as $again"
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
     * produce, the name corpus fed in raw, both A12 spellings of a repeated path, the empty-bracket form, and
     * the root pointer. `parse` filters this to its own domain, so an entry that is not a pointer costs nothing.
     */
    private fun stringCorpus(): List<String> = buildList {
        add("")
        add("Order")
        add("Recipe[]")
        add("Order/Items[2]/Count")
        add("/Order[1]/Items[2]/Count")
        add("Plan/Demand[-5]/Headcount")
        add("Order/Items[0]")
        addAll(NAME_CORPUS)
        for (arity in ARITIES) {
            forEachVector(arity, emptyList()) { repetitions ->
                val names = List(arity) { LEGAL_NAMES[it] }
                runCatching { A12PointerFormat.format(names.joinToString("/"), repetitions) }
                    .onSuccess { add(it) }
            }
        }
    }

    /** A12's root pointer is legal on BOTH entry points. Pinned separately so it cannot regress silently. */
    @Test
    fun theRootPointerIsLegalOnBothEntryPoints() {
        assertEquals("", A12PointerFormat.format("", emptyList()))
        assertEquals(A12Pointer(emptyList(), emptyList()), A12PointerFormat.parse(""))
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
         * Names the vector sweep can never generate, each an independent way to be outside `PART`'s grammar.
         * `Order[2]` is the one that matters most: it is already-indexed, so `format` appending a second
         * bracket produced `Order[2][3]`, a string with no legal reading at all.
         *
         * **The empty name is deliberately absent, and its absence is a scope statement rather than an
         * exemption.** [formatNeverRefusesAPointerParseAccepts] rebuilds the canonical string from
         * `(names, repetitions)`, which assumes each name renders to something distinguishable — and `[""]`
         * with `[1]` renders to exactly what `[]` with `[]` renders to, the empty string. The law then reads
         * arity-1-with-an-empty-name as "format refused what parse accepts" when the two inputs are simply not
         * distinct pointers: an empty name at arity 1 *is* the arity-0 root pointer. That case is pinned by
         * [theRootPointerIsLegalOnBothEntryPoints] instead, where no reconstruction step is involved. Adding it
         * here would not find a defect; it would report the reconstruction's ambiguity as one.
         */
        private val NAME_CORPUS = listOf("Order[2]", "Order\$", "Order Item", "Order/", "Order]")
    }
}
