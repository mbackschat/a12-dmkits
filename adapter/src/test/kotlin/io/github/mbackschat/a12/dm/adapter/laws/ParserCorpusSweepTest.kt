package io.github.mbackschat.a12.dm.adapter.laws

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import io.github.mbackschat.a12.dm.adapter.perf.PerfCatalog
import io.github.mbackschat.a12.dm.interpreter.parse.parseCondition
import io.github.mbackschat.a12.dm.interpreter.parse.parseExpression
import io.github.mbackschat.a12.dm.rulekit.catalog.OperatorCatalog
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.extension

/**
 * The **parser coverage sweep** (WI-P of SEMANTICS-FORMALIZATION-PROPOSAL, from Phase-0 S1). Re-creates the
 * throwaway Phase-0 sweep as a PERMANENT guard: it parses the DSL the real kernel stores and asserts the
 * interpreter's hand-written parser accepts it. Two populations, two postures:
 *
 *  - **canonical / in-repo (always-on, must be 100%):** every catalog `validExample` + `computationExample`
 *    ([OperatorCatalog]) and every [PerfCatalog.fuzzConditions] string. These are our own canonical DSL — a
 *    parse failure here is a straight regression, so the guard is unconditional.
 *  - **real corpora (opt-in, classified):** every distinct `errorCondition`/`precondition` (→ [parseCondition])
 *    and `operation` (→ [parseExpression]) harvested from the `a12-kernel-mm` metamodels and the A12 Tools
 *    workspaces. Both skip silently when absent (external-sibling pattern — the EUPL/proprietary corpora are
 *    never committed). Every failure must fall into an explicitly-classified [WontSupport] bucket with a
 *    reason; an *unclassified* failure fails the sweep, forcing triage (a fix, a ledger row, or a reasoned
 *    won't-support).
 *
 * Phase-0 artifact avoided: paths are parsed against a DEEP synthetic base ([BASE]), so a `../..` walk never
 * false-fails on "walks above root" (the fixed-shallow-base bug that inflated the Phase-0 failure count).
 */
class ParserCorpusSweepTest {

    // ─────────────────────────────────────────────────────────────────────────────────────────────────────
    // Canonical / in-repo — the always-on 100% guard
    // ─────────────────────────────────────────────────────────────────────────────────────────────────────

