package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.interpreter.DmInterpreter
import io.github.mbackschat.a12.dm.interpreter.eval.Document
import io.github.mbackschat.a12.dm.interpreter.eval.newDocument
import io.github.mbackschat.a12.dm.interpreter.load.ModelLoader
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.util.function.Consumer

/**
 * Differential + tri-check (groovy-dynamic == java-static == interpreter) for **CRLF normalization at the
 * kernel's eval ingestion** (IG70/feedback F12, IF124). Two source loci, one normalization
 * (`RuntimeUtils.normalizeLinebreaks` — the `\r\n` pair only; a lone CR stays):
 * `FormatDefinitionString.validiereFormat` checks line-break permission on the raw text, then normalizes and
 * applies pattern / enum-domain / min-max length on the NORMALIZED text; and the formal-check stage feeds the
 * eval caches the normalized value (`CheckCommand` → `checkAndConvertFieldValue.normalizedValue()`), so RULE
 * and COMPUTATION reads see it too — probe-refuted the F12 "measure-only" hypothesis: `Length` reads 5 for a
 * permitted `"AB\r\nCD"` (and FIRES `> 4` — a real normalized read, not a suppression) and a computed copy
 * STORES `"AB\nCD"`. The interpreter's twin is the Document's eval-index ingestion (raw placements stay
 * untouched). (A `noValueValidation` field is OUT of this class: its rule read never surfaces the stored
 * text at all — the raw-type read semantics, IF125.)
 */
class CrlfLengthNormalizationDiffTest {

    private companion object {
        const val NOTE = "/Claim/IncidentNote"
        const val STATUS = "/Claim/Status"
        const val CRLF = "AB\r\nCD"   // 6 UTF-16 units raw, 5 normalized
        const val LF = "AB\nCD"       // 5
        const val CR = "AB\rCD"       // 5 — a lone CR is NOT normalized
    }

    private fun noteModel(lineBreaks: Boolean, min: Int? = null, max: Int? = null, pattern: String? = null,
        noValueValidation: Boolean = false): String =
        DocumentModelDraft.named("claim").locale("en_US")
            .rootGroup("Claim", Consumer { g ->
                g.stringField("IncidentNote") { n ->
                    if (lineBreaks) n.lineBreaks()
                    min?.let { n.minLength(it) }
                    max?.let { n.maxLength(it) }
                    pattern?.let { n.pattern(it, "must match the incident-note shape") }
                    if (noValueValidation) n.noValueValidation()
                }
                g.stringField("NoteCopy") { n -> n.lineBreaks() }
            })
            .build().toJson()

    private fun statusModel(): String = DocumentModelDraft.named("claim").locale("en_US")
        .rootGroup("Claim", Consumer { g ->
            g.enumField("Status") { e -> e.value("OPEN").value("CLOSED") }
        })
        .build().toJson()

    /** All fired codes for [value] at [field], per engine — (groovy-dynamic, java-static, interpreter). */
    private fun firedCodes(model: String, field: String, value: String): Triple<List<String>, List<String>, List<String>> {
        val kDoc = RuntimeLaws.doc().group("/Claim", 1).field(field, value, 1, 1)
        val k = RuntimeLaws.messages(model, kDoc).map { it.code() }.sorted()
        val engine = RuntimeLaws.kernelJavaEngine(model)
        val j = engine.messages(engine.instance(kDoc)).map { it.code() }.sorted()
        val def = ModelLoader.load(model)
        val iDoc = def.newDocument()
            .group("/Claim", 1).field(field, value, 1, 1)
        val i = DmInterpreter(def).validateFull(iDoc).map { it.code }.sorted()
        return Triple(k, j, i)
    }

    private fun agree(model: String, field: String, value: String, expected: List<String>, label: String) {
        val (k, j, i) = firedCodes(model, field, value)
        assertEquals(expected, k, "$label: kernel (groovy-dynamic)")
        assertEquals(k, j, "$label: groovy-dynamic vs java-static")
        assertEquals(k, i, "$label: kernel vs interpreter")
    }

    // The divergence pair: a permitted CRLF measures its NORMALIZED length (5), not the raw 6 units.
    @Test fun permittedCrlfMeasuresNormalizedAgainstMaxLength() =
        agree(noteModel(lineBreaks = true, max = 5), NOTE, CRLF, emptyList(), "CRLF vs maxLength 5 (norm 5 fits)")

    @Test fun permittedCrlfMeasuresNormalizedAgainstMinLength() =
        agree(noteModel(lineBreaks = true, min = 6), NOTE, CRLF, listOf("stringZuKurz"), "CRLF vs minLength 6 (norm 5 short)")

    // LF controls — no normalization in play, the plain length clauses.
    @Test fun lfWithinMaxNoFire() =
        agree(noteModel(lineBreaks = true, max = 5), NOTE, LF, emptyList(), "LF vs maxLength 5")

    @Test fun lfOverMaxFires() =
        agree(noteModel(lineBreaks = true, max = 4), NOTE, LF, listOf("stringZuLang"), "LF vs maxLength 4")

    // A LONE CR is not normalized (normalizeLinebreaks replaces only the "\r\n" pair) — it keeps its unit.
    @Test fun loneCrKeepsItsUnitOverMax() =
        agree(noteModel(lineBreaks = true, max = 4), NOTE, CR, listOf("stringZuLang"), "lone CR vs maxLength 4 (5 units)")

    @Test fun loneCrWithinMaxNoFire() =
        agree(noteModel(lineBreaks = true, max = 5), NOTE, CR, emptyList(), "lone CR vs maxLength 5")

