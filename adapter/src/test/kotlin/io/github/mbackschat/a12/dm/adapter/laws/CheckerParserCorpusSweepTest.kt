package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.consistency.syntax.ConditionLanguage
import io.github.mbackschat.a12.dm.consistency.syntax.SyntaxEntry
import io.github.mbackschat.a12.dm.consistency.syntax.lexicalFailure
import io.github.mbackschat.a12.dm.consistency.syntax.shapeFailure
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.nio.file.Path

/**
 * The `:consistency` checker's condition parser, swept over the DSL real models actually store.
 *
 * This is the last row of the grammatical increment's frozen acceptance matrix, and the one the catalog
 * cannot supply. The catalog's examples are DSL we wrote; these are DSL the Kernel stores in models we did
 * not write, which is the only population that can show a parser refusing a shape nobody here thought of —
 * and a refusal is an over-rejection, the one direction the checker must never take.
 *
 * **It lives in `:adapter` because the corpora do.** Both are external siblings under EUPL/proprietary
 * licence, discovered from this module's tests and never committed; `:consistency` is a standalone
 * multiplatform module and depending on a filesystem sweep would be the wrong shape for it. The edge is
 * test-scoped and the harvest is shared with the interpreter's own sweep, so the two parsers are held to the
 * SAME strings rather than to two populations that can drift.
 *
 * **Each text is parsed in ITS OWN model's condition language**, read from the model rather than assumed.
 * Assuming it is how this sweep measures nothing: under the wrong language every operator keyword reads as an
 * undeclared name, and a faithful parser refuses the whole corpus — which one run reported as 655
 * over-rejections before the assumption was checked.
 *
 * **Text the checker's LEXER refuses is excluded, not counted as a pass.** The parser's contract starts after
 * tokenization, so feeding it a text with an unterminated literal would measure the wrong phase; such a text
 * is a lexical finding in the report and is reported separately here if it appears at all.
 */
class CheckerParserCorpusSweepTest {

    @Test
    fun theCheckersParserAcceptsTheKernelMmCorpus() {
        val root = ParserCorpora.kernelMm()
        assumeTrue(root != null, "a12-kernel-mm not present — skipping checker parser sweep")
        sweep("kernel-mm", root!!)
    }

    @Test
    fun theCheckersParserAcceptsTheWorkspacesCorpus() {
        val dir = ParserCorpora.workspaces()
        assumeTrue(dir != null, "a12.corpus1.dir not set / absent — skipping checker parser sweep")
        sweep("workspaces", dir!!)
    }

    private fun sweep(label: String, root: Path) {
        val harvested = ParserCorpora.harvest(root)
        val unlexable = mutableListOf<String>()
        val refused = mutableListOf<String>()
        val unsupported = mutableSetOf<String>()
        var swept = 0
        for ((text, code) in harvested.conditions) {
            val language = ConditionLanguage.of(code)
            if (language == null) {
                unsupported += code
                continue
            }
            if (lexicalFailure(text) != null) {
                unlexable += text
                continue
            }
            swept++
            shapeFailure(text, language, SyntaxEntry.CONDITION)?.let {
                refused += "[$code] at ${it.at} found '${it.found}' in: $text"
            }
        }
        for ((text, code) in harvested.operations) {
            val language = ConditionLanguage.of(code)
            if (language == null) {
                unsupported += code
                continue
            }
            if (lexicalFailure(text) != null) {
                unlexable += text
                continue
            }
            swept++
            shapeFailure(text, language, SyntaxEntry.OPERATION)?.let {
                refused += "[$code] at ${it.at} found '${it.found}' in: $text"
            }
        }
        println(
            "[checker-parser-sweep] $label: ${harvested.conditions.size} conditions + " +
                "${harvested.operations.size} operations, $swept parsed, ${refused.size} refused, " +
                "${unlexable.size} unlexable",
        )
        // A corpus that yielded nothing would make the sweep pass while measuring nothing, and an empty
        // directory looks exactly like a corpus that parses perfectly. `swept` rather than the harvest size
        // is what carries that, because every exclusion below it also drains the population silently.
        assertTrue(swept > 0) { "$label put no text through the parser at all — the sweep would pass vacuously" }
        // A12 supports exactly two condition languages and refuses a model declaring a third, so a code the
        // checker does not know is a harvest defect — and dropping its text would shrink the sweep invisibly.
        assertTrue(unsupported.isEmpty()) {
            "$label declares condition language(s) the checker does not know: $unsupported"
        }
        assertTrue(refused.isEmpty()) {
            "$label: the checker's parser refused ${refused.size} text(s) the Kernel stores, which is an " +
                "OVER-REJECTION — widen the parser, never the expectation:\n" +
                refused.take(20).joinToString("\n") { "  $it" }
        }
        assertTrue(unlexable.isEmpty()) {
            "$label: ${unlexable.size} stored text(s) the checker's LEXER refuses, which it must not — this " +
                "is a tokenizer defect, not a parser one:\n" +
                unlexable.take(20).joinToString("\n") { "  ${it.take(200)}" }
        }
    }

}
