package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.interpreter.DmInterpreter
import io.github.mbackschat.a12.dm.interpreter.eval.Document
import io.github.mbackschat.a12.dm.interpreter.model.FieldDef
import io.github.mbackschat.a12.dm.interpreter.model.Kind
import io.github.mbackschat.a12.dm.interpreter.model.ModelDef
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft.GroupBuilder
import io.github.mbackschat.a12.dm.modelkit.FieldKind
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.util.function.Consumer

/**
 * Differential — the kernel's BASELINE value checks (`FormalChecker.checkFieldValueFormat`, IF43), run for EVERY
 * field kind BEFORE the type-specific format check: legal **charset** → **leading** blank → **trailing** blank.
 * With no programming-language charset configured (a DM-JSON model) the default legal set is the **BMP**, so any
 * supplementary-plane char (a UTF-16 surrogate, e.g. an emoji) → `ZeichenNichtImZeichensatz`; a leading blank →
 * `fuehrendesBlank`; a trailing blank → `nachfolgendesBlank`. The baseline pre-empts the type check (a ` `-led
 * DATE is `fuehrendesBlank`, not a date-format error). Compared kernel-vs-interpreter on the (code,pointer) pairs
 * for these three codes, across STRING / NUMBER / DATE. KERNEL-FINDINGS "Partial validation"/§3 charset.
 */
class BaselineValueFormalDiffTest {

    private val json = DocumentModelDraft.named("c").locale("en_US")
        .rootGroup("C", Consumer { g: GroupBuilder ->
            g.field("S", FieldKind.STRING)
                .field("N", FieldKind.NUMBER)
                .dateField("D", Consumer { it.format("yyyy-MM-dd") })
        })
        .build().toJson()
    private val def = ModelDef(listOf(
        FieldDef(S, Kind.STRING), FieldDef(N, Kind.NUMBER, scale = 0), FieldDef(D, Kind.DATE, format = "yyyy-MM-dd")),
        emptyList())

    private val codes = setOf("ZeichenNichtImZeichensatz", "fuehrendesBlank", "nachfolgendesBlank")

    /** Set [field]=[value], compare the kernel's vs interpreter's baseline (code@pointer) pairs. */
    private fun agree(field: String, value: String, label: String) {
        val k = RuntimeLaws.messages(json, RuntimeLaws.doc().group("/C", 1).field("/C/$field", value, 1, 1))
            .filter { it.code() in codes }.map { "${it.code()}@${it.field()}" }.sorted()
        val i = DmInterpreter(def).validate(Document(def.kinds).group("/C", 1).field("/C/$field", value, 1, 1))
            .messages.filter { it.code in codes }.map { "${it.code}@${it.errorPath}" }.sorted()
        assertEquals(k, i, "$label — kernel=$k interp=$i")
    }

    @Test fun stringAstral() = agree("S", "x😀", "STRING astral (emoji) → charset")   // U+1F600
    @Test fun stringLeadingBlank() = agree("S", " ab", "STRING leading blank")
    @Test fun stringTrailingBlank() = agree("S", "ab ", "STRING trailing blank")
    @Test fun stringInternalBlankClean() = agree("S", "a b", "STRING internal blank → clean")
    @Test fun stringBmpClean() = agree("S", "café™€中", "STRING BMP chars → clean (legal)")

    @Test fun numberLeadingBlank() = agree("N", " 5", "NUMBER leading blank")
    @Test fun numberTrailingBlank() = agree("N", "5 ", "NUMBER trailing blank (nachfolgendesBlank, not fuehrendes)")

    @Test fun dateLeadingBlank() = agree("D", " 2024-01-01", "DATE leading blank pre-empts date-format")
    @Test fun dateTrailingBlank() = agree("D", "2024-01-01 ", "DATE trailing blank pre-empts date-format")

    private companion object {
        const val S = "/C/S"; const val N = "/C/N"; const val D = "/C/D"
    }
}
