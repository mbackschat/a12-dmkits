package io.github.mbackschat.a12.dm.modelkit;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.StringReader;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.core.ModelLoader;

/**
 * modelkit M4 — field/group metadata: per-locale <b>labels</b> and name/value <b>annotations</b>,
 * available uniformly on every field builder (shared base) and on groups. Kernel is the oracle: the
 * annotated/labelled model passes {@code checkConsistency}, and the emitted JSON carries the slots.
 * Own domain (a contact), not adapted from any fixture.
 */
class MetadataTest {

    private static DocumentModelDraft contact() {
        return DocumentModelDraft.named("contact").locale("en_US").locale("de_DE")
                .rootGroup("Contact", c -> c
                        .annotation("ui_section", "primary")
                        .stringField("FullName", s -> s.maxLength(80)
                                .label("en_US", "Full name").label("de_DE", "Vollständiger Name")
                                .annotation("search", "fuzzy")
                                .required())
                        .numberField("Age", n -> n
                                .label("en_US", "Age").label("de_DE", "Alter")))
                .build();
    }

    private static void load(String json) {
        try {
            ModelLoader.loadSelfContained(json);
        } catch (Exception e) {
            throw new RuntimeException("emitted DM-JSON failed checkConsistency:\n" + json, e);
        }
    }

    @Test
    void labelsAndAnnotationsAreKernelValidAndEmitted() {
        String json = contact().toJson();
        load(json);
        // per-locale field labels
        assertTrue(json.contains("\"text\" : \"Vollständiger Name\""), json);
        assertTrue(json.contains("\"text\" : \"Age\""), json);
        // field annotation (element-level name/value)
        assertTrue(json.contains("\"name\" : \"search\""), json);
        assertTrue(json.contains("\"value\" : \"fuzzy\""), json);
        // group annotation
        assertTrue(json.contains("\"name\" : \"ui_section\""), json);
    }
}
