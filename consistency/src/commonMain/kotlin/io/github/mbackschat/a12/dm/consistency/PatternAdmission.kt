package io.github.mbackschat.a12.dm.consistency

import io.github.mbackschat.a12.dm.predicates.kernelPatternBlacklist

/**
 * A declared string field's `pattern` against A12's admission gate.
 *
 * A12 puts an authored pattern through two stages ([KERNEL-FINDINGS KF165],
 * `rulekit.validate.laws.DeclaredPatternGateLawsTest`): it must compile as a Java regular expression, and
 * its SOURCE TEXT must avoid a finite blacklist. Only the second is reproduced here — the first is a
 * property of one regex engine and no other engine can answer it, so a portable checker that tried would be
 * guessing in the direction that refuses models A12 takes.
 *
 * **The gate reads TEXT, not the pattern it spells**, and that is the whole reason this family is exact
 * rather than approximate. `a\*+` is refused because the source carries `*` immediately before `+`, though
 * the expression means an escaped star with a quantifier; `a\+\+` and `\[a\[b` are admitted because neither
 * spells a listed adjacency. An implementation that consumed escapes while scanning would read the pattern
 * instead of the text and quietly admit the first — which the dmkits' own Java preflight did until the grid
 * above was measured.
 *
 * **The code is `MVK_PATTERN_INVALID`, not the condition locus's `MVK_INVALID_PATTERN`** — the same two
 * words swapped, measured rather than carried across, because the two loci share the check and nothing
 * about that suggests two codes.
 */
internal fun TrackedFactBase.declaredPatternFindings(): List<Finding> =
    fieldDeclarations().mapNotNull { field ->
        val pattern = field.declaredPattern() ?: return@mapNotNull null
        val listed = kernelPatternBlacklist(pattern) ?: return@mapNotNull null
        Finding(
            FindingCategory.A12_CONSTRAINT,
            FindingSeverity.ERROR,
            "MVK_PATTERN_INVALID",
            "the declared `pattern` `$pattern` carries $listed, which A12's admission gate refuses. The " +
                "gate reads the pattern's SOURCE TEXT rather than the expression it spells, so escaping " +
                "the character does not exempt it",
            field.path,
        )
    }

/**
 * The stage this family cannot reproduce, said on the report rather than only here.
 *
 * Whether a pattern compiles as a JAVA regular expression is a property of one engine; `Regex` on this
 * checker's own hosts accepts and refuses a different set, so agreeing with it would be a coincidence. The
 * gap is raised only when the model actually declares a pattern — otherwise it would describe every model.
 */
internal fun TrackedFactBase.declaredPatternGaps(): List<CoverageGap> =
    if (fieldDeclarations().none { !it.declaredPattern().isNullOrEmpty() }) {
        emptyList()
    } else {
        listOf(
            CoverageGap(
                at = null,
                reason = "a declared `pattern` is judged here only against A12's textual admission " +
                    "blacklist. Its other half — whether the pattern compiles as a JAVA regular " +
                    "expression — belongs to one engine and cannot be answered off the JVM, so a pattern " +
                    "this checker admits may still be refused for its syntax",
            ),
        )
    }

/** The `pattern` this field declares on its `StringType`, or null when it declares none. */
private fun TrackedDeclaration.declaredPattern(): String? = stringTypeBody()?.string("pattern")