    @Test
    fun catalogAndFuzzPopulationsParse() {
        val failures = mutableListOf<Failure>()
        val catalog = OperatorCatalog.load()
        var conds = 0
        var exprs = 0
        for (e in catalog.entries()) {
            e.validExample()?.dsl()?.let { conds++; tryParseCond(it, "catalog:${e.id()}", failures) }
            e.computationExample()?.operation()?.let { exprs++; tryParseExpr(it, "catalog:${e.id()}", failures) }
        }
        for (c in PerfCatalog.fuzzConditions()) { conds++; tryParseCond(c, "fuzz", failures) }
        println("[parser-sweep] canonical: ${conds} conditions + ${exprs} operations parsed, ${failures.size} failed")
        assertTrue(failures.isEmpty()) {
            "canonical in-repo DSL must parse 100% — a failure here is a parser regression:\n" +
                failures.joinToString("\n") { "  [${it.source}] ${it.error}\n      ${it.text.take(160)}" }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────────────────────────────────
    // Real corpora — opt-in, classified
    // ─────────────────────────────────────────────────────────────────────────────────────────────────────

    @Test
    fun kernelMmCorpusSweep() {
        val root = locateKernelMm()
        assumeTrue(root != null, "a12-kernel-mm not present — skipping parser corpus sweep")
        sweepCorpus("kernel-mm", root!!)
    }

    @Test
    fun workspacesCorpusSweep() {
        val dir = System.getProperty("a12.corpus1.dir")
        assumeTrue(dir != null && Files.isDirectory(Path.of(dir)),
            "a12.corpus1.dir not set / absent — skipping workspaces parser corpus sweep")
        sweepCorpus("workspaces", Path.of(dir))
    }

    // ─────────────────────────────────────────────────────────────────────────────────────────────────────

    private fun sweepCorpus(label: String, root: Path) {
        val (conditions, operations) = harvest(root)
        val condFailures = mutableListOf<Failure>()
        val opFailures = mutableListOf<Failure>()
        conditions.forEach { tryParseCond(it, label, condFailures) }
        operations.forEach { tryParseExpr(it, label, opFailures) }

        val condPass = conditions.size - condFailures.size
        val opPass = operations.size - opFailures.size
        println("\n[parser-sweep] $label: conditions ${condPass}/${conditions.size}, operations ${opPass}/${operations.size}")

        // Every real-corpus string must either PARSE or match a reasoned won't-support bucket. Both pinned
        // corpora (kernel-mm @ current, A12 Tools workspaces @ 2025.06-ext5) parse 100%, so [unsupportedReason]
        // has no buckets yet; a NEW failure (a kernel bump, a fresh model) fails here and forces triage — fix
        // the parser, or record a reason. Print the residue either way so triage has the offending strings.
        val unclassified = (condFailures + opFailures).filter { unsupportedReason(it) == null }
        report("$label unclassified", unclassified)
        assertTrue(unclassified.isEmpty()) {
            "$label: ${unclassified.size} parse failure(s) with no won't-support reason — fix the parser (WI-P), " +
                "or add a branch to unsupportedReason() / a ledger row:\n" +
                unclassified.take(20).joinToString("\n") { "  ${it.error}\n      ${it.text.take(200)}" }
        }
    }

    /** Print a failure histogram + a few examples — triage aid when the sweep goes red. */
    private fun report(what: String, failures: List<Failure>) {
        if (failures.isEmpty()) return
        println("  $what (${failures.size}):")
        failures.take(20).forEach {
            println("            · ${it.text.replace("\n", "\\n").replace("\r", "").take(140)}")
            println("              → ${it.error}")
        }
    }

    /**
     * A reasoned won't-support classification for a real-corpus parse failure — legal kernel syntax the
     * interpreter deliberately does not parse (e.g. an H-model `#Zeile` construct, were one to appear). Returns
     * the reason, or null if the failure is UNexpected (→ the sweep fails, forcing a fix or an added branch).
     * Empty today: after WI-P, both pinned corpora parse 100% — there is no legal-but-unsupported residue.
     */
    private fun unsupportedReason(@Suppress("UNUSED_PARAMETER") f: Failure): String? = null

    private data class Failure(val source: String, val text: String, val error: String)

    private fun tryParseCond(text: String, source: String, into: MutableList<Failure>) {
        try {
            parseCondition(text, BASE)
        } catch (t: Throwable) {
            into.add(Failure(source, text, "${t::class.simpleName}: ${t.message}"))
        }
    }

    private fun tryParseExpr(text: String, source: String, into: MutableList<Failure>) {
        try {
            parseExpression(text, BASE)
        } catch (t: Throwable) {
            into.add(Failure(source, text, "${t::class.simpleName}: ${t.message}"))
        }
    }

    /** Harvest DISTINCT condition + operation strings from every `*.json` under [root]. */
    private fun harvest(root: Path): Pair<Set<String>, Set<String>> {
        val conditions = LinkedHashSet<String>()
        val operations = LinkedHashSet<String>()
        Files.walk(root).use { paths ->
            paths.filter { it.extension == "json" }.forEach { file ->
                val tree = try {
                    MAPPER.readTree(Files.readString(file))
                } catch (_: Exception) {
                    return@forEach
                }
                collect(tree, conditions, operations)
            }
        }
        return conditions to operations
    }

    private fun collect(node: JsonNode, conditions: MutableSet<String>, operations: MutableSet<String>) {
        when {
            node.isObject -> node.fields().forEach { (key, value) ->
                if (value.isTextual && value.asText().isNotBlank()) {
                    when (key) {
                        "errorCondition", "precondition" -> conditions.add(value.asText())
                        "operation" -> operations.add(value.asText())
                    }
                }
                collect(value, conditions, operations)
            }
            node.isArray -> node.forEach { collect(it, conditions, operations) }
            else -> {}
        }
    }

    private fun locateKernelMm(): Path? {
        val candidates = buildList {
            System.getProperty("a12.kernel.mm.dir")?.let { add(it) }
            add("../a12-kernel-mm/documentModels")
            add("../../a12-kernel-mm/documentModels")
        }
        return candidates.map { Path.of(it) }.firstOrNull { Files.isDirectory(it) }
    }

    private companion object {
        /** A synthetic base deep enough that no realistic `../…` run walks above the root (Phase-0 artifact). */
        const val BASE = "/G0/G1/G2/G3/G4/G5/G6/G7/G8/G9"
        val MAPPER = ObjectMapper()
    }
}
