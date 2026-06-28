package io.github.mbackschat.a12.dm.schemakit.spike;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft;
import io.github.mbackschat.a12.dm.modelkit.FieldKind;
import io.github.mbackschat.a12.dm.modelkit.Requiredness;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * JSON Schema → Document Model, the spike's import direction (SCHEMAKIT-SPEC §6, shape 1: a flat top-level
 * object). It authors through the typed surface (the dogfood rule) — modelkit's {@link DocumentModelDraft}
 * — so the emitted DM-JSON is kernel-valid by construction, and it touches the kernel only transitively
 * via the public facade. The richer shapes (nested/array groups, {@code $ref}, rules) land in later
 * increments; an unsupported property is reported, never silently dropped.
 */
public final class JsonSchemaImporter {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String DEFAULT_MODEL_ID = "ImportedModel";
    private static final String ROOT_GROUP = "Root";
    private static final String LOCALE = "en_US";

    public record ImportResult(String dmJson, TranscodeReport report) {}

    public ImportResult importSchema(String jsonSchema) {
        JsonNode root = parse(jsonSchema);
        String type = root.path("type").asText("");
        if (!"object".equals(type)) {
            throw new IllegalArgumentException(
                    "shape 1 supports a top-level object schema only; got type=" + (type.isEmpty() ? "<none>" : type));
        }
        String modelId = identifier(root.path("title").asText(null));
        Set<String> required = requiredNames(root);
        JsonNode properties = root.path("properties");

        List<TranscodeReport.Disposition> dispositions = new ArrayList<>();
        List<String> diagnostics = new ArrayList<>();

        DocumentModelDraft draft = DocumentModelDraft.named(modelId)
                .locale(LOCALE)
                .rootGroup(ROOT_GROUP, g -> {
                    for (Iterator<Map.Entry<String, JsonNode>> it = properties.fields(); it.hasNext(); ) {
                        Map.Entry<String, JsonNode> e = it.next();
                        Requiredness req = required.contains(e.getKey())
                                ? Requiredness.REQUIRED : Requiredness.OPTIONAL;
                        addField(g, e.getKey(), e.getValue(), req, dispositions, diagnostics);
                    }
                })
                .build();

        return new ImportResult(draft.toJson(), new TranscodeReport(dispositions, diagnostics));
    }

    private static void addField(DocumentModelDraft.GroupBuilder g, String name, JsonNode schema,
                                 Requiredness req, List<TranscodeReport.Disposition> disp, List<String> diag) {
        String type = schema.path("type").asText("");
        boolean isRequired = req == Requiredness.REQUIRED;
        String source = "properties/" + name;
        switch (type) {
            case "string" -> {
                g.field(name, FieldKind.STRING, req);
                structure(disp, source, "STRING");
            }
            case "boolean" -> {
                g.field(name, FieldKind.BOOLEAN, req);
                structure(disp, source, "BOOLEAN");
            }
            case "integer" -> {
                g.numberField(name, b -> {
                    b.fractionDigits(0);
                    if (isRequired) {
                        b.required();
                    }
                });
                structure(disp, source, "NUMBER(integer, scale 0)");
            }
            // JSON `number` has NO faithful a12 target in shape 1: an a12 number always carries a bounded
            // max scale (modelkit defaults it to 0 = integer), so an unbounded decimal cannot be expressed
            // without a scale policy. Mapping it to scale 0 would silently turn a decimal into an integer —
            // so it is REPORTED, not mismapped. Scale inference (multipleOf → scale, proposal §5) lands in
            // the field-type-matcher increment.
            case "number" -> {
                disp.add(new TranscodeReport.Disposition(source, "<skipped>", TranscodeReport.Tier.REFUSED));
                diag.add("property '" + name + "' is type 'number' (unbounded decimal) — no faithful a12 target "
                        + "in shape 1: an a12 number is always scale-bounded (default 0 = integer). Provide "
                        + "'multipleOf' for a scale, or await the field-type-matcher increment (proposal §5).");
            }
            default -> diag.add("unsupported property type '" + (type.isEmpty() ? "<none>" : type) + "' on '"
                    + name + "' — shape 1 maps string/integer/boolean only");
        }
    }

    private static void structure(List<TranscodeReport.Disposition> disp, String source, String target) {
        disp.add(new TranscodeReport.Disposition(source, target, TranscodeReport.Tier.STRUCTURE));
    }

    private static Set<String> requiredNames(JsonNode root) {
        JsonNode req = root.path("required");
        if (!req.isArray()) {
            return Set.of();
        }
        Set<String> names = new LinkedHashSet<>();
        req.forEach(n -> names.add(n.asText()));
        return names;
    }

    /** A JSON Schema title is free text; fall back to a stable default when it is absent or not a safe id. */
    private static String identifier(String title) {
        if (title == null || title.isBlank()) {
            return DEFAULT_MODEL_ID;
        }
        String trimmed = title.trim();
        return trimmed.matches("[A-Za-z][A-Za-z0-9_]*") ? trimmed : DEFAULT_MODEL_ID;
    }

    private static JsonNode parse(String jsonSchema) {
        try {
            return JSON.readTree(jsonSchema);
        } catch (Exception e) {
            throw new IllegalArgumentException("not valid JSON: " + e.getMessage(), e);
        }
    }
}