    // Clause order: forbidden line breaks are checked on the RAW text BEFORE normalization and length —
    // a no-breaks field with an absurd maxLength reports the newline, never the length.
    @Test fun forbiddenBreaksPreemptLength() =
        agree(noteModel(lineBreaks = false, max = 1), NOTE, CRLF, listOf("stringMitNewline"), "no-breaks clause order")

    // The pattern clause also sees the NORMALIZED text (it follows the normalization in validiereFormat):
    // `AB\sCD` requires exactly ONE whitespace char — the raw "\r\n" pair (two) fails, the normalized "\n" matches.
    // (A pattern whose own text contains a literal line break is model-rejected — probe-observed 2026-07-15,
    // "Only values without line breaks are allowed" — so the discriminator is the whitespace class, not a literal.)
    @Test fun patternMatchesTheNormalizedText() =
        agree(noteModel(lineBreaks = true, pattern = "AB\\sCD"), NOTE, CRLF, emptyList(), "pattern vs normalized CRLF")

    // An ENUM field forbids line breaks (its format definition hard-codes lineBreakAllowed=false), and that
    // gate precedes the domain check — a break-carrying out-of-domain value is the NEWLINE, not the pattern.
    @Test fun enumNewlinePrecedesDomainCheck() =
        agree(statusModel(), STATUS, "OPEN\r\nX", listOf("stringMitNewline"), "enum newline-before-domain")

    // Locates the normalization: NOT measure-local — the formal-check stage stores the NORMALIZED value into
    // the eval cache (`CheckCommand` → `FormalChecker.checkAndConvertFieldValue.normalizedValue()`), so a RULE
    // read sees it too: Length over the permitted CRLF reads 5 (normalized), never the raw 6 units.
    @Test fun lengthOperatorReadsTheNormalizedText() {
        val json = noteModel(lineBreaks = true, max = 10)
        val m = BoundModel.ofJson(json)
        val cond = RuntimeLaws.render(Dsl.length(m.str(NOTE)).gt(5))
        val model = RuntimeLaws.withRule(json, "/Claim", "R_LEN", "../IncidentNote", "LEN", cond)
        agree(model, NOTE, CRLF, emptyList(), "Length(IncidentNote) > 5 over normalized CRLF")
    }

    // The POSITIVE-fire discriminator: `> 4` FIRES at the normalized 5 — so the no-fire above is a real
    // normalized READ, not a suppression of the CRLF value (which would silence this arm too).
    @Test fun lengthOperatorFiresAtTheNormalizedLength() {
        val json = noteModel(lineBreaks = true, max = 10)
        val m = BoundModel.ofJson(json)
        val cond = RuntimeLaws.render(Dsl.length(m.str(NOTE)).gt(4))
        val model = RuntimeLaws.withRule(json, "/Claim", "R_LEN", "../IncidentNote", "LEN", cond)
        agree(model, NOTE, CRLF, listOf("LEN"), "Length(IncidentNote) > 4 fires at the normalized 5")
    }

    // …and the same read boundary serves COMPUTATIONS: a bare copy of the CRLF source stores the NORMALIZED
    // text (the value itself witnesses the read form — 5 units, "AB\nCD").
    @Test fun computationCopyReadsTheNormalizedText() {
        val json = noteModel(lineBreaks = true, max = 10)
        val model = RuntimeLaws.withComputation(json, "/Claim", "CopyComp", "../NoteCopy", "[IncidentNote]")
        val kDoc = RuntimeLaws.doc().group("/Claim", 1).field(NOTE, CRLF, 1, 1)
        val k = RuntimeLaws.compute(model, kDoc).firstOrNull { it.path() == COPY }
            ?.let { c -> if (c.cleared()) "CLEARED" else "VALUE|${c.value().orElse("")}" } ?: "NONE"
        val engine = RuntimeLaws.kernelJavaEngine(model)
        val jRaw = engine.computeOutcomesSorted(engine.instance(kDoc)).firstOrNull { it.startsWith("$COPY|") }
            ?.substringAfter("$COPY|") ?: "NONE"
        val j = if (jRaw == "CLEARED" || jRaw == "ERR" || jRaw == "NONE") jRaw else "VALUE|$jRaw"
        val def = ModelLoader.load(model)
        val iDoc = def.newDocument()
            .group("/Claim", 1).field(NOTE, CRLF, 1, 1)
        val i = DmInterpreter(def).compute(iDoc).firstOrNull { it.path == COPY }
            ?.let { c -> if (c.value != null) "VALUE|${c.value}" else "CLEARED" } ?: "NONE"
        assertEquals("VALUE|AB\nCD", k, "compute copy: kernel (groovy-dynamic)")
        assertEquals(k, j, "compute copy: groovy-dynamic vs java-static")
        assertEquals(k, i, "compute copy: kernel vs interpreter")
    }

    // (A `noValueValidation` field deliberately has NO arm here: `Length(nvv) > c` is the only authorable
    // value-ish read of one — `MVK_INVALID_LENGTH_OF_RAW_TYPE` / `MVK_INVALID_RAW_TYPE` reject the rest —
    // and the kernel NEVER fires it (an unconditionally non-evaluable read, probe-pinned incl. `> -1` over
    // filled AND empty), so the nvv read is a raw-type semantics question, not a normalization one — IF125:
    // the shape is ELIMINATED at codegen, locked by NvvRawTypeDiffTest.)

    private val COPY = "/Claim/NoteCopy"
}
