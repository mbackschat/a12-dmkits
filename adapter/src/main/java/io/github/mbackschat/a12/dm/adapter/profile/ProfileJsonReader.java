package io.github.mbackschat.a12.dm.adapter.profile;

import static io.github.mbackschat.a12.dm.adapter.profile.ProfileJsonValues.array;
import static io.github.mbackschat.a12.dm.adapter.profile.ProfileJsonValues.bool;
import static io.github.mbackschat.a12.dm.adapter.profile.ProfileJsonValues.exactFields;
import static io.github.mbackschat.a12.dm.adapter.profile.ProfileJsonValues.invalid;
import static io.github.mbackschat.a12.dm.adapter.profile.ProfileJsonValues.object;
import static io.github.mbackschat.a12.dm.adapter.profile.ProfileJsonValues.optionalBoolean;
import static io.github.mbackschat.a12.dm.adapter.profile.ProfileJsonValues.optionalObject;
import static io.github.mbackschat.a12.dm.adapter.profile.ProfileJsonValues.optionalText;
import static io.github.mbackschat.a12.dm.adapter.profile.ProfileJsonValues.required;
import static io.github.mbackschat.a12.dm.adapter.profile.ProfileJsonValues.text;
import static io.github.mbackschat.a12.dm.adapter.profile.ProfileJsonValues.validated;

import java.util.Objects;
import java.util.Set;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/** Strict current-format parser for bare profile artifacts and normal result envelopes. */
final class ProfileJsonReader {

    private static final Set<String> PROFILE_FIELDS =
            Set.of("structure", "rules", "computations", "wiring");
    private static final Set<String> ENVELOPE_FIELDS = Set.of(
            "target",
            "op",
            "outcome",
            "ok",
            "valid",
            "verification",
            "summary",
            "changed",
            "data",
            "diagnostics",
            "written",
            "output");
    private static final Set<String> REQUIRED_ENVELOPE_FIELDS =
            Set.of("target", "op", "outcome", "ok", "summary", "data", "diagnostics", "written");
    private static final Set<String> OUTCOMES =
            Set.of("applied", "preview", "refused", "rejected", "read", "staged", "error");
    private static final Set<String> VERIFICATIONS =
            Set.of("KERNEL_CONFIRMED", "STRUCTURAL_ONLY");

    private static final ObjectMapper JSON = new ObjectMapper(JsonFactory.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .build());

    private ProfileJsonReader() {}

    static ModelProfile read(String json) {
        Objects.requireNonNull(json, "json");
        final JsonNode root;
        try {
            root = JSON.readTree(json);
        } catch (JsonProcessingException e) {
            throw invalid("$", "malformed JSON: " + e.getOriginalMessage(), e);
        }
        if (root == null) {
            throw invalid("$", "profile JSON must not be empty");
        }
        return profile(unwrap(root));
    }

    private static ModelProfile profile(JsonNode source) {
        String path = "$";
        ObjectNode node = object(source, path);
        exactFields(node, path, PROFILE_FIELDS, PROFILE_FIELDS);
        var structure =
                ProfileJsonStructureReader.read(required(node, "structure", path), "$.structure");
        var rules =
                ProfileJsonProgramReader.read(required(node, "rules", path), "$.rules");
        var computations = ProfileJsonComputationReader.computations(
                required(node, "computations", path), "$.computations");
        var wiring = ProfileJsonComputationReader.wiring(
                required(node, "wiring", path), "$.wiring");
        return validated(path, () -> new ModelProfile(structure, rules, computations, wiring));
    }

    private static JsonNode unwrap(JsonNode root) {
        ObjectNode object = object(root, "$");
        if (PROFILE_FIELDS.stream().anyMatch(object::has)) {
            return object;
        }
        exactFields(object, "$", ENVELOPE_FIELDS, REQUIRED_ENVELOPE_FIELDS);
        text(object, "target", "$");
        text(object, "op", "$");
        String outcome = text(object, "outcome", "$");
        if (!OUTCOMES.contains(outcome)) {
            throw invalid("$.outcome", "unknown result outcome: " + outcome);
        }
        bool(object, "ok", "$");
        text(object, "summary", "$");
        array(required(object, "diagnostics", "$"), "$.diagnostics");
        bool(object, "written", "$");
        optionalBoolean(object, "valid", "$");
        optionalText(object, "output", "$");
        optionalObject(object, "changed", "$");
        if (object.has("verification")) {
            String verification = text(object, "verification", "$");
            if (!VERIFICATIONS.contains(verification)) {
                throw invalid(
                        "$.verification", "unknown verification posture: " + verification);
            }
        }
        return object(required(object, "data", "$"), "$.data");
    }
}
