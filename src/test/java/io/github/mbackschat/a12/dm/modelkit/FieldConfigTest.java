package io.github.mbackschat.a12.dm.modelkit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.StringReader;
import java.util.List;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.core.ModelIndex;
import io.github.mbackschat.a12.dm.core.ModelLoader;
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
            return ModelIndex.of(ModelLoader.loadSelfContained(new StringReader(draft.toJson())));
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
