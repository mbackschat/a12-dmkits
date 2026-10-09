package io.github.mbackschat.a12.dm.adapter.laws;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.Objects;

import org.junit.jupiter.api.Test;

/**
 * A12's own Document-JSON ingress is <b>value-normalizing</b> — the measurement, against the real kernel
 * (30.8.1). {@code DocumentV2JsonReader} stringifies each JSON node ({@code asText()}),
 * {@code FieldValueConverter} converts that text to {@code String | Boolean | BigDecimal | Instant |
 * InstantRange}, and every later text is re-rendered <em>from the stored value</em>: the serializer's
 * {@code toJsonFieldNode}, and the runtime's {@code IData} via {@code MetaField.convertFromBasicType} →
 * {@code FormatDefinition*.convertFromBasicType}. So an authored spelling survives neither channel, and a
 * value the converter rejects survives both untouched ({@code [A12K-3638]}).
 *
 * <p>Each row records the two consumer-visible observables: what {@code serializeV2} writes back, and which
 * formal errors validation then reports. The second is what pins the runtime's text — for
 * {@code /Order/Quantity} (a {@code maxFractionalDigits: 0} field) {@code 250.00} draws <em>no</em>
 * decimal-separator error while {@code 0.0000001} does, so the runtime STRIPS a fractional trailing zero; and
 * for {@code /Order/LineTotal} (a {@code minFractionalDigits: 2} field) a bare {@code 250} draws no
 * missing-separator error, so it also PADS. Neither half of
 * {@code bd.setScale(max(bd.stripTrailingZeros().scale(), minFractionalDigits)).toPlainString()} is discriminated
 * by these rows ALONE, though: they fix the rendering only up to the codes they can fire. The lexical
 * discrimination — a length bound counting the rendered characters — lives in
 * {@code NumberRenderChannelKernelTest}, together with the measurement that every other evaluation channel reads
 * this same one text.
 *
 * <p>Distinct from every other oracle in this package: those build a V1 instance from raw <em>strings</em>,
 * which {@code ValidationData.getValue} returns unchanged — a regime where no conversion happens at all. The
 * interpreter's side of this ingress is {@code A12JsonIngressDiffTest}.
 */
class A12JsonIngressKernelTest {

    private static final String MODEL = RuntimeLaws.model("models/order.dm.json");

    /** One authored field value, and what A12 makes of it. */
    record Row(String field, String authored, String rewrittenValue, List<String> codes) {
        String documentJson() {
            return "{\"Order\":{\"" + field + "\":" + authored + "}}";
        }

        String expectedRewritten() {
            return "{\"Order\":{\"" + field + "\":" + rewrittenValue + "}}";
        }
    }

    private static Row row(String field, String authored, String rewrittenValue, String... codes) {
        return new Row(field, authored, rewrittenValue, List.of(codes));
    }

