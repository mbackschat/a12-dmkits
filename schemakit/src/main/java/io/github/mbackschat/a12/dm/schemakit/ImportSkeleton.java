package io.github.mbackschat.a12.dm.schemakit;

import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft;

/**
 * What every import starts from before any structure or carriage: the model's identity and the header and content
 * defaults A12's draft fills in. Export reads the same skeleton to leave out whatever import would rebuild anyway,
 * so a foreign schema's import → export gains no {@code title} or {@code x-a12-model} (SCHEMAKIT-SPEC §4d).
 */
final class ImportSkeleton {

    /** The model id when the schema names none, or names one that is not a safe id. */
    static final String DEFAULT_MODEL_ID = "ImportedModel";
    private static final String LOCALE = "en_US";

    private ImportSkeleton() {}

    static DocumentModelDraft.Builder draft(String modelId) {
        return DocumentModelDraft.named(modelId).locale(LOCALE);
    }

    /**
     * The model an import of {@code modelId} builds before carriage restores anything, as DM-JSON. It is per id
     * because {@code modelInfo} names the model.
     */
    static String json(String modelId) {
        return draft(modelId).rootGroup("Root", g -> { }).build().toJson();
    }

    /** A JSON Schema title is free text; fall back to a stable default when it is absent or not a safe id. */
    static String identifier(String title) {
        if (title == null || title.isBlank()) {
            return DEFAULT_MODEL_ID;
        }
        String trimmed = title.trim();
        return trimmed.matches("[A-Za-z0-9_.-]+") ? trimmed : DEFAULT_MODEL_ID;
    }
}
