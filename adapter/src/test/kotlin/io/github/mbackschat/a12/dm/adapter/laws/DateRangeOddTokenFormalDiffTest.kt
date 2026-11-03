package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.util.Locale

/**
 * Two acceptance cells of a12-kernel-lean SPEC-2026-10-26-21 and -27 ([KERNEL-FINDINGS KF633], [KF634]), requalified
 * on kernel 31.1.1: a DateRange declared with an empty separator, and a pattern holding an escaped backslash before
 * `p`, which the consistency check refuses as the checker refuses it.
 *
 * On 30.8.1 the first cell locked KF633's odd-length token, a formal error because an empty separator split the text
 * in half. On 31.1.1 the empty separator reads as `/` (KERNEL-FINDINGS KF658), so every unseparated token lacks its
 * separator and the cell now locks that, on all three engines.
 *
 * Own domain (a tour season).
 */
class DateRangeOddTokenFormalDiffTest {

    private val model = DocumentModelDraft.named("tour-season").locale("en_US")
        .rootGroup("Tour") { t -> t.stringField("Ref") { }
            .dateRangeField("Season") { it.format("MM").separator("") } }
        .build().toJson()

    /** An empty separator is the slash, so a token without one lacks its separator whatever its length. */
    @Test
    fun anUnseparatedTokenLacksItsSeparatorOnAllThreeEngines() {
        val values = listOf("061", "06109", "6", "0609", "06/09")
        val dynamicEngine = RuntimeLaws.kernelEngine(model)
        val staticEngine = RuntimeLaws.kernelJavaEngine(model)
        val kernel = values.associateWith { v ->
            dynamicEngine.validationMessages(dynamicEngine.instance(season(v)), null, Locale.US).map { it.errorCode } }
        val static = values.associateWith { v ->
            staticEngine.validationMessages(staticEngine.instance(season(v)), null, Locale.US).map { it.errorCode } }
        val interpreted = values.associateWith { v -> DocumentDifferential.interpreterMessages(model, season(v)).map { it.code } }
        assertEquals(kernel, static, "static Java")
        assertEquals(kernel, interpreted, "the interpreter")
        val missing = listOf("datumBereichTrennerFehlt")
        assertEquals(linkedMapOf("061" to missing, "06109" to missing, "6" to missing, "0609" to missing,
            "06/09" to emptyList()), kernel)
    }

    private fun season(value: String) = """{"Tour":{"Ref":"R","Season":"$value"}}"""

    /** Each key is the pattern as the model's JSON text spells it, so `a\\p` is the regex `a\p`. */
    @Test
    fun aBackslashBeforePIsRefusedEvenWhenTheBackslashIsEscaped() {
        val authored = DocumentModelDraft.named("tour-code").locale("en_US")
            .rootGroup("Tour") { t -> t.stringField("Code") { it.pattern("aXp", "Use the code format.") } }
            .build().toJson()
        val models = listOf("a\\\\\\\\p", "a\\\\p", "a\\\\\\\\d").associateWith { authored.replace("aXp", it) }
        val observed = models.mapValues { (_, model) -> RuntimeLaws.consistencyMessages(model) }
        assertEquals(models.mapValues { (_, model) -> MessageParameterProbes.kernel(model) },
            models.mapValues { (_, model) -> MessageParameterProbes.portable(model) }, "the checker's classes")
        assertEquals(linkedMapOf(
            "a\\\\\\\\p" to listOf("ERROR: Field with path '/Tour/Code': The pattern 'a\\\\p' is invalid. [MVK_PATTERN_INVALID]"),
            "a\\\\p" to listOf("ERROR: Field with path '/Tour/Code': The pattern 'a\\p' is invalid. [MVK_PATTERN_INVALID]"),
            "a\\\\\\\\d" to emptyList(),
        ), observed)
    }
}
