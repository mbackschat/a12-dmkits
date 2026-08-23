package io.github.mbackschat.a12.dm.adapter.laws;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft;

/**
 * KERNEL-FINDINGS §6 — a temporal field's declared <b>format</b> is checked against a fixed <b>vocabulary of
 * strings</b>, not against the field's KIND (SPEC-2026-08-23-10).
 *
 * <p><b>This corrects the framing of [KF81], which is ours.</b> That entry enumerated the admitted formats
 * <em>per kind</em> — "DATE: both {@code yyyy-MM-dd} and {@code dd.MM.yyyy}; TIME: the full {@code HH:mm:ss}
 * is required; DATE_TIME: the only date-bearing format is ISO" — and every cell it ran was inside its own
 * kind's column. A per-kind allowlist and a shared vocabulary predict identical results on that grid, so the
 * enumeration was complete in one direction and untested in the other. Crossing it inverts the reading.
 *
 * <p>{@link #aTimeFieldIsRefusedItsOwnNearMissAndAdmittedADateFormat} is the discriminating pair: the same
 * TIME field refuses {@code HH:mm} — a natural spelling for its kind — and accepts {@code yyyy-MM-dd}, which
 * is not one. No kind-keyed gate produces that pair.
 *
 * <p><b>Static admission only.</b> What a mismatched declaration stores, renders, or is admitted as an
 * operand is a different question, characterized for the degenerate DATE_TIME case in [KF81] and untouched
 * here.
 */
class TemporalFormatVocabularyLawsTest {

    private enum Kind { DATE, TIME, DATE_TIME }

    private static final String ADMITTED = "ADMITTED";
    private static final String REFUSED = "REFUSED";

    /** A one-field model, so the only thing the consistency gate can object to is the declaration. */
    private static String verdict(Kind kind, String format) {
        String json = DocumentModelDraft.named("temporal").locale("en_US")
                .rootGroup("Doc", d -> {
                    switch (kind) {
                        case DATE -> d.dateField("F", f -> f.format(format));
                        case TIME -> d.timeField("F", f -> f.format(format));
                        case DATE_TIME -> d.dateTimeField("F", f -> f.format(format));
                    }
                })
                .build().toJson();
        // No catch: an exception here would be a CLIENT-side refusal, and this law is about the kernel's.
        // Letting it propagate is what keeps a builder throw from being reported as a kernel rejection.
        return RuntimeLaws.consistencyMessages(json).isEmpty() ? ADMITTED : REFUSED;
    }

    /** The pair that separates a vocabulary check from a kind check, on one field of one kind. */
    @Test
    void aTimeFieldIsRefusedItsOwnNearMissAndAdmittedADateFormat() {
        assertEquals(REFUSED, verdict(Kind.TIME, "HH:mm"), "HH:mm is not in the vocabulary");
        assertEquals(ADMITTED, verdict(Kind.TIME, "yyyy-MM-dd"), "a DATE format on a TIME field");
    }

    /** Every cross-kind witness the vocabulary reading predicts, plus each kind's own default as a control. */
    @Test
    void theVocabularyIsSharedAcrossTheTemporalKinds() {
        Map<String, String> actual = new LinkedHashMap<>();
        actual.put("DATE · HH:mm:ss", verdict(Kind.DATE, "HH:mm:ss"));
        actual.put("DATE · the ISO datetime format", verdict(Kind.DATE, "yyyy-MM-dd'T'HH:mm:ss"));
        actual.put("TIME · yyyy-MM-dd", verdict(Kind.TIME, "yyyy-MM-dd"));
        actual.put("TIME · dd.MM.yyyy", verdict(Kind.TIME, "dd.MM.yyyy"));
        actual.put("TIME · the fragment format MM", verdict(Kind.TIME, "MM"));
        actual.put("DATE · yyyy-MM-dd — control", verdict(Kind.DATE, "yyyy-MM-dd"));
        actual.put("TIME · HH:mm:ss — control", verdict(Kind.TIME, "HH:mm:ss"));
        actual.put("DATE_TIME · the ISO datetime format — control",
                verdict(Kind.DATE_TIME, "yyyy-MM-dd'T'HH:mm:ss"));

        Map<String, String> expected = new LinkedHashMap<>();
        actual.keySet().forEach(k -> expected.put(k, ADMITTED));
        assertEquals(expected, actual, "the gate reads the string, so a kind's default is not a restriction");
    }

    /**
     * The negative half — without it "no kind check" is indistinguishable from "no check". Nine strings, most
     * of them natural for the kind offering them, are refused at the model level.
     */
    @Test
    void nineUnrecognizedStringsAreRefused() {
        Map<String, String> actual = new LinkedHashMap<>();
        for (String format : List.of("HH:mm", "H:mm:ss", "HH:mm:ss.SSS", "hh:mm:ss a", "HHmmss", "HH-mm-ss")) {
            actual.put("TIME · " + format, verdict(Kind.TIME, format));
        }
        for (String format : List.of("yyyy-MM-dd HH:mm:ss", "dd.MM.yyyy HH:mm:ss", "yyyy-MM-dd HH:mm")) {
            actual.put("DATE_TIME · " + format, verdict(Kind.DATE_TIME, format));
        }

        Map<String, String> expected = new LinkedHashMap<>();
        actual.keySet().forEach(k -> expected.put(k, REFUSED));
        assertEquals(expected, actual, "the vocabulary is finite: an unrecognized string is refused");
    }

    /** An admitted mismatch is stored as declared — the gate accepts the string, it does not rewrite it. */
    @Test
    void anAdmittedMismatchIsReadBackUnnormalized() {
        String json = DocumentModelDraft.named("temporal").locale("en_US")
                .rootGroup("Doc", d -> d.timeField("F", f -> f.format("yyyy-MM-dd")))
                .build().toJson();
        assertTrue(json.contains("yyyy-MM-dd"), () -> "the declared format survives the round trip: " + json);
        assertEquals(List.of(), RuntimeLaws.consistencyMessages(json));
    }
}