    /**
     * The full measured matrix. {@code /Order/Quantity} is {@code maxFractionalDigits: 0},
     * {@code /Order/UnitPrice} is {@code 2}; neither declares {@code leadingZerosAllowed}.
     */
    static List<Row> rows() {
        return List.of(
            // ── NUMBER: every authored spelling collapses to the BigDecimal's own plain rendering ──
            row("Quantity", "250", "250"),
            row("Quantity", "\"250.00\"", "250.00"),
            row("Quantity", "1e5", "100000"),
            row("Quantity", "\"1e5\"", "100000"),
            row("Quantity", "1e3", "1000"),
            row("Quantity", "\"1e3\"", "1000"),
            row("Quantity", "1.5e1", "15"),
            row("Quantity", "\"007\"", "7"),
            row("Quantity", "\"+5\"", "5"),
            row("Quantity", "0e5", "0"),
            // The SIGN rows, which decide the reachability of two formal classes on this route: a leading plus
            // and a minus on a ZERO magnitude are both canonicalised away with no code, so `zahlMitPlus` and
            // `zahlNullEnthaeltVorzeichen` have no reachable precondition here at all — while `"-5"` keeps its
            // sign, so the predicate is (plus) or (minus on a zero magnitude), not "the sign is dropped".
            // a12-kernel-lean reached the same conclusion from 278 observations on their own carrier and asked
            // which route ours takes; the direct-cell route every other oracle in this package uses is what
            // reaches those two classes (KF253).
            row("Quantity", "-0.0", "0.0"),
            row("Quantity", "\"-0.0\"", "0.0"),
            row("Quantity", "-0", "0"),
            row("Quantity", "-0e5", "0"),
            row("Quantity", "\"-5\"", "-5"),
            row("Quantity", "\"\"", "null"),
            row("Quantity", "0.0000001", "0.0000001", "zahlHatDezimalTrenner"),
            row("Quantity", "1234567890123456789012.5", "1234567890123456789012.5", "stelligkeitZuLang"),
            // …and a value the converter rejects is kept verbatim and written back as a STRING.
            row("Quantity", "\"1,5\"", "\"1,5\"", "zahlHatUngueltigeZeichen"),
            row("Quantity", "\"nope\"", "\"nope\"", "zahlHatUngueltigeZeichen"),
            row("UnitPrice", "2.5e2", "250"),
            row("UnitPrice", "250.0", "250.0"),
            row("UnitPrice", "0.005", "0.005", "zahlFalscheAnzahlNachkommastellen"),
            // …and the PADDING direction, which needs a `minFractionalDigits` field to be reachable at all:
            // /Order/LineTotal is minFrac 2 / maxFrac 2, so the runtime text of every value below is `250.00`
            // even where the serializer writes `250`, `250.0`, or `250.5`. Without such a field in this model the
            // whole class sat outside the matrix — an "exhaustive" residual set is only exhaustive over its
            // generator (IF231, was IG87).
            row("LineTotal", "250", "250"),
            row("LineTotal", "250.0", "250.0"),
            row("LineTotal", "250.5", "250.5"),
            row("LineTotal", "250.00", "250.00"),
            row("LineTotal", "\"+250.00\"", "250.00"),
            row("LineTotal", "250.555", "250.555", "zahlFalscheAnzahlNachkommastellen"),
            row("LineTotal", "\"\"", "null"),

            // ── BOOLEAN / CONFIRM: `equalsIgnoreCase`, then `booleanNode` ──
            row("ExpressShipping", "\"TRUE\"", "true"),
            row("ExpressShipping", "\"True\"", "true"),
            row("ExpressShipping", "\"FALSE\"", "false"),
            row("ExpressShipping", "false", "false"),
            row("ExpressShipping", "\"\"", "null"),
            row("ExpressShipping", "\"yes\"", "\"yes\"", "feldJaNeinFalsch"),
            row("TermsConfirmed", "\"TRUE\"", "true"),
            // A confirm has NO false — `parseConfirm` accepts only `true`, so even a native JSON `false` is a
            // conversion failure and comes back as the STRING "false".
            row("TermsConfirmed", "false", "\"false\"", "feldJaFalsch"),
            row("TermsConfirmed", "\"false\"", "\"false\"", "feldJaFalsch"),

            // ── the DATE family: non-lenient `SimpleDateFormat`, so ONLY the model format parses; every other
            //    spelling fails conversion and is kept verbatim. Nothing to normalize. ──
            row("OrderDate", "\"2024-03-05\"", "\"2024-03-05\""),
            row("OrderDate", "\"2024-3-5\"", "\"2024-3-5\"", "datumFormatFalsch"),
            row("OrderDate", "\"2024-03-5\"", "\"2024-03-5\"", "datumFormatFalsch"),
            row("OrderDate", "\"05.03.2024\"", "\"05.03.2024\"", "datumFormatFalsch"),
            row("OrderDate", "\"20240305\"", "\"20240305\"", "datumFormatFalsch"),
            row("OrderDate", "\"12024-03-05\"", "\"12024-03-05\"", "datumFormatFalsch"),
            row("OrderDate", "\"2024-02-30\"", "\"2024-02-30\"", "datumFormatFalsch"),
            row("OrderDate", "\"\"", "null"),
            row("ApproxDate", "\"05.03.2024\"", "\"05.03.2024\""),
            row("ApproxDate", "\"5.3.2024\"", "\"5.3.2024\"", "datumFormatFalsch"),
            row("PickupTime", "\"14:05:00\"", "\"14:05:00\""),
            row("PickupTime", "\"14:5:0\"", "\"14:5:0\"", "datumFormatFalsch"),
            row("PickupTime", "\"14:05\"", "\"14:05\"", "datumFormatFalsch"),
            row("ScheduledAt", "\"2024-03-05T14:05:00\"", "\"2024-03-05T14:05:00\""),
            row("ScheduledAt", "\"2024-3-5T14:5:0\"", "\"2024-3-5T14:5:0\"", "datumFormatFalsch"),
            row("CoverageWindow", "\"01.01.2024-31.12.2024\"", "\"01.01.2024-31.12.2024\""),
            row("CoverageWindow", "\"1.1.2024-31.12.2024\"", "\"1.1.2024-31.12.2024\"", "datumBereichFormatFalsch"),

            // ── STRING / Enumeration: never normalized; the converter succeeds with the text itself ──
            row("CustomerName", "\"007\"", "\"007\""),
            row("CustomerName", "5", "\"5\""),
            row("CustomerName", "true", "\"true\""),
            row("CustomerName", "\"\"", "\"\""),
            // a native decimal reaches a String as BigDecimal.toString(), the stringification asText() performs:
            // its signed scale survives, so an exponent stays scientific and a tiny value turns scientific past the
            // threshold, while a quoted token is kept as typed (a12-kernel-lean SPEC-2026-10-09-11)
            row("CustomerName", "1e3", "\"1E+3\""),
            row("CustomerName", "\"1e3\"", "\"1e3\""),
            row("CustomerName", "1.5e2", "\"1.5E+2\""),
            row("CustomerName", "0.0000001", "\"1E-7\""),
            row("CustomerName", "0.000001", "\"0.000001\""),
            row("CustomerName", "250.00", "\"250.00\""),
            row("CustomerName", "-0.0", "\"0.0\""),
            row("CustomerName", "-0", "\"0\""),
            row("Priority", "\"EXPRESS\"", "\"EXPRESS\""),
            row("Priority", "\"express\"", "\"express\"", "stringFalschesMuster"),
            row("RushLevel", "1", "\"1\"")
        );
    }

