package io.github.mbackschat.a12.dm.modelkit;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.core.ModelIndex;
import io.github.mbackschat.a12.dm.core.ValueKind;

/**
 * modelkit M2 — type-specific config (String min/max/pattern, Number scale/min/max, Enumeration
 * values+labels) and field requiredness, authored through type-safe per-kind builders so illegal
 * combinations (a min-length on a number, an empty enumeration) can't be written. The kernel is the
 * oracle: the configured model passes {@code checkConsistency}, and the index reflects the config.
 * Own domain (a permit), not adapted from any fixture.
 */
class FieldConfigTest {

    private static DocumentModelDraft permit() {
        return DocumentModelDraft.named("permit").locale("en_US").locale("de_DE")
                .rootGroup("Permit", p -> p
                        .stringField("Code", s -> s.minLength(3).maxLength(12)
                                .pattern("[A-Z][A-Z0-9]+", "Code must be a letter then letters or digits.").required())
                        .numberField("Fee", n -> n.fractionDigits(2).min("0").max("10000"))
                        .enumField("Category", e -> e
                                .value("RESIDENTIAL", l -> l.label("en_US", "Residential").label("de_DE", "Wohngebiet"))
                                .value("COMMERCIAL", l -> l.label("en_US", "Commercial").label("de_DE", "Gewerblich"))
                                .required())
                        .field("IssuedOn", FieldKind.DATE, Requiredness.REQUIRED))
                .build();
    }

    private static ModelIndex index(DocumentModelDraft draft) {
        try {
            return ModelIndex.ofSelfContained(draft.toJson());
        } catch (Exception e) {
            throw new RuntimeException("emitted DM-JSON failed checkConsistency:\n" + draft.toJson(), e);
        }
    }

    @Test
    void configuredModelIsKernelValidAndReflectedInTheIndex() {
        ModelIndex index = index(permit());

        assertEquals(ValueKind.STRING, index.kindOf("/Permit/Code"));
        assertEquals(ValueKind.NUMBER, index.kindOf("/Permit/Fee"));
        assertEquals(ValueKind.ENUMERATION, index.kindOf("/Permit/Category"));
        assertEquals(ValueKind.DATE, index.kindOf("/Permit/IssuedOn"));

        // number scale (maxFractionalDigits) and enum domain are surfaced by the index
        assertEquals(2, index.numberScale("/Permit/Fee"));
        assertEquals(List.of("RESIDENTIAL", "COMMERCIAL"), index.enumValues("/Permit/Category"));
    }

    @Test
    void indexSurfacesDeclaredBounds() {
        ModelIndex index = index(permit());

        // numeric inclusive bounds + string length/pattern — the declared config read back (no longer dropped)
        assertEquals(0, index.numberMin("/Permit/Fee").orElseThrow().compareTo(new java.math.BigDecimal("0")));
        assertEquals(0, index.numberMax("/Permit/Fee").orElseThrow().compareTo(new java.math.BigDecimal("10000")));
        assertEquals(java.util.OptionalInt.of(3), index.stringMinLength("/Permit/Code"));
        assertEquals(java.util.OptionalInt.of(12), index.stringMaxLength("/Permit/Code"));
        assertEquals(java.util.Optional.of("[A-Z][A-Z0-9]+"), index.stringPattern("/Permit/Code"));

        // a config accessor probed on the wrong kind is empty, not an exception (safe to call on any field)
        assertTrue(index.numberMin("/Permit/Code").isEmpty(), "min on a string is empty");
        assertTrue(index.stringPattern("/Permit/Fee").isEmpty(), "pattern on a number is empty");
        assertTrue(index.stringMinLength("/Permit/Category").isEmpty(), "minLength on an enum is empty");
    }

    @Test
    void emitsTheConfigSlotsAndRequiredness() {
        String json = permit().toJson();
        // string config
        assertTrue(json.contains("\"minLength\" : 3"), json);
        assertTrue(json.contains("\"maxLength\" : 12"), json);
        assertTrue(json.contains("\"pattern\" : \"[A-Z][A-Z0-9]+\""), json);
        // number config
        assertTrue(json.contains("\"maxFractionalDigits\" : 2"), json);
        assertTrue(json.contains("\"minValue\" : 0"), json);
        assertTrue(json.contains("\"maxValue\" : 10000"), json);
        // requiredness: plain required vs the parent-relative default
        assertTrue(json.contains("\"mode\" : \"absoluteOrRelativeToNextRepAncestor\""), json);
        // enum per-value label
        assertTrue(json.contains("\"text\" : \"Gewerblich\""), json);
    }

    @Test
    void requiredIfParentPresentUsesTheRelativeMode() {
        String json = DocumentModelDraft.named("m").locale("en_US")
                .rootGroup("G", g -> g.stringField("F", s -> s.requiredIfParentPresent()))
                .build().toJson();
        assertTrue(json.contains("\"mode\" : \"relativeToParent\""), json);
    }

