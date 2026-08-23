package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.edit.EditSession
import io.github.mbackschat.a12.dm.interpreter.DmInterpreter
import io.github.mbackschat.a12.dm.interpreter.eval.newDocument
import io.github.mbackschat.a12.dm.interpreter.load.ModelLoader
import io.github.mbackschat.a12.dm.modelkit.DatePrecision
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl
import io.github.mbackschat.a12.dm.rulekit.dsl.LocalizedText
import io.github.mbackschat.a12.dm.rulekit.dsl.Severity
import java.util.Locale
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Tri-engine lock for how a `$field.value$` message parameter is PRESENTED (KERNEL-SEMANTICS §13, [KF199]).
 * A stored value is written in the field's declared format; what an author reads in the message is that value
 * re-presented for the MESSAGE LOCALE — a locale date pattern and locale number separators — and only for the
 * nine locale tags the kernel maps.
 *
 * <b>Why the locale axis is a table and not two rows.</b> The map is keyed on the exact tag, so `fr_FR` MISSES
 * where `fr` hits, and a value under an unmapped locale renders verbatim. A consumer that treated presentation
 * as a cascade with a sensible default would pass every mapped row and be wrong on the one that matters, since
 * `fr_FR` is the ordinary spelling a model declares. `ch` and `gb` are in for the same reason from the other
 * side: they separate "the locale's own conventions" from "this table", `ch` pairing a German date pattern
 * with an apostrophe grouping separator and `gb` a British date pattern with English separators.
 *
 * Three deliberate non-effects, each with its row here because each is what an implementation gets wrong by
 * being helpful: a TIME is never re-presented, an unfilled field's DEFAULT is never presented (`0.00` under
 * `de_DE`, whose filled values use a comma), and a partially known DATE keeps its `00` markers rather than
 * being completed to the interval boundary it denotes.
 *
 * Insurance domain (own fixtures).
 */
class MessageDisplayPresentationDiffTest {

    private companion object {
        const val G = "/Policy"
        const val REFERENCE = "$G/Reference"
        const val ISO_DATE = "$G/IncidentOn"
        const val GERMAN_DATE = "$G/ReportedOn"
        const val PARTIAL_DATE = "$G/DiagnosedOn"
        const val AMOUNT = "$G/Claimed"
        const val UNPADDED = "$G/Excess"
        const val NEGATIVE = "$G/Adjustment"
        const val UNFILLED = "$G/Reserve"
        const val AT = "$G/CallAt"
        const val WHEN = "$G/RecordedAt"
        const val COVER = "$G/Cover"
        const val CODE = "ECHO"

        /** Every locale the fixture declares — the nine the kernel maps, plus `fr_FR`, which it does not. */
        val LOCALES = listOf("en_US", "de_DE", "en", "de", "fr", "nl", "ch", "gb", "us", "fr_FR")
    }

    private val model: String = run {
        var draft = DocumentModelDraft.named("claims-policy")
        for (locale in LOCALES) draft = draft.locale(locale)
        val base = draft
            .rootGroup("Policy") { policy ->
                policy
                    .stringField("Reference") { }
                    .dateField("IncidentOn") { it.format("yyyy-MM-dd") }
                    .dateField("ReportedOn") { it.format("dd.MM.yyyy") }
                    .dateField("DiagnosedOn") {
                        it.format("yyyy-MM-dd").datePrecision(DatePrecision.YEAR_OPTIONAL)
                    }
                    .numberField("Claimed") { it.fractionDigits(2, 2) }
                    .numberField("Excess") { it.fractionDigits(0, 2) }
                    .numberField("Adjustment") { it.fractionDigits(2, 2) }
                    .numberField("Reserve") { it.fractionDigits(2, 2) }
                    .timeField("CallAt") { }
                    .dateTimeField("RecordedAt") { }
                    .dateRangeField("Cover") { it.format("yyyy-MM-dd").separator("/") }
            }
            .build().toJson()
        EditSession.read(base).also { edit ->
            edit.addRule { b ->
                val reference = b.str(REFERENCE)
                var condition = reference.filled().and(b.field(UNFILLED).notFilled())
                for (path in listOf(ISO_DATE, GERMAN_DATE, PARTIAL_DATE, AMOUNT, UNPADDED, NEGATIVE,
                        AT, WHEN, COVER)) {
                    condition = condition.and(b.field(path).filled())
                }
                b.rule(b.group(G)).name("Echo").code(CODE).errorField(reference)
                    .`when`(condition).severity(Severity.ERROR)
                    .message(*LOCALES.map { LocalizedText(it, TEMPLATE) }.toTypedArray())
                    .build()
            }
            edit.validate()
        }.toJson()
    }

    private val definition = ModelLoader.load(model)

    private val kDoc = RuntimeLaws.doc().group(G, 1)
    private val iDoc = definition.newDocument().group(G, 1)

    init {
        for ((path, value) in VALUES) {
            kDoc.field(path, value, 1, 1)
            iDoc.field(path, value, 1, 1)
        }
    }

    /** dynamic Groovy (normative) == generated Java == interpreter, and all three equal [expected]. */
    private fun agree(locale: String, expected: String) {
        val jdk = Locale.forLanguageTag(locale.replace('_', '-'))
        val dynamic = RuntimeLaws.errorTextOf(model, CODE, kDoc, jdk).orElseThrow()
        val java = RuntimeLaws.kernelJavaEngine(model)
        val generated = java.errorTextOf(java.instance(kDoc), CODE, jdk).orElseThrow()
        val interpreted = DmInterpreter(definition).validateFull(iDoc, locale).single { it.code == CODE }.text
        assertEquals(expected, dynamic, "normative Groovy-dynamic at $locale")
        assertEquals(dynamic, generated, "generated Java at $locale  kernel=$dynamic java=$generated")
        assertEquals(dynamic, interpreted, "interpreter at $locale  kernel=$dynamic interp=$interpreted")
    }

