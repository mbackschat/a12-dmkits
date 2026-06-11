package io.github.mbackschat.a12.dm.modelkit;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * An existing document model, read from DM-JSON into its <em>authored source tree</em> (M7) — the read
 * counterpart of {@link DocumentModelDraft} (build-from-scratch). It lets you enumerate the model's
 * structure (header, doc config, includes, type definitions, the group/field tree, name-path addressed)
 * and round-trip it: {@link #toJson()} re-serializes the retained Jackson tree, preserving includes and
 * any element this kit does not (yet) model — the substrate the M8 surgical structure edits build on.
 *
 * <p><b>Round-trip fidelity.</b> The round-trip rides the <em>generic</em> Jackson tree, never the
 * kernel's typed serializer (whose {@code NON_EMPTY}/{@code NON_DEFAULT} mapper omits empty/default
 * fields — KERNEL-FINDINGS), so read → write-back drops nothing and defaults nothing; it is identity
 * modulo pretty-printing. Reading is purely textual (no kernel, no include resolution), so a model with
 * unresolved includes still reads and enumerates.
 */
public final class ModelDocument {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final ObjectNode root;

    private ModelDocument(ObjectNode root) {
        this.root = root;
    }

    /** Read a model from DM-JSON, retaining its authored source tree. */
    public static ModelDocument read(String dmJson) {
        try {
            JsonNode node = MAPPER.readTree(dmJson);
            if (!(node instanceof ObjectNode obj)) {
                throw new IllegalArgumentException("not a DM-JSON object");
            }
            return new ModelDocument(obj);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("invalid DM-JSON", e);
        }
    }

    /** The model as DM-JSON text — the retained tree, pretty-printed (lossless modulo whitespace). */
    public String toJson() {
        try {
            return MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(root);
        } catch (JsonProcessingException e) {
            throw new UncheckedIOException(new IOException("failed to serialize model", e));
        }
    }

    // --- header ---------------------------------------------------------------------------------

    public String id() {
        return root.path("header").path("id").asText();
    }

    public String modelVersion() {
        return root.path("header").path("modelVersion").asText();
    }

    /** The declared locale codes, in document order. */
    public List<String> locales() {
        List<String> out = new ArrayList<>();
        for (JsonNode l : root.path("header").path("locales")) {
            out.add(l.path("code").asText());
        }
        return out;
    }

    /** The declared model includes (header {@code modelReferences}), in document order. */
    public List<Include> includes() {
        List<Include> out = new ArrayList<>();
        for (JsonNode r : root.path("header").path("modelReferences")) {
            out.add(new Include(r.path("alias").asText(), r.path("reference").asText()));
        }
        return out;
    }

    // --- content / config -----------------------------------------------------------------------

    /** The model-level document configuration (content {@code modelConfig}). */
    public DocConfig config() {
        JsonNode c = root.path("content").path("modelConfig");
        return new DocConfig(
                c.path("decimalSeparator").asText(),
                c.path("timeZone").asText(),
                c.path("conditionLanguage").path("code").asText(),
                c.path("fieldRefByShortNameAllowed").asBoolean());
    }

    /** The ids of the reusable type definitions declared on the model, in document order. */
    public List<String> typeDefinitionIds() {
        List<String> out = new ArrayList<>();
        for (JsonNode t : root.path("content").path("typeDefinitions")) {
            out.add(t.path("id").asText());
        }
        return out;
    }

    // --- the element tree (name-path addressed) -------------------------------------------------

    /** Every group's name-path (root groups + nested), in document order. */
    public List<String> groupPaths() {
        List<String> out = new ArrayList<>();
        walk((path, node, isGroup) -> {
            if (isGroup) {
                out.add(path);
            }
        });
        return out;
    }

    /** Every field's name-path, in document order. */
    public List<String> fieldPaths() {
        List<String> out = new ArrayList<>();
        walk((path, node, isGroup) -> {
            if (!isGroup) {
                out.add(path);
            }
        });
        return out;
    }

    /** The field-type discriminator at {@code fieldPath} (e.g. {@code StringType}, {@code TypeDefType}). */
    public Optional<String> fieldType(String fieldPath) {
        JsonNode field = locate(fieldPath, false);
        if (field == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(field.path("Field").path("fieldType").path("type").asText(null));
    }

    /** Whether the group at {@code groupPath} is repeatable (repeatability &gt; 1). */
    public boolean isRepeating(String groupPath) {
        JsonNode group = locate(groupPath, true);
        return group != null && group.path("Group").path("repeatability").asInt(1) > 1;
    }

    // --- tree walk ------------------------------------------------------------------------------

    private void walk(ElementVisitor visitor) {
        for (JsonNode rg : root.path("content").path("modelRoot").path("rootGroups")) {
            walk(rg, "", visitor);
        }
    }

    private void walk(JsonNode node, String prefix, ElementVisitor visitor) {
        String type = node.path("type").asText();
        String path = prefix + "/" + node.path("name").asText();
        if ("Group".equals(type)) {
            visitor.visit(path, node, true);
            for (JsonNode child : node.path("Group").path("elements")) {
                walk(child, path, visitor);
            }
        } else if ("Field".equals(type)) {
            visitor.visit(path, node, false);
        }
        // Other element kinds (Rule/Computation/…) are not enumerated here — they are rulekit's, and
        // structure editing (M8) addresses them by their own type; the tree still round-trips them.
    }

    private JsonNode locate(String namePath, boolean group) {
        JsonNode[] found = new JsonNode[1];
        walk((path, node, isGroup) -> {
            if (found[0] == null && isGroup == group && path.equals(namePath)) {
                found[0] = node;
            }
        });
        return found[0];
    }

    @FunctionalInterface
    private interface ElementVisitor {
        void visit(String namePath, JsonNode node, boolean isGroup);
    }

    /** A declared model include (an entry of the header's {@code modelReferences}). */
    public record Include(String alias, String reference) {}

    /** The model-level document configuration (content {@code modelConfig}). */
    public record DocConfig(String decimalSeparator, String timeZone, String conditionLanguage,
            boolean fieldRefByShortNameAllowed) {}
}