    @Test
    void illegalConfigIsRejectedAtBuildTime() {
        // min length above max length
        assertThrows(IllegalArgumentException.class, () -> DocumentModelDraft.named("m").locale("en_US")
                .rootGroup("G", g -> g.stringField("F", s -> s.minLength(10).maxLength(5)))
                .build());
        // an enumeration with no values can't be a valid field
        assertThrows(IllegalStateException.class, () -> DocumentModelDraft.named("m").locale("en_US")
                .rootGroup("G", g -> g.enumField("F", e -> { }))
                .build());
        // noValueValidation requires line breaks (MVK_STRING_TYPE_NO_VAL_AND_NO_LINE_BREAK) — caught at build
        assertThrows(IllegalArgumentException.class, () -> DocumentModelDraft.named("m").locale("en_US")
                .rootGroup("G", g -> g.stringField("F", s -> s.noValueValidation()))
                .build());
        // String and typedef patterns share the same kernel-admission preflight as rulekit.
        assertThrows(IllegalArgumentException.class, () -> DocumentModelDraft.named("m").locale("en_US")
                .rootGroup("G", g -> g.stringField("F", s -> s.pattern("(?<=a)b", "invalid")))
                .build());
        assertThrows(IllegalArgumentException.class, () -> DocumentModelDraft.named("m").locale("en_US")
                .typeDef("T", t -> t.string(s -> s.pattern("\\P{L}+", "invalid")))
                .rootGroup("G", g -> g.typedefField("F", "T"))
                .build());
    }

    /**
     * The kernel caps a number field's {@code maxFractionalDigits} at <b>14</b> — declaring 15 draws a plain,
     * MVK-less model-check message ("Only values smaller than or equal to 14 are allowed") that names neither the
     * builder call nor the property. Caught fail-fast at spec construction instead, so every path (builder, CLI,
     * schema import, editor) reports a corrective error naming the property and the cap.
     * See KERNEL-FINDINGS §9 supplement "the host conversion's PARSE step".
     */
    @Test
    void aNumberScaleAboveTheKernelCapIsRejectedFailFast() {
        assertDoesNotThrow(() -> DocumentModelDraft.named("m").locale("en_US")
                .rootGroup("G", g -> g.numberField("F", n -> n.fractionDigits(14)))
                .build(), "the cap itself is legal");
        var tooMany = assertThrows(IllegalArgumentException.class, () -> DocumentModelDraft.named("m")
                .locale("en_US")
                .rootGroup("G", g -> g.numberField("F", n -> n.fractionDigits(15)))
                .build());
        assertTrue(tooMany.getMessage().contains("maxFractionalDigits") && tooMany.getMessage().contains("14"),
                () -> "the message must name the property and the cap: " + tooMany.getMessage());
        // min is capped by the same wall: min ≤ max ≤ 14 must hold, and an absent max defaults to 0 (IF78)
        assertThrows(IllegalArgumentException.class, () -> DocumentModelDraft.named("m").locale("en_US")
                .rootGroup("G", g -> g.numberField("F", n -> n.fractionDigits(15, 15)))
                .build());
    }

    // ── the inverted-bound refusals MIRROR kernel laws, so they cannot over-reject ───────────────────────
    //
    // Part of the 2026-07-25 pre-check audit: the MVK ledger tracks only the 300 MVK codes, so an MVK-less
    // client-side refusal like these is invisible to it. modelkit refuses both inverted bounds at spec
    // construction; these two cases prove the kernel refuses the same models, so the refusals are earlier and
    // better-worded, not stricter. (The one KNOWN deliberate over-strictness in this file's family is the enum
    // S-value domain check — see API-FINDINGS 96, where being stricter than the kernel is the documented intent.)
    @Test
    void theKernelAlsoRejectsAnInvertedStringLength() {
        String legal = DocumentModelDraft.named("m").locale("en_US")
                .rootGroup("G", g -> g.stringField("F", s -> s.minLength(2).maxLength(5)))
                .build().toJson();
        String inverted = legal.replaceFirst("(\"minLength\"\\s*:\\s*)2", "$19");
        assertNotEquals(legal, inverted, "the patch must land");
        assertThrows(RuntimeException.class,
                () -> io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel.ofJson(inverted));
    }

    @Test
    void theKernelAlsoRejectsAnInvertedNumberRange() {
        String legal = DocumentModelDraft.named("m").locale("en_US")
                .rootGroup("G", g -> g.numberField("F", n -> n.min("1").max("10")))
                .build().toJson();
        String inverted = legal.replaceFirst("(\"minValue\"\\s*:\\s*)1\\b", "$199");
        assertNotEquals(legal, inverted, "the patch must land");
        assertThrows(RuntimeException.class,
                () -> io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel.ofJson(inverted));
    }

    @Test
    void incompleteEnumLabelsAreRejected() {
        // the kernel's MVK_COUNT_OF_VALUES_INVALID: labels are all-or-nothing per declared locale —
        // labeling one value but not another (or missing a declared locale) is caught fail-fast as a SPEC
        // error (IllegalArgumentException) — so the CLI reports a clean "invalid field spec", not RK_INTERNAL_ERROR
        assertThrows(IllegalArgumentException.class, () -> DocumentModelDraft.named("m").locale("en_US")
                .rootGroup("G", g -> g.enumField("F", e -> e
                        .value("A", l -> l.label("en_US", "Ay"))
                        .value("B")))
                .build().toJson());
    }
}
