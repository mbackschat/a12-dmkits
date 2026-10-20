package io.github.mbackschat.a12.dm.modelkit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.core.ModelIndex;

/**
 * modelkit — the closed Enumeration's <b>S-value</b> (DM-JSON {@code sValue} on {@code EnumerationType}):
 * the model-owned stored-token default an eligible empty INDEX field receives during full validation
 * (SPEC-2026-07-23-14; the kernel's {@code IEnumerationType.getsValue()}). Kernel is the oracle: a model
 * carrying the key loads + expands. Own domain (a shipment whose service level defaults to standard).
 */
class EnumSValueTest {

    private static DocumentModelDraft shipment() {
        return DocumentModelDraft.named("shipment").locale("en_US")
                .rootGroup("Shipment", s -> s
                        .field("ShipmentRef", FieldKind.STRING)
                        .group("Lines", lines -> lines
                                .repeatable(50).indexField("ServiceLevel")
                                .enumField("ServiceLevel", e -> e.value("STD").value("EXPRESS").sValue("STD"))
                                .field("OrderedQty", FieldKind.NUMBER)))
                .build();
    }

    @Test
    void anEnumIndexFieldWithAnSValueIsKernelValid() {
        ModelIndex index;
        try {
            index = ModelIndex.ofSelfContained(shipment().toJson());
        } catch (Exception e) {
            throw new RuntimeException("emitted DM-JSON failed checkConsistency:\n" + shipment().toJson(), e);
        }
        assertTrue(index.isRepeating("/Shipment/Lines"), "the index-keyed group should repeat");
    }

    @Test
    void emitsTheSValueKey() {
        assertTrue(shipment().toJson().contains("\"sValue\" : \"STD\""), shipment()::toJson);
    }

    @Test
    void withoutAnSValueTheKeyIsOmitted() {
        String json = DocumentModelDraft.named("shipment").locale("en_US")
                .rootGroup("Shipment", s -> s
                        .enumField("ServiceLevel", e -> e.value("STD").value("EXPRESS")))
                .build().toJson();
        assertTrue(!json.contains("sValue"), json);
    }

    @Test
    void theSValueRoundTripsThroughTheAuthoredState() {
        FieldTypeSpec spec = ModelDocument.read(shipment().toJson())
                .authoredState().fields().get("/Shipment/Lines/ServiceLevel").type();
        assertEquals("STD", ((FieldTypeSpec.EnumSpec) spec).sValue());
    }

    @Test
    void aBlankSValueIsRejectedFailFast() {
        assertThrows(IllegalArgumentException.class, () -> DocumentModelDraft.named("m").locale("en_US")
                .rootGroup("G", g -> g.enumField("E", e -> e.value("A").sValue(" ")))
                .build());
    }

    /**
     * A12 compares the S-value and the tokens after Java's {@code String.trim}, so a padded one matches and one that
     * trims to nothing is unset (KERNEL-FINDINGS KF569). Reading such a model must not refuse what A12 loads.
     */
    @Test
    void aModelWhoseSValueA12ReadsTrimmedIsReadable() {
        String declared = shipment().toJson();
        java.util.Map<String, String> readable = new java.util.LinkedHashMap<>();
        readable.put("padded S-value", declared.replace("\"sValue\" : \"STD\"", "\"sValue\" : \" STD\""));
        readable.put("trailing pad", declared.replace("\"sValue\" : \"STD\"", "\"sValue\" : \"STD \""));
        readable.put("blank S-value", declared.replace("\"sValue\" : \"STD\"", "\"sValue\" : \" \""));
        readable.put("tab S-value", declared.replace("\"sValue\" : \"STD\"", "\"sValue\" : \"\\t\""));
        readable.put("padded token", declared.replace("\"STD\"", "\" STD\"").replace("\"sValue\" : \" STD\"", "\"sValue\" : \"STD\""));
        readable.forEach((row, json) -> {
            assertTrue(!json.equals(declared), row + ": the fixture edit did not apply");
            FieldTypeSpec spec = ModelDocument.read(json).authoredState().fields().get("/Shipment/Lines/ServiceLevel").type();
            assertTrue(spec instanceof FieldTypeSpec.EnumSpec, row);
        });
    }

    /** The trim is all A12 forgives: another spelling, or a no-break space, stays outside the domain (KF569). */
    @Test
    void anSValueThatDiffersAfterTrimmingIsStillRefused() {
        for (String other : java.util.List.of("std", "S TD", "\u00A0")) {
            assertThrows(IllegalArgumentException.class, () -> DocumentModelDraft.named("m").locale("en_US")
                    .rootGroup("G", g -> g.enumField("E", e -> e.value("STD").sValue(other))).build(), other);
        }
    }

    @Test
    void anSValueOutsideTheDeclaredValuesIsRejectedFailFast() {
        // The kernel stages the token into evaluation without re-validating it, so a typo'd sValue would
        // silently leak an out-of-domain token into rules — reject at build, before the kernel ever sees it.
        assertThrows(IllegalArgumentException.class, () -> DocumentModelDraft.named("m").locale("en_US")
                .rootGroup("G", g -> g.enumField("E", e -> e.value("A").value("B").sValue("C")))
                .build());
    }
}