    @Test
    void a12RewritesEveryFieldValueFromTheStoredValueAndValidatesThatRenderingNotTheAuthoredText() {
        List<String> mismatches = rows().stream()
                .map(row -> {
                    RuntimeLaws.JsonIngress observed = RuntimeLaws.jsonIngress(MODEL, row.documentJson());
                    if (row.expectedRewritten().equals(observed.rewritten())
                            && row.codes().equals(observed.codes())) {
                        return null;
                    }
                    return "%s = %s → rewritten %s codes %s (expected %s / %s)".formatted(
                            row.field(), row.authored(), observed.rewritten(), observed.codes(),
                            row.expectedRewritten(), row.codes());
                })
                .filter(Objects::nonNull)
                .toList();
        assertEquals(List.of(), mismatches, "A12's Document-JSON ingress changed");
    }

    /**
     * A pre-Gregorian date is the ONE date value A12 re-renders into a different spelling: parsing uses
     * {@code SimpleDateFormat}'s Julian/Gregorian hybrid calendar while
     * {@code FieldValueConverter.serializeInstant} formats with the proleptic-Gregorian
     * {@code java.time.DateTimeFormatter}. Both spellings are {@code datumFalsch} regardless, so the shift is
     * confined to a domain that is already a formal error.
     */
    @Test
    void aPreGregorianDateIsTheOneDateValueA12RewritesIntoADifferentSpelling() {
        RuntimeLaws.JsonIngress observed = RuntimeLaws.jsonIngress(MODEL,
                "{\"Order\":{\"OrderDate\":\"1583-10-15\"}}");

        assertEquals("{\"Order\":{\"OrderDate\":\"1583-10-14\"}}", observed.rewritten());
        assertEquals(List.of("datumFalsch"), observed.codes());
    }
}
