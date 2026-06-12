package io.github.mbackschat.a12.dm.modelkit;

import java.io.IOException;
import java.io.StringReader;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import com.mgmtp.a12.kernel.md.model.api.services.IDocumentModelReferenceResolver;

import io.github.mbackschat.a12.dm.core.DmJson;
import io.github.mbackschat.a12.dm.core.ModelConsistencyException;
import io.github.mbackschat.a12.dm.core.ModelLoader;
import io.github.mbackschat.a12.dm.core.ReferenceResolvers;

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

    // --- structure edits (M8) — surgical mutations of the source tree; validate() is the kernel gate -

    /**
     * Add a field of {@code kind} to the group at {@code groupPath} (an "easy" kind — String/Number/Date/
     * Boolean/Confirm — whose type node needs no inline config). Mutates this document; chain
     * {@link #validate()} to confirm the result is kernel-valid. Fails fast if the group does not exist.
     */
    public ModelDocument addField(String groupPath, String name, FieldKind kind) {
        return addField(groupPath, name, DocumentModelDraft.bareSpec(kind));
    }

    /**
     * Add a field with full type config — a {@link FieldTypeSpec} (string bounds/pattern, number scale,
     * enum values + categories, date format/precision, a {@code TypeDefRef}, …). The richer peer of the
     * {@link FieldKind} overload (which is just {@code addField(g, n, bareSpec(kind))}).
     */
    public ModelDocument addField(String groupPath, String name, FieldTypeSpec type) {
        // reuse the builder's node emitter so the shape is identical to a built field
        ElementDraft.Field field = new ElementDraft.Field(
                name, type, Requiredness.OPTIONAL, Map.of(), List.of());
        containerElements(groupPath).add(DocumentModelDraft.fieldNode(field, groupPath, locales()));
        return this;
    }

    /** Re-type an existing field — replace its {@code fieldType} with {@code type} (full config). */
    public ModelDocument setFieldType(String fieldPath, FieldTypeSpec type) {
        JsonNode field = locate(fieldPath, false);
        if (field == null) {
            throw new IllegalArgumentException("no field at path: " + fieldPath);
        }
        DocumentModelDraft.writeFieldType(((ObjectNode) field.path("Field")).putObject("fieldType"),
                type, locales());
        return this;
    }

    /**
     * Set the per-locale display labels of a field or group. A label must cover <em>every</em> declared
     * locale (the kernel's rule, uniform with the builder) — fails fast otherwise.
     */
    public ModelDocument setLabel(String elementPath, Map<String, String> localeToText) {
        ObjectNode payload = elementPayload(elementPath);
        ArrayNode label = payload.putArray("label");
        for (String loc : locales()) {
            if (!localeToText.containsKey(loc)) {
                throw new IllegalArgumentException("label for " + elementPath
                        + " is missing the declared locale '" + loc + "'");
            }
            label.addObject().put("locale", loc).put("text", localeToText.get(loc));
        }
        return this;
    }

    /** Set a name/value annotation on a field or group (replacing any existing entry with the same name). */
    public ModelDocument setAnnotation(String elementPath, String name, String value) {
        ObjectNode element = element(elementPath);
        ArrayNode annotations = element.has("annotations")
                ? (ArrayNode) element.get("annotations")
                : element.putArray("annotations");
        for (JsonNode a : annotations) {
            if (name.equals(a.path("name").asText())) {
                ((ObjectNode) a).put("value", value);
                return this;
            }
        }
        annotations.addObject().put("name", name).put("value", value);
        return this;
    }

    /** Set (or clear, with {@link Requiredness#OPTIONAL}) the requiredness of an existing field. */
    public ModelDocument setRequiredness(String fieldPath, Requiredness requiredness) {
        JsonNode field = locate(fieldPath, false);
        if (field == null) {
            throw new IllegalArgumentException("no field at path: " + fieldPath);
        }
        ObjectNode body = (ObjectNode) field.path("Field");
        String mode = requiredness.mode();
        if (mode == null) {
            body.remove("requirednessConfig");
        } else {
            body.putObject("requirednessConfig").put("mode", mode);
        }
        return this;
    }

    /** The element node (field or group) at {@code path}; fails fast if absent. */
    private ObjectNode element(String path) {
        JsonNode[] found = new JsonNode[1];
        walk((p, node, isGroup) -> {
            if (found[0] == null && p.equals(path)) {
                found[0] = node;
            }
        });
        if (found[0] == null) {
            throw new IllegalArgumentException("no element at path: " + path);
        }
        return (ObjectNode) found[0];
    }

    /** The typed payload object ({@code "Field"} or {@code "Group"}) of the element at {@code path}. */
    private ObjectNode elementPayload(String path) {
        ObjectNode element = element(path);
        return (ObjectNode) element.path(element.path("type").asText());
    }

    /** Add a non-repeating group named {@code name} under {@code parentPath} ({@code ""} = a root group). */
    public ModelDocument addGroup(String parentPath, String name) {
        return addGroup(parentPath, name, 1);
    }

    /** Add a repeatable group ({@code maxRepetitions >= 2}) named {@code name} under {@code parentPath}. */
    public ModelDocument addRepeatableGroup(String parentPath, String name, int maxRepetitions) {
        if (maxRepetitions < 2) {
            throw new IllegalArgumentException("a repeatable group needs maxRepetitions >= 2, got " + maxRepetitions);
        }
        return addGroup(parentPath, name, maxRepetitions);
    }

    private ModelDocument addGroup(String parentPath, String name, int repeatability) {
        ArrayNode container = parentPath.isEmpty() ? rootGroups() : containerElements(parentPath);
        ElementDraft.Group group = new ElementDraft.Group(name, repeatability, null, List.of(), List.of());
        container.add(DocumentModelDraft.groupNode(group, parentPath, locales()));
        return this;
    }

    /** Remove the field at {@code fieldPath}. Fails fast if no field is there. */
    public ModelDocument removeField(String fieldPath) {
        removeElement(fieldPath, "Field");
        return this;
    }

    /** Remove the group at {@code groupPath} (and everything under it). Fails fast if no group is there. */
    public ModelDocument removeGroup(String groupPath) {
        removeElement(groupPath, "Group");
        return this;
    }

    /** Set the group at {@code groupPath} repeatable to {@code maxRepetitions} ({@code >= 2}). */
    public ModelDocument setRepeatable(String groupPath, int maxRepetitions) {
        if (maxRepetitions < 2) {
            throw new IllegalArgumentException("a repeatable group needs maxRepetitions >= 2, got " + maxRepetitions);
        }
        JsonNode group = locate(groupPath, true);
        if (group == null) {
            throw new IllegalArgumentException("no group at path: " + groupPath);
        }
        ((ObjectNode) group.path("Group")).put("repeatability", maxRepetitions);
        return this;
    }

    // --- doc config (content.modelConfig) -------------------------------------------------------

    /** Set the decimal separator (e.g. {@code "."} or {@code ","} — the comma-vs-point concern). */
    public ModelDocument setDecimalSeparator(String separator) {
        modelConfig().put("decimalSeparator", separator);
        return this;
    }

    /** Set the model's time zone (e.g. {@code "Europe/Berlin"}, {@code "UTC"}). */
    public ModelDocument setTimeZone(String timeZone) {
        modelConfig().put("timeZone", timeZone);
        return this;
    }

    /** Set the condition language code (e.g. {@code "en_US"}, {@code "de_DE"}). */
    public ModelDocument setConditionLanguage(String localeCode) {
        ObjectNode config = modelConfig();
        JsonNode cl = config.path("conditionLanguage");
        ObjectNode node = cl.isObject() ? (ObjectNode) cl : config.putObject("conditionLanguage");
        node.put("code", localeCode);
        return this;
    }

    private ObjectNode modelConfig() {
        JsonNode config = root.path("content").path("modelConfig");
        if (!config.isObject()) {
            throw new IllegalStateException("model has no content.modelConfig object");
        }
        return (ObjectNode) config;
    }

    // --- type definitions (content.typeDefinitions) ---------------------------------------------

    /** Declare a reusable type definition {@code id} over an easy {@code underlying} kind. */
    public ModelDocument addTypeDefinition(String id, FieldKind underlying) {
        return addTypeDefinition(id, DocumentModelDraft.bareSpec(underlying));
    }

    /** Declare a reusable type definition {@code id} over a fully-configured {@code type} (scale, pattern, enum, …). */
    public ModelDocument addTypeDefinition(String id, FieldTypeSpec type) {
        JsonNode tds = root.path("content").path("typeDefinitions");
        ArrayNode array = tds.isArray() ? (ArrayNode) tds
                : ((ObjectNode) root.path("content")).putArray("typeDefinitions");
        ObjectNode node = array.addObject();
        node.put("id", id);
        node.put("name", id);
        DocumentModelDraft.writeFieldType(node.putObject("fieldType"), type, locales());
        return this;
    }

    /** Add a field at {@code groupPath} typed by the type definition {@code typeDefId}. */
    public ModelDocument addTypedefField(String groupPath, String name, String typeDefId) {
        ElementDraft.Field field = new ElementDraft.Field(
                name, new FieldTypeSpec.TypeDefRef(typeDefId), Requiredness.OPTIONAL, Map.of(), List.of());
        containerElements(groupPath).add(DocumentModelDraft.fieldNode(field, groupPath, locales()));
        return this;
    }

    // --- includes (header.modelReferences + mount groups) ---------------------------------------

    /** Declare a model include: a header {@code modelReference} aliased {@code alias} → {@code reference}. */
    public ModelDocument addInclude(String alias, String reference) {
        JsonNode refs = root.path("header").path("modelReferences");
        ArrayNode array = refs.isArray() ? (ArrayNode) refs
                : ((ObjectNode) root.path("header")).putArray("modelReferences");
        array.addObject()
                .put("alias", alias)
                .put("modelType", "document")
                .put("purpose", "include")
                .put("reference", reference);
        return this;
    }

    /** Mount a declared include at {@code parentPath} as a group named {@code name} (a {@code modelAlias} group). */
    public ModelDocument mount(String parentPath, String name, String alias) {
        ArrayNode container = parentPath.isEmpty() ? rootGroups() : containerElements(parentPath);
        container.add(DocumentModelDraft.mountNode(new ElementDraft.Mount(name, alias, List.of()), parentPath));
        return this;
    }

    /** Remove the type definition {@code id}. Fails fast if absent; {@link #validate()} catches a field still using it. */
    public ModelDocument removeTypeDefinition(String id) {
        JsonNode tds = root.path("content").path("typeDefinitions");
        if (tds.isArray()) {
            ArrayNode array = (ArrayNode) tds;
            for (int i = 0; i < array.size(); i++) {
                if (id.equals(array.get(i).path("id").asText())) {
                    array.remove(i);
                    return this;
                }
            }
        }
        throw new IllegalArgumentException("no type definition with id: " + id);
    }

    /**
     * Remove the include aliased {@code alias} — its header {@code modelReference} <em>and</em> every mount
     * group ({@code modelAlias == alias}). Fails fast if neither is present; {@link #validate()} catches a
     * rule still referencing the mounted content.
     */
    public ModelDocument removeInclude(String alias) {
        boolean removed = false;
        JsonNode refs = root.path("header").path("modelReferences");
        if (refs.isArray()) {
            ArrayNode array = (ArrayNode) refs;
            for (int i = array.size() - 1; i >= 0; i--) {
                if (alias.equals(array.get(i).path("alias").asText())) {
                    array.remove(i);
                    removed = true;
                }
            }
        }
        boolean[] mountRemoved = {false};
        removeMounts(rootGroups(), alias, mountRemoved);
        if (!removed && !mountRemoved[0]) {
            throw new IllegalArgumentException("no include with alias: " + alias);
        }
        return this;
    }

    private void removeMounts(ArrayNode elements, String alias, boolean[] any) {
        for (int i = elements.size() - 1; i >= 0; i--) {
            JsonNode el = elements.get(i);
            if (!"Group".equals(el.path("type").asText())) {
                continue;
            }
            JsonNode body = el.path("Group");
            if (alias.equals(body.path("modelAlias").asText())) {
                elements.remove(i);
                any[0] = true;
            } else if (body.path("elements").isArray()) {
                removeMounts((ArrayNode) body.path("elements"), alias, any);
            }
        }
    }

    /**
     * The kernel gate (tier 2): re-bind the edited model through {@code checkConsistency} (self-contained).
     * Throws {@link ModelConsistencyException} if any edit made the model invalid — the full structural
     * constraint set, enforced by the engine rather than reimplemented here. Returns this on success.
     */
    public ModelDocument validate() {
        return validate(ReferenceResolvers.selfContained());
    }

    /** As {@link #validate()}, resolving includes / type-def imports via {@code resolver}. */
    public ModelDocument validate(IDocumentModelReferenceResolver resolver) {
        try {
            ModelLoader.load(new StringReader(toJson()), resolver);
        } catch (ModelConsistencyException e) {
            throw e; // checkConsistency tier — already the uniform "model invalid" type
        } catch (IOException | RuntimeException e) {
            // deserialize/expand tier (e.g. a duplicate element id surfaces as IllegalArgumentException):
            // funnel into the same type so a caller sees one "invalid edit" signal regardless of tier
            throw new ModelConsistencyException("the edited model is not kernel-valid", e);
        }
        return this;
    }

    private ArrayNode rootGroups() {
        JsonNode rg = root.path("content").path("modelRoot").path("rootGroups");
        if (!rg.isArray()) {
            throw new IllegalStateException("model has no content.modelRoot.rootGroups array");
        }
        return (ArrayNode) rg;
    }

    private ArrayNode containerElements(String groupPath) {
        ArrayNode elements = DmJson.groupElements(root, groupPath);
        if (elements == null) {
            throw new IllegalArgumentException("no group at path: " + groupPath);
        }
        return elements;
    }

    private void removeElement(String path, String type) {
        int cut = path.lastIndexOf('/');
        String parent = cut <= 0 ? "" : path.substring(0, cut);
        ArrayNode container = parent.isEmpty() ? rootGroups() : containerElements(parent);
        for (int i = 0; i < container.size(); i++) {
            JsonNode n = container.get(i);
            if (type.equals(n.path("type").asText()) && path.equals(n.path("id").asText())) {
                container.remove(i);
                return;
            }
        }
        throw new IllegalArgumentException("no " + type + " at path: " + path);
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
