package io.github.mbackschat.a12.dm.modelkit;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * F3 — a standalone requiredness change must not clobber the rest of {@code requirednessConfig}. The custom
 * required-error message ({@code requirednessConfig.errorMessage}, G23) lives <em>inside</em> the config object,
 * so {@link ModelDocument#setRequiredness} replacing the whole object (rather than mutating the {@code mode} in
 * place) silently wiped an already-authored message. Found 2026-07-10 (six-component review): unguarded — no
 * existing test pre-seeded the config before mutating.
 */
class RequirednessConfigTest {

    private static ModelDocument policyWithRequiredMessage() {
        String json = DocumentModelDraft.named("policy").locale("en_US")
                .rootGroup("Policy", p -> p.field("PolicyNumber", FieldKind.STRING))
                .build().toJson();
        ModelDocument doc = ModelDocument.read(json);
        doc.setRequiredness("/Policy/PolicyNumber", Requiredness.REQUIRED);
        doc.setRequiredErrorMessages("/Policy/PolicyNumber", Map.of("en_US", "A policy number is mandatory."));
        return doc;
    }

    @Test
    void changingTheRequirednessModeKeepsTheCustomErrorMessage() {
        ModelDocument doc = policyWithRequiredMessage();
        // a standalone mode change (REQUIRED → REQUIRED_IF_PARENT_PRESENT) must preserve the message inside the config
        doc.setRequiredness("/Policy/PolicyNumber", Requiredness.REQUIRED_IF_PARENT_PRESENT);

        String json = doc.toJson();
        assertTrue(json.contains("relativeToParent"), () -> "the new mode is written: " + json);
        assertTrue(json.contains("A policy number is mandatory."),
                () -> "the custom required-error message survives a standalone requiredness change: " + json);
    }

    @Test
    void clearingRequirednessDropsTheWholeConfigIncludingItsMessage() {
        // the OPTIONAL case is correct to drop the config entirely — an optional field has no required-error message.
        ModelDocument doc = policyWithRequiredMessage();
        doc.setRequiredness("/Policy/PolicyNumber", Requiredness.OPTIONAL);

        String json = doc.toJson();
        assertTrue(!json.contains("requirednessConfig"), () -> "clearing requiredness removes the config: " + json);
    }
}
