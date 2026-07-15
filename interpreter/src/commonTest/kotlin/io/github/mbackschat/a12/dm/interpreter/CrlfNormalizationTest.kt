package io.github.mbackschat.a12.dm.interpreter

import io.github.mbackschat.a12.dm.interpreter.eval.newDocument
import io.github.mbackschat.a12.dm.interpreter.model.ComputationAlternative
import io.github.mbackschat.a12.dm.interpreter.model.ComputationDef
import io.github.mbackschat.a12.dm.interpreter.model.ComputeOutcome
import io.github.mbackschat.a12.dm.interpreter.model.FieldDef
import io.github.mbackschat.a12.dm.interpreter.model.Kind
import io.github.mbackschat.a12.dm.interpreter.model.ModelDef
import io.github.mbackschat.a12.dm.interpreter.model.RuleDef
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Multiplatform (JVM + JS) lock for the CRLF→LF eval-ingestion normalization (IG70/feedback F12, IF124): the
 * kernel normalizes `\r\n` to `\n` ONCE when the formal-check stage feeds its eval caches
 * (`RuntimeUtils.normalizeLinebreaks` — the pair only, a lone CR stays), so the format measure, rule reads,
 * and computation reads all see the normalized text. The interpreter's twin is the [Document] eval-index
 * ingestion; the consumer's raw placements stay untouched. The kernel differential (tri-check, both
 * strategies) is `adapter.laws.CrlfLengthNormalizationDiffTest`; this pins the behavior on both platforms.
 */
class CrlfNormalizationTest {

    private companion object {
        const val NOTE = "/Claim/IncidentNote"
        const val COPY = "/Claim/NoteCopy"
        const val STATUS = "/Claim/Status"
        const val CRLF = "AB\r\nCD"   // 6 UTF-16 units raw, 5 normalized
    }

    private fun model(
        lineBreaks: Boolean = true, min: Int? = null, max: Int? = null, pattern: String? = null,
        rules: List<RuleDef> = emptyList(), comps: List<ComputationDef> = emptyList(),
    ) = ModelDef(
        fields = listOf(
            FieldDef(NOTE, Kind.STRING, lineBreaksPermitted = lineBreaks, minLength = min, maxLength = max,
                pattern = pattern),
            FieldDef(COPY, Kind.STRING, lineBreaksPermitted = true),
            FieldDef(STATUS, Kind.STRING, enumValues = listOf("OPEN", "CLOSED")),
        ),
        rules = rules,
        computations = comps,
    )

    private fun codes(m: ModelDef, field: String = NOTE, value: String = CRLF): List<String> {
        val doc = m.newDocument().group("/Claim", 1).field(field, value, 1, 1)
        return DmInterpreter(m).validateFull(doc).map { it.code }.sorted()
    }

    // The format measure sees the normalized length (5): maxLength 5 fits, minLength 6 is too short.
    @Test fun crlfMeasuresNormalizedAgainstMaxLength() = assertEquals(emptyList(), codes(model(max = 5)))

    @Test fun crlfMeasuresNormalizedAgainstMinLength() =
        assertEquals(listOf("stringZuKurz"), codes(model(min = 6)))

    // A lone CR is NOT normalized — "AB\rCD" keeps its 5 units (fires over maxLength 4).
    @Test fun loneCrKeepsItsUnit() = assertEquals(listOf("stringZuLang"), codes(model(max = 4), value = "AB\rCD"))

    // Clause order: a forbidden line break pre-empts the length clauses.
    @Test fun forbiddenBreaksPreemptLength() =
        assertEquals(listOf("stringMitNewline"), codes(model(lineBreaks = false, max = 1)))

    // The pattern clause sees the normalized text: `AB\sCD` (exactly one whitespace char) matches "AB\nCD".
    @Test fun patternMatchesTheNormalizedText() = assertEquals(emptyList(), codes(model(pattern = "AB\\sCD")))

    // An ENUM field's newline gate precedes the domain check (the kernel's enum format definition hard-codes
    // lineBreakAllowed=false): a break-carrying out-of-domain value is the NEWLINE, not stringFalschesMuster.
    @Test fun enumNewlinePrecedesDomainCheck() =
        assertEquals(listOf("stringMitNewline"), codes(model(), field = STATUS, value = "OPEN\r\nX"))

    // A RULE read sees the normalized text: Length reads 5 — `> 5` never fires, `> 4` does (proving the read
    // is the normalized 5, not raw 6 and not a suppression).
    @Test fun ruleReadIsNormalized() {
        val gt5 = model(max = 10, rules = listOf(RuleDef("/Claim/R", "/Claim", "Length([IncidentNote]) > 5", "LEN", "../IncidentNote")))
        assertEquals(emptyList(), codes(gt5))
        val gt4 = model(max = 10, rules = listOf(RuleDef("/Claim/R", "/Claim", "Length([IncidentNote]) > 4", "LEN", "../IncidentNote")))
        assertEquals(listOf("LEN"), codes(gt4))
    }

    // A COMPUTATION read sees the normalized text: a bare copy stores "AB\nCD" (5 units).
    @Test fun computationCopyStoresTheNormalizedText() {
        val m = model(max = 10, comps = listOf(
            ComputationDef(COPY, "/Claim", listOf(ComputationAlternative(null, "[IncidentNote]")))))
        val doc = m.newDocument().group("/Claim", 1).field(NOTE, CRLF, 1, 1)
        val c = DmInterpreter(m).compute(doc).first { it.path == COPY }
        assertEquals(ComputeOutcome.VALUE to "AB\nCD", c.outcome to c.value)
    }
}