    @Test fun enUs() = agree("en_US",
        "iso=[06/15/2024] de=[06/15/2024] partial=[06/00/2024] amount=[1,234.50] unpadded=[1,234.5] " +
            "negative=[-1,234,567.50] unfilled=[0.00] time=[13:45:00] at=[06/15/2024 13:45:00] " +
            "cover=[06/15/2024 07/01/2024]")

    @Test fun deDe() = agree("de_DE",
        "iso=[15.06.2024] de=[15.06.2024] partial=[00.06.2024] amount=[1.234,50] unpadded=[1.234,5] " +
            "negative=[-1.234.567,50] unfilled=[0.00] time=[13:45:00] at=[15.06.2024 13:45:00] " +
            "cover=[15.06.2024 01.07.2024]")

    /** The language-only tags are separate map keys and resolve to the same presentation as their pair. */
    @Test fun en() = agree("en",
        "iso=[06/15/2024] de=[06/15/2024] partial=[06/00/2024] amount=[1,234.50] unpadded=[1,234.5] " +
            "negative=[-1,234,567.50] unfilled=[0.00] time=[13:45:00] at=[06/15/2024 13:45:00] " +
            "cover=[06/15/2024 07/01/2024]")

    @Test fun de() = agree("de",
        "iso=[15.06.2024] de=[15.06.2024] partial=[00.06.2024] amount=[1.234,50] unpadded=[1.234,5] " +
            "negative=[-1.234.567,50] unfilled=[0.00] time=[13:45:00] at=[15.06.2024 13:45:00] " +
            "cover=[15.06.2024 01.07.2024]")

    /** French groups with a SPACE and separates with a comma. */
    @Test fun fr() = agree("fr",
        "iso=[15/06/2024] de=[15/06/2024] partial=[00/06/2024] amount=[1 234,50] unpadded=[1 234,5] " +
            "negative=[-1 234 567,50] unfilled=[0.00] time=[13:45:00] at=[15/06/2024 13:45:00] " +
            "cover=[15/06/2024 01/07/2024]")

    /** Dutch is the only dash-separated date pattern. */
    @Test fun nl() = agree("nl",
        "iso=[15-06-2024] de=[15-06-2024] partial=[00-06-2024] amount=[1.234,50] unpadded=[1.234,5] " +
            "negative=[-1.234.567,50] unfilled=[0.00] time=[13:45:00] at=[15-06-2024 13:45:00] " +
            "cover=[15-06-2024 01-07-2024]")

    /** Swiss pairs the German date pattern with an APOSTROPHE grouping separator and a dot decimal. */
    @Test fun ch() = agree("ch",
        "iso=[15.06.2024] de=[15.06.2024] partial=[00.06.2024] amount=[1'234.50] unpadded=[1'234.5] " +
            "negative=[-1'234'567.50] unfilled=[0.00] time=[13:45:00] at=[15.06.2024 13:45:00] " +
            "cover=[15.06.2024 01.07.2024]")

    /** British pairs a day-first date pattern with the English separators. */
    @Test fun gb() = agree("gb",
        "iso=[15/06/2024] de=[15/06/2024] partial=[00/06/2024] amount=[1,234.50] unpadded=[1,234.5] " +
            "negative=[-1,234,567.50] unfilled=[0.00] time=[13:45:00] at=[15/06/2024 13:45:00] " +
            "cover=[15/06/2024 01/07/2024]")

    @Test fun us() = agree("us",
        "iso=[06/15/2024] de=[06/15/2024] partial=[06/00/2024] amount=[1,234.50] unpadded=[1,234.5] " +
            "negative=[-1,234,567.50] unfilled=[0.00] time=[13:45:00] at=[06/15/2024 13:45:00] " +
            "cover=[06/15/2024 07/01/2024]")

    /**
     * The row that makes the map a LOOKUP rather than a cascade: `fr_FR` is a legal declared locale and a
     * perfectly ordinary spelling, and it is not a key — so every value renders exactly as stored, in its own
     * declared format, including the German-format date beside the ISO one.
     */
    @Test fun anUnmappedLocaleRendersEveryValueAsStored() = agree("fr_FR",
        "iso=[2024-06-15] de=[15.06.2024] partial=[2024-06-00] amount=[1234.50] unpadded=[1234.5] " +
            "negative=[-1234567.50] unfilled=[0.00] time=[13:45:00] at=[2024-06-15T13:45:00] " +
            "cover=[2024-06-15/2024-07-01]")
}

private const val TEMPLATE =
    "iso=[\$IncidentOn.value\$] de=[\$ReportedOn.value\$] partial=[\$DiagnosedOn.value\$] " +
        "amount=[\$Claimed.value\$] unpadded=[\$Excess.value\$] negative=[\$Adjustment.value\$] " +
        "unfilled=[\$Reserve.value\$] time=[\$CallAt.value\$] at=[\$RecordedAt.value\$] " +
        "cover=[\$Cover.value\$]"

/** One document for every row; only the message locale varies across the table. */
private val VALUES = listOf(
    "/Policy/Reference" to "P-1",
    "/Policy/IncidentOn" to "2024-06-15",
    "/Policy/ReportedOn" to "15.06.2024",
    "/Policy/DiagnosedOn" to "2024-06-00",
    "/Policy/Claimed" to "1234.50",
    "/Policy/Excess" to "1234.5",
    "/Policy/Adjustment" to "-1234567.50",
    "/Policy/CallAt" to "13:45:00",
    "/Policy/RecordedAt" to "2024-06-15T13:45:00",
    "/Policy/Cover" to "2024-06-15/2024-07-01",
)
