package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.consistency.ModelSource
import io.github.mbackschat.a12.dm.consistency.check
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import io.github.mbackschat.a12.dm.modelkit.FieldKind
import io.github.mbackschat.a12.dm.modelkit.ModelDocument
import io.github.mbackschat.a12.dm.rulekit.validate.DraftRule
import io.github.mbackschat.a12.dm.rulekit.validate.RuleValidator
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * On every path of a spelled GRID, the classes the checker names against the classes A12 names, with each
 * known disagreement listed in a committed ledger.
 *
 * **It exists because hand-written rows could not find what the grid found.** The resolver-class rows of
 * `rulekit.validate.laws.GroupOperandAndKeyResolverClassLawsTest` were all green when this grid was first run
 * (2026-09-24), and the same model still disagreed with A12 on 813 of 4 888 rows. The largest groups were a
 * `..` after a named segment, which A12 refuses as a token and the checker resolved, and an undeclared segment
 * that a later `..` climbs back out of. Nobody writes rows for spellings they have not thought of, and a grid
 * needs no one to.
 *
 * **The ledger is the frozen record of what is still wrong, and the test fails in both directions.** A row that
 * disagrees and is not listed is a new defect. A listed row that now agrees is a fix whose row was not deleted,
 * and it fails too, because a ledger nobody has to shrink stops meaning anything. On a mismatch the live
 * disagreements are written to `build/checker-path-grid/actual.tsv`, so the diff is reviewable rather than
 * retyped.
 *
 * **One pair is agreement by design, not a ledger row.** Where A12 names only `MVK_POINT_IN_A12_MODEL` for a
 * `.*` on a group that does not repeat, the checker adds `MVK_INVALID_WILDCARD`, because that defect survives
 * fixing the suffix (`HModelPathSuffix`'s own contract). A12 stops at its first gate and the checker
 * accumulates, so a strict superset is not by itself wrong; every OTHER superset stays in the ledger until it
 * is measured to be a true second defect.
 *
 * Own domain: an insurance policy with a fixed insured party and broker and repeatable claims, `Remark`
 * declared twice so a bare `Remark` is ambiguous, and short-name references on. The rule sits at the root and
 * one level down, because `..` means something different from each.
 */
class CheckerPathGridAgreementTest {

    private val policy = ModelDocument.read(DocumentModelDraft.named("policy").locale("en_US")
        .rootGroup("Policy") { p -> p
            .field("Holder", FieldKind.STRING)
            .group("Insured") { i -> i.field("Remark", FieldKind.STRING).field("Age", FieldKind.NUMBER) }
            .group("Broker") { b -> b.field("BrokerName", FieldKind.STRING) }
            .group("Claims") { c -> c.repeatable(20)
                .field("ClaimRef", FieldKind.STRING)
                .field("Remark", FieldKind.STRING)
                .field("Amount", FieldKind.NUMBER) } }
        .build().toJson())
        .setFieldRefByShortNameAllowed(true)
        .toJson()

    private val validator = RuleValidator()

    @Test
    fun theCheckerDisagreesWithA12OnlyWhereTheLedgerSays() {
        val live = LinkedHashSet<String>()
        var compared = 0
        for ((group, errorLeaf) in listOf("/Policy" to "Holder", "/Policy/Insured" to "Remark")) {
            for (operand in operands()) {
                val draft = DraftRule(group, "ProbeRule", "../$errorLeaf",
                        "FieldFilled($errorLeaf) And $operand", "PROBE", "ERROR")
                val a12 = validator.check(policy, draft).let { result ->
                    if (result.ok()) emptySet() else result.diagnostics().map { it.code().orElse(UNKEYED) }.toSet()
                }
                val ours = check(ModelSource(validator.inject(policy, draft))).findings
                        .filter { it.justifiesRejection }.map { it.code }.toSet()
                compared++
                verdict(a12, ours)?.let { live += listOf(group, operand, it, a12.sorted().joinToString(","),
                        ours.sorted().joinToString(",")).joinToString("\t") }
            }
        }
        // A floor, not a count: it answers whether the grid reached both engines at all.
        assertTrue(compared > 4000, "the grid compared only $compared rows")
        val ledger = ledger()
        if (live != ledger) {
            ACTUAL.apply { parentFile.mkdirs() }.writeText(live.joinToString("\n", postfix = "\n"))
        }
        assertEquals(emptyList<String>(), (live - ledger).toList(),
                "disagreements the ledger does not list (live set in ${ACTUAL.path})")
        assertEquals(emptyList<String>(), (ledger - live).toList(),
                "ledger rows that no longer disagree as listed: delete or update them")
    }

    /**
     * Every carrier over every path of up to two segments, relative and absolute, plus a third segment that
     * misses or gates once more. Two carriers because a field and a group operand meet different kind gates
     * around the same resolver.
     */
    private fun operands(): List<String> {
        val shorter = VOCABULARY + VOCABULARY.flatMap { a -> VOCABULARY.map { "$a/$it" } }
        val paths = shorter + shorter.drop(VOCABULARY.size).flatMap { a -> THIRD.map { "$a/$it" } } +
                shorter.flatMap { listOf("/Policy/$it", "/$it") }
        return paths.flatMap { listOf("FieldFilled($it)", "AtLeastOneGroupFilled($it)") }
    }

    /** Null where the two agree, including the one designed superset; otherwise the ledger's verdict word. */
    private fun verdict(a12: Set<String>, ours: Set<String>): String? = when {
        a12 == ours -> null
        a12 == setOf(POINT) && ours == setOf(POINT, "MVK_INVALID_WILDCARD") -> null
        a12.isEmpty() -> "OVER-REJECT"
        ours.isEmpty() -> "SILENT"
        ours.containsAll(a12) -> "EXTRA"
        else -> "WRONG-CLASS"
    }

    private fun ledger(): Set<String> =
        checkNotNull(javaClass.getResource(LEDGER)) { "missing $LEDGER" }.readText().lines()
            .filter { it.isNotBlank() && !it.startsWith("#") }
            .toCollection(LinkedHashSet())

    private companion object {
        const val LEDGER = "/checker-path-grid/divergences.tsv"
        val ACTUAL = File("build/checker-path-grid/actual.tsv")
        const val POINT = "MVK_POINT_IN_A12_MODEL"
        const val UNKEYED = "(no code)"
        val VOCABULARY = listOf(
            "Claims", "Claims*", "Claims.*", "Insured", "Insured*", "Insured.*",
            "Holder", "Holder*", "Amount", "Nope", "Nope*", "..", "..*",
        )
        val THIRD = listOf("Amount", "Nope", "Nope*", "Insured*")
    }
}
