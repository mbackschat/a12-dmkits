package io.github.mbackschat.a12.dm.modelkit;

import java.io.IOException;
import java.io.StringReader;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;

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

    /** The {@code modelReferences.purpose} value for a type-def import (kernel {@code PURPOSE_4_ONLY_TYPE_DEFS}). */
    private static final String IMPORT_PURPOSE = "typeDefinitions";
    /** A direct, authored mount (the classic include). Absent/blank purpose is treated as this. */
    private static final String INCLUDE_PURPOSE = "include";
    /** A reference the kernel adds for an include pulled in <em>transitively</em> (DG6/A12K-3234). */
    private static final String TRANSITIVE_INCLUDE_PURPOSE = "transitiveInclude";

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

    /** The {@code header.modelType} discriminator ({@code "document"} for document/type-def-import models). */
    public String modelType() {
        return root.path("header").path("modelType").asText();
    }

    /** The declared locale codes, in document order. */
    public List<String> locales() {
        List<String> out = new ArrayList<>();
        for (JsonNode l : root.path("header").path("locales")) {
            out.add(l.path("code").asText());
        }
        return out;
    }

    /**
     * Register a display locale — append {@code code} to the {@code header.locales} registry (idempotent: a
     * no-op if already declared). The registry is explicit (adding per-element labels/messages does NOT
     * derive it), and the kernel then requires every rule/computation message to cover it, so a
     * {@code validate} after this rejects until each carries the locale's message (G16).
     */
    public ModelDocument addLocale(String code) {
        ObjectNode header = (ObjectNode) root.path("header");
        ArrayNode locales = header.path("locales").isArray()
                ? (ArrayNode) header.get("locales")
                : header.putArray("locales");
        for (JsonNode l : locales) {
            if (code.equals(l.path("code").asText())) {
                return this;   // already declared
            }
        }
        locales.addObject().put("code", code);
        return this;
    }

    /** The directly-declared model includes (header {@code modelReferences} with {@code purpose: "include"},
     *  or a blank/absent purpose — the classic default), in document order. <b>Transitive</b> includes the
     *  kernel adds are excluded here — read those via {@link #transitiveIncludes()}. */
    public List<Include> includes() {
        List<Include> out = new ArrayList<>();
        for (JsonNode r : root.path("header").path("modelReferences")) {
            String purpose = r.path("purpose").asText();
            if (INCLUDE_PURPOSE.equals(purpose) || purpose.isEmpty()) {
                out.add(new Include(r.path("alias").asText(), r.path("reference").asText()));
            }
        }
        return out;
    }

    /** The <b>transitive</b> includes (header {@code modelReferences} with {@code purpose: "transitiveInclude"})
     *  the kernel records for includes pulled in via another include — distinct from the author's direct
     *  {@link #includes()} (DG6/A12K-3234). */
    public List<Include> transitiveIncludes() {
        List<Include> out = new ArrayList<>();
        for (JsonNode r : root.path("header").path("modelReferences")) {
            if (TRANSITIVE_INCLUDE_PURPOSE.equals(r.path("purpose").asText())) {
                out.add(new Include(r.path("alias").asText(), r.path("reference").asText()));
            }
        }
        return out;
    }

    /**
     * The referenced models this one <b>imports type definitions from</b> (header {@code modelReferences}
     * with {@code purpose: "typeDefinitions"}), in document order — their type-defs merge into this model's
     * type-def namespace by id at expand. Distinct from {@link #includes()} (mounted structure).
     */
    public List<String> typeDefImports() {
        List<String> out = new ArrayList<>();
        for (JsonNode r : root.path("header").path("modelReferences")) {
            if (IMPORT_PURPOSE.equals(r.path("purpose").asText())) {
                out.add(r.path("reference").asText());
            }
        }
        return out;
    }

    // --- content / config -----------------------------------------------------------------------

    /** The model-level document configuration (content {@code modelConfig}). */
    public DocConfig config() {
        JsonNode c = root.path("content").path("modelConfig");
        List<String> supported = new ArrayList<>();
        for (JsonNode ch : c.path("supportedCharacters")) {
            supported.add(ch.asText());
        }
        return new DocConfig(
                c.path("decimalSeparator").asText(),
                c.path("timeZone").asText(),
                c.path("conditionLanguage").path("code").asText(),
                c.path("fieldRefByShortNameAllowed").asBoolean(),
                List.copyOf(supported));
    }

    /** The number of validation rules declared anywhere in the element tree (kernel-free count). */
    public int ruleCount() {
        return countByType("Rule");
    }

    /** The number of computations declared anywhere in the element tree (kernel-free count). */
    public int computationCount() {
        return countByType("Computation");
    }

    private int countByType(String type) {
        int[] count = {0};
        countByType(root.path("content").path("modelRoot").path("rootGroups"), type, count);
        return count[0];
    }

    private static void countByType(JsonNode elements, String type, int[] count) {
        for (JsonNode el : elements) {
            if (type.equals(el.path("type").asText())) {
                count[0]++;
            }
            if ("Group".equals(el.path("type").asText())) {
                countByType(el.path("Group").path("elements"), type, count);
            }
        }
    }

    /** The ids of the reusable type definitions declared on the model, in document order. */
    public List<String> typeDefinitionIds() {
        List<String> out = new ArrayList<>();
        for (JsonNode t : root.path("content").path("typeDefinitions")) {
            out.add(t.path("id").asText());
        }
        return out;
    }

    /** The field-type discriminator of the type definition {@code id} (e.g. {@code NumberType}), if declared. */
    public Optional<String> typeDefinitionType(String id) {
        for (JsonNode t : root.path("content").path("typeDefinitions")) {
            if (id.equals(t.path("id").asText())) {
                return Optional.ofNullable(t.path("fieldType").path("type").asText(null));
            }
        }
        return Optional.empty();
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

    /**
     * The field's requiredness at {@code fieldPath} — the friendly form of the stored
     * {@code Field.requirednessConfig.mode} (absent = {@link Requiredness#OPTIONAL}). Empty if no such field.
     */
    public Optional<Requiredness> requiredness(String fieldPath) {
        JsonNode field = locate(fieldPath, false);
        if (field == null) {
            return Optional.empty();
        }
        return Optional.of(Requiredness.fromMode(
                field.path("Field").path("requirednessConfig").path("mode").asText(null)));
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
        return addField(groupPath, new ElementDraft.Field(name, type, Requiredness.OPTIONAL, Map.of(), List.of()));
    }

    /**
     * Add a fully-configured {@link ElementDraft.Field} — its type, requiredness, per-locale labels, element
     * annotations, and the §3.5 metadata (global/transient flags, descriptions, helper text) — emitted via
     * the same node builder a freshly-authored field uses. The rich peer of the {@code (name, type)} overload;
     * the CLI {@code field add <spec.json>} path builds the full field, then chains {@link #validate}.
     */
    public ModelDocument addField(String groupPath, ElementDraft.Field field) {
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

    /** Set a name/value annotation on any element — field, group, or rule (replacing any same-named entry). */
    public ModelDocument setAnnotation(String elementPath, String name, String value) {
        ObjectNode element = metaElement(elementPath);
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

    /** Remove the annotation named {@code name} from the element at {@code elementPath} (no-op if absent). */
    public ModelDocument removeAnnotation(String elementPath, String name) {
        filterArray(metaElement(elementPath), "annotations", "name", name);
        return this;
    }

    /**
     * Upsert one per-locale {@code internalDescription} entry on the element at {@code elementPath} — a
     * field, group, or rule (the kernel's {@code IDescribed}, authoring/maintenance text). Replaces the
     * text for an existing locale.
     */
    public ModelDocument setInternalDescription(String elementPath, String locale, String text) {
        upsertLocalized(metaElement(elementPath), "internalDescription", locale, text);
        return this;
    }

    /** Upsert one per-locale {@code externalDescription} entry on the element at {@code elementPath} (end-user text). */
    public ModelDocument setExternalDescription(String elementPath, String locale, String text) {
        upsertLocalized(metaElement(elementPath), "externalDescription", locale, text);
        return this;
    }

    /** Remove the {@code internalDescription} entry for {@code locale} from the element at {@code elementPath}. */
    public ModelDocument removeInternalDescription(String elementPath, String locale) {
        filterArray(metaElement(elementPath), "internalDescription", "locale", locale);
        return this;
    }

    /** Remove the {@code externalDescription} entry for {@code locale} from the element at {@code elementPath}. */
    public ModelDocument removeExternalDescription(String elementPath, String locale) {
        filterArray(metaElement(elementPath), "externalDescription", "locale", locale);
        return this;
    }

    /** Upsert a {@code [{locale, text}]} entry under {@code key} on {@code element}. */
    private static void upsertLocalized(ObjectNode element, String key, String locale, String text) {
        ArrayNode array = element.has(key) ? (ArrayNode) element.get(key) : element.putArray(key);
        for (JsonNode e : array) {
            if (locale.equals(e.path("locale").asText())) {
                ((ObjectNode) e).put("text", text);
                return;
            }
        }
        array.addObject().put("locale", locale).put("text", text);
    }

    /** Drop every entry of {@code element[key]} whose {@code matchField} equals {@code matchValue}; remove the array if it empties. */
    private static void filterArray(ObjectNode element, String key, String matchField, String matchValue) {
        JsonNode array = element.path(key);
        if (!array.isArray()) {
            return;
        }
        ArrayNode kept = MAPPER.createArrayNode();
        for (JsonNode e : array) {
            if (!matchValue.equals(e.path(matchField).asText())) {
                kept.add(e);
            }
        }
        if (kept.isEmpty()) {
            element.remove(key);
        } else {
            element.set(key, kept);
        }
    }

    /** Upsert one model-level header <b>label</b> locale (the model-level peer of {@link #setLabel}, §3.5/§2). */
    public ModelDocument setModelLabel(String locale, String text) {
        ArrayNode labels = headerArray("labels");
        for (JsonNode l : labels) {
            if (locale.equals(l.path("locale").asText())) {
                ((ObjectNode) l).put("text", text);
                return this;
            }
        }
        labels.addObject().put("locale", locale).put("text", text);
        return this;
    }

    /** Upsert a model-level header <b>annotation</b> (replacing any existing entry with the same name). */
    public ModelDocument setModelAnnotation(String name, String value) {
        ArrayNode annotations = headerArray("annotations");
        for (JsonNode a : annotations) {
            if (name.equals(a.path("name").asText())) {
                ((ObjectNode) a).put("value", value);
                return this;
            }
        }
        annotations.addObject().put("name", name).put("value", value);
        return this;
    }

    /** Set the model-level {@code comment} ({@code modelInfo.comment} — a free-text model description/note). */
    public ModelDocument setModelComment(String text) {
        modelInfo().put("comment", text);
        return this;
    }

    /** Remove the model-level {@code comment}. */
    public ModelDocument clearModelComment() {
        modelInfo().remove("comment");
        return this;
    }

    /** The model-level {@code comment} ({@code modelInfo.comment}), if set. */
    public Optional<String> modelComment() {
        JsonNode comment = root.path("content").path("modelInfo").path("comment");
        return comment.isTextual() ? Optional.of(comment.asText()) : Optional.empty();
    }

    /** The model-level header <b>annotations</b> as a name→value map (the read peer of {@link #setModelAnnotation}). */
    public Map<String, String> modelAnnotations() {
        Map<String, String> out = new java.util.LinkedHashMap<>();
        for (JsonNode a : root.path("header").path("annotations")) {
            out.put(a.path("name").asText(), a.path("value").asText());
        }
        return out;
    }

    /** The model-level header <b>labels</b> as a locale→text map (the read peer of {@link #setModelLabel}). */
    public Map<String, String> modelLabels() {
        Map<String, String> out = new java.util.LinkedHashMap<>();
        for (JsonNode l : root.path("header").path("labels")) {
            out.put(l.path("locale").asText(), l.path("text").asText());
        }
        return out;
    }

    /** The header's {@code key} array, created if absent. */
    private ArrayNode headerArray(String key) {
        ObjectNode header = (ObjectNode) root.path("header");
        return header.has(key) ? (ArrayNode) header.get(key) : header.putArray(key);
    }

    /** The {@code Field} body object at {@code fieldPath}; fails fast if there is no field there. */
    private ObjectNode fieldBody(String fieldPath) {
        JsonNode field = locate(fieldPath, false);
        if (field == null) {
            throw new IllegalArgumentException("no field at path: " + fieldPath);
        }
        return (ObjectNode) field.path("Field");
    }

    /** Set (or clear, with {@link Requiredness#OPTIONAL}) the requiredness of an existing field. */
    public ModelDocument setRequiredness(String fieldPath, Requiredness requiredness) {
        ObjectNode body = fieldBody(fieldPath);
        String mode = requiredness.mode();
        if (mode == null) {
            body.remove("requirednessConfig");
        } else {
            body.putObject("requirednessConfig").put("mode", mode);
        }
        return this;
    }

    /** Set a field's per-locale helper text (replacing any existing). Unlike a label it need not cover every locale. */
    public ModelDocument setHelperText(String fieldPath, Map<String, String> localeToText) {
        ObjectNode body = fieldBody(fieldPath);
        ArrayNode helper = body.putArray("helperText");
        localeToText.forEach((loc, text) -> helper.addObject().put("locale", loc).put("text", text));
        return this;
    }

    /** Set a field's {@code global} flag (shared across repetitions); {@code false} removes it (the kernel default). */
    public ModelDocument setGlobal(String fieldPath, boolean global) {
        return setFieldFlag(fieldPath, "global", global);
    }

    /** Set a field's {@code transient} flag (not persisted); {@code false} removes it (the kernel default). */
    public ModelDocument setTransient(String fieldPath, boolean isTransient) {
        return setFieldFlag(fieldPath, "transient", isTransient);
    }

    private ModelDocument setFieldFlag(String fieldPath, String flag, boolean on) {
        ObjectNode body = fieldBody(fieldPath);
        if (on) {
            body.put(flag, true);
        } else {
            body.remove(flag);
        }
        return this;
    }

    /**
     * Apply a {@link FieldModification} to the field at {@code fieldPath} — each present aspect is set, each
     * absent one left as-is. This is the <b>single</b> place a partial field edit is realized, shared by the
     * CLI's standalone {@code field modify} and the {@code apply}/{@code batch} field-modify op, so neither
     * can silently drop an aspect the spec carried (the false-{@code applied} trap a type-only modify caused).
     */
    public ModelDocument applyModification(String fieldPath, FieldModification mod) {
        mod.type().ifPresent(t -> setFieldType(fieldPath, t));
        mod.requiredness().ifPresent(r -> setRequiredness(fieldPath, r));
        mod.label().ifPresent(l -> setLabel(fieldPath, l));
        mod.helperText().ifPresent(h -> setHelperText(fieldPath, h));
        mod.internalDescription().ifPresent(m -> m.forEach((loc, t) -> setInternalDescription(fieldPath, loc, t)));
        mod.externalDescription().ifPresent(m -> m.forEach((loc, t) -> setExternalDescription(fieldPath, loc, t)));
        mod.annotations().ifPresent(list -> list.forEach(a -> setAnnotation(fieldPath, a.name(), a.value())));
        mod.global().ifPresent(g -> setGlobal(fieldPath, g));
        mod.isTransient().ifPresent(t -> setTransient(fieldPath, t));
        return this;
    }

    /** The element node (field or group) at {@code path}; fails fast if absent. */
    private Optional<ObjectNode> findElement(String path) {
        JsonNode[] found = new JsonNode[1];
        walk((p, node, isGroup) -> {
            if (found[0] == null && p.equals(path)) {
                found[0] = node;
            }
        });
        return Optional.ofNullable((ObjectNode) found[0]);
    }

    private ObjectNode element(String path) {
        return findElement(path).orElseThrow(() -> new IllegalArgumentException("no element at path: " + path));
    }

    /**
     * Read the per-locale display labels of the element at {@code elementPath}. Present (possibly an empty
     * map) only for a <b>field or group</b> — the only describables that carry a display label; empty
     * {@code Optional} for a rule/computation/type-def (they have no label slot). The read peer of
     * {@link #setLabel}.
     */
    public Optional<Map<String, String>> labels(String elementPath) {
        return findElement(elementPath)
                .map(el -> localizedOf((ObjectNode) el.path(el.path("type").asText()), "label"));
    }

    /** The typed payload object ({@code "Field"} or {@code "Group"}) of the element at {@code path}. */
    private ObjectNode elementPayload(String path) {
        ObjectNode element = element(path);
        return (ObjectNode) element.path(element.path("type").asText());
    }

    /**
     * Locate any describable node by its name-path or type-def id: a field, group, or rule (by name-path,
     * broader than {@link #element}, which {@code walk} limits to fields/groups), or a <b>type definition</b>
     * (by id, since type-defs live in {@code content.typeDefinitions}, not the element tree). All carry the
     * same element-level {@code internalDescription}/{@code externalDescription}/{@code annotations} shape, so
     * one metadata API ({@link #setInternalDescription} / {@link #setAnnotation} / …) covers them all.
     */
    private ObjectNode metaElement(String pathOrTypeDefId) {
        ObjectNode found = findByPath(root.path("content").path("modelRoot").path("rootGroups"), "", pathOrTypeDefId);
        if (found != null) {
            return found;
        }
        for (JsonNode td : root.path("content").path("typeDefinitions")) {
            if (pathOrTypeDefId.equals(td.path("id").asText())) {
                return (ObjectNode) td;
            }
        }
        throw new IllegalArgumentException("no element or type definition: " + pathOrTypeDefId);
    }

    /**
     * Whether a describable node exists at {@code pathOrTypeDefId} — a field/group/rule by name-path, or a
     * type definition by id. The pre-check for the metadata edit verbs (so a bogus reference refuses cleanly).
     */
    public boolean describableExists(String pathOrTypeDefId) {
        if (findByPath(root.path("content").path("modelRoot").path("rootGroups"), "", pathOrTypeDefId) != null) {
            return true;
        }
        for (JsonNode td : root.path("content").path("typeDefinitions")) {
            if (pathOrTypeDefId.equals(td.path("id").asText())) {
                return true;
            }
        }
        return false;
    }

    /** Read an element's per-locale {@code internalDescription}/{@code externalDescription} (the metadata read verbs). */
    public Map<String, String> internalDescription(String pathOrTypeDefId) {
        return localizedOf(metaElement(pathOrTypeDefId), "internalDescription");
    }

    /** Read an element's external description, by locale. */
    public Map<String, String> externalDescription(String pathOrTypeDefId) {
        return localizedOf(metaElement(pathOrTypeDefId), "externalDescription");
    }

    /** Read an element's annotations as a name→value map. */
    public Map<String, String> annotations(String pathOrTypeDefId) {
        Map<String, String> out = new java.util.LinkedHashMap<>();
        for (JsonNode a : metaElement(pathOrTypeDefId).path("annotations")) {
            out.put(a.path("name").asText(), a.path("value").asText());
        }
        return out;
    }

    private static Map<String, String> localizedOf(ObjectNode element, String key) {
        Map<String, String> out = new java.util.LinkedHashMap<>();
        for (JsonNode e : element.path(key)) {
            out.put(e.path("locale").asText(), e.path("text").asText());
        }
        return out;
    }

    private static ObjectNode findByPath(JsonNode container, String prefix, String target) {
        for (JsonNode node : container) {
            String path = prefix + "/" + node.path("name").asText();
            if (path.equals(target)) {
                return (ObjectNode) node;
            }
            if ("Group".equals(node.path("type").asText())) {
                ObjectNode inner = findByPath(node.path("Group").path("elements"), path, target);
                if (inner != null) {
                    return inner;
                }
            }
        }
        return null;
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

    /**
     * Add a <b>Multi-Select</b> group template (FEATURE-GAPS §3.8) named {@code name} under {@code parentPath}
     * — a repeatable group with one enum/string selection field; {@code body} supplies the field + maxRows
     * via the same {@link DocumentModelDraft.MultiSelectBuilder} the from-scratch authoring path uses. The
     * kernel's {@code MultiSelectGroupRule} validates it on the next {@code validate(...)}.
     */
    public ModelDocument addMultiSelectGroup(String parentPath, String name,
            Consumer<DocumentModelDraft.MultiSelectBuilder> body) {
        DocumentModelDraft.MultiSelectBuilder b = new DocumentModelDraft.MultiSelectBuilder();
        body.accept(b);
        ArrayNode container = parentPath.isEmpty() ? rootGroups() : containerElements(parentPath);
        container.add(DocumentModelDraft.groupNode(b.toGroup(name), parentPath, locales()));
        return this;
    }

    /**
     * Add an <b>Attachment</b> group template (FEATURE-GAPS §3.8) named {@code name} under {@code parentPath}
     * — the kernel's canonical file-attachment composition (8 fields + 4 rules + {@code content} config). The
     * mandated rules are emitted in the model's condition language; the model must be {@code en_US}/{@code
     * de_DE} (else the builder refuses). Validated by {@code AttachmentGroupRule} on the next {@code validate(...)}.
     */
    public ModelDocument addAttachmentGroup(String parentPath, String name,
            Consumer<DocumentModelDraft.AttachmentBuilder> body) {
        DocumentModelDraft.AttachmentBuilder b = new DocumentModelDraft.AttachmentBuilder();
        body.accept(b);
        ElementDraft.Group group = b.toGroup(name, locales(), config().conditionLanguage());
        ArrayNode container = parentPath.isEmpty() ? rootGroups() : containerElements(parentPath);
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

    /** Set the group at {@code groupPath} non-repeating (a single group, {@code repeatability == 1}). */
    public ModelDocument setNonRepeatable(String groupPath) {
        JsonNode group = locate(groupPath, true);
        if (group == null) {
            throw new IllegalArgumentException("no group at path: " + groupPath);
        }
        ((ObjectNode) group.path("Group")).put("repeatability", 1);
        return this;
    }

    /** The group's index field (repetition row-key), if one is set. Fails fast if no group is there. */
    public Optional<String> indexField(String groupPath) {
        ObjectNode body = groupBody(groupPath);
        return Optional.ofNullable(body.path("indexFieldName").asText(null));
    }

    /**
     * Designate a direct-child field of the <b>repeatable</b> group at {@code groupPath} as its index field
     * — the repetition row-key that arms {@code RepetitionNotUnique} + parallel iteration. Requires a
     * repeatable group (an index field on a single group is a degenerate row-key) and an existing
     * direct-child field; fails fast otherwise. The {@code validate()} gate stays the authoritative check.
     */
    public ModelDocument setIndexField(String groupPath, String fieldName) {
        ObjectNode body = groupBody(groupPath);
        if (body.path("repeatability").asInt(1) <= 1) {
            throw new IllegalArgumentException("an index field needs a repeatable group; " + groupPath
                    + " is not repeatable");
        }
        boolean childField = false;
        for (JsonNode e : body.path("elements")) {
            if ("Field".equals(e.path("type").asText()) && fieldName.equals(e.path("name").asText())) {
                childField = true;
                break;
            }
        }
        if (!childField) {
            throw new IllegalArgumentException("index field '" + fieldName
                    + "' is not a direct child field of " + groupPath);
        }
        body.put("indexFieldName", fieldName);
        return this;
    }

    /** Clear the group's index field (no-op if none). Fails fast if no group is there. */
    public ModelDocument clearIndexField(String groupPath) {
        groupBody(groupPath).remove("indexFieldName");
        return this;
    }

    /** The {@code "Group"} payload object at {@code groupPath}; fails fast if no group is there. */
    private ObjectNode groupBody(String groupPath) {
        JsonNode group = locate(groupPath, true);
        if (group == null) {
            throw new IllegalArgumentException("no group at path: " + groupPath);
        }
        return (ObjectNode) group.path("Group");
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

    /** Allow (or forbid) short-name field references where unique — the {@code modelConfig} flag. */
    public ModelDocument setFieldRefByShortNameAllowed(boolean allowed) {
        modelConfig().put("fieldRefByShortNameAllowed", allowed);
        return this;
    }

    /**
     * Set the {@code modelConfig} allowed-character set (the kernel checks string/enum/custom field values
     * against it). Each entry is a single character or a range like {@code " -~"}; an empty list clears the
     * restriction (all of Unicode-Plane-0 allowed).
     */
    public ModelDocument setSupportedCharacters(List<String> chars) {
        ObjectNode config = modelConfig();
        config.remove("supportedCharacters");
        if (!chars.isEmpty()) {
            ArrayNode array = config.putArray("supportedCharacters");
            chars.forEach(array::add);
        }
        return this;
    }

    /** Set the model base year ({@code modelInfo.baseYear}) — resolves year-less dates / the BaseYear point-in-time. */
    public ModelDocument setBaseYear(int year) {
        modelInfo().put("baseYear", String.valueOf(year));
        return this;
    }

    private ObjectNode modelConfig() {
        JsonNode config = root.path("content").path("modelConfig");
        if (!config.isObject()) {
            throw new IllegalStateException("model has no content.modelConfig object");
        }
        return (ObjectNode) config;
    }

    private ObjectNode modelInfo() {
        JsonNode info = root.path("content").path("modelInfo");
        if (!info.isObject()) {
            throw new IllegalStateException("model has no content.modelInfo object");
        }
        return (ObjectNode) info;
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

    /** Re-configure the underlying type of the type definition {@code id}; fails fast if absent. */
    public ModelDocument setTypeDefinitionType(String id, FieldTypeSpec type) {
        JsonNode tds = root.path("content").path("typeDefinitions");
        if (tds.isArray()) {
            for (JsonNode td : tds) {
                if (id.equals(td.path("id").asText())) {
                    DocumentModelDraft.writeFieldType(((ObjectNode) td).putObject("fieldType"), type, locales());
                    return this;
                }
            }
        }
        throw new IllegalArgumentException("no type definition with id: " + id);
    }

    /**
     * Every field path typed by the type definition {@code id} (its {@code typeDefinitionId} pointer), in
     * document order — the structural <em>find</em> leg a typedef refactor rewrites against (task #2).
     */
    public List<String> fieldsUsingTypeDefinition(String id) {
        List<String> out = new ArrayList<>();
        walk((path, node, isGroup) -> {
            if (!isGroup && id.equals(node.path("Field").path("fieldType")
                    .path("TypeDefType").path("typeDefinitionId").asText(null))) {
                out.add(path);
            }
        });
        return out;
    }

    // --- rename refactors (CLI-SPEC §6 — structural; the CLI safety gate refuses if the element is referenced) ---

    /** Rename the field at {@code fieldPath} to {@code newName} (its name + id). Fails fast if absent. */
    public ModelDocument renameField(String fieldPath, String newName) {
        JsonNode field = locate(fieldPath, false);
        if (field == null) {
            throw new IllegalArgumentException("no field at: " + fieldPath);
        }
        ((ObjectNode) field).put("name", newName).put("id", renamePath(fieldPath, newName));
        return this;
    }

    /**
     * Rename the group at {@code groupPath} to {@code newName} — its name + id AND the id-path of every
     * descendant element (fields, sub-groups, rules, computations), in step. Descendant <em>names</em> are
     * unchanged, so rules inside the subtree (which reference relatively) keep working. Fails fast if absent.
     */
    public ModelDocument renameGroup(String groupPath, String newName) {
        JsonNode group = locate(groupPath, true);
        if (group == null) {
            throw new IllegalArgumentException("no group at: " + groupPath);
        }
        ((ObjectNode) group).put("name", newName);
        reprefixIds(group, groupPath, renamePath(groupPath, newName));
        return this;
    }

    /** Rename the rule at {@code ruleFullName} to {@code newName} (its name + id). Fails fast if absent. */
    public ModelDocument renameRule(String ruleFullName, String newName) {
        ObjectNode rule = findById(ruleFullName);
        if (rule == null || !"Rule".equals(rule.path("type").asText())) {
            throw new IllegalArgumentException("no rule at: " + ruleFullName);
        }
        rule.put("name", newName).put("id", renamePath(ruleFullName, newName));
        return this;
    }

    // --- move refactors (CLI-SPEC §6 — relocate to a different parent; the CLI rewrites references first) ---

    /**
     * Move the field at {@code fieldPath} to become a child of the group at {@code newParentPath} — detach
     * the node, re-home it, and re-prefix its id to the new parent (the {@code name} is unchanged; only the
     * location, hence the id-path, moves). References to the field are the caller's concern — the CLI
     * rewrites them via {@code adapter.ReferenceRewriter} (the kernel's {@code MoveSupportDM}) <em>before</em>
     * this call; {@link #validate()} is the kernel backstop. Fails fast if the field is absent or the
     * destination group does not exist.
     */
    public ModelDocument moveField(String fieldPath, String newParentPath) {
        JsonNode node = locate(fieldPath, false);
        if (node == null) {
            throw new IllegalArgumentException("no field at: " + fieldPath);
        }
        relocate(node, fieldPath, newParentPath, "Field");
        return this;
    }

    /**
     * Move the group at {@code groupPath} (and its whole subtree) under the group at {@code newParentPath}
     * ({@code ""} = a root group) — the group node and every descendant id move in step; descendant
     * <em>names</em> are unchanged, so an internal relative-pathed rule travels intact. References from
     * outside the subtree are the caller's concern (rewritten first, as for {@link #moveField}). Fails fast
     * if the group is absent, the destination group does not exist, or the destination lies inside the
     * moved subtree (which would orphan it).
     */
    public ModelDocument moveGroup(String groupPath, String newParentPath) {
        JsonNode node = locate(groupPath, true);
        if (node == null) {
            throw new IllegalArgumentException("no group at: " + groupPath);
        }
        if (newParentPath.equals(groupPath) || newParentPath.startsWith(groupPath + "/")) {
            throw new IllegalArgumentException(
                    "cannot move group " + groupPath + " into itself or its own subtree: " + newParentPath);
        }
        relocate(node, groupPath, newParentPath, "Group");
        return this;
    }

    /**
     * Detach {@code node} from its current parent and re-home it under {@code newParentPath}, re-prefixing
     * its id-subtree from {@code path} to the new location. The destination is resolved <em>before</em> any
     * detach, so an unknown parent leaves the tree untouched (fail-fast, no half-move).
     */
    private void relocate(JsonNode node, String path, String newParentPath, String type) {
        String name = path.substring(path.lastIndexOf('/') + 1);
        String newPath = newParentPath.isEmpty() ? "/" + name : newParentPath + "/" + name;
        ArrayNode destination = newParentPath.isEmpty() ? rootGroups() : containerElements(newParentPath);
        removeElement(path, type);
        reprefixIds(node, path, newPath);
        destination.add(node);
    }

    private static String renamePath(String path, String newName) {
        return path.substring(0, path.lastIndexOf('/') + 1) + newName;
    }

    /** Re-prefix the id of {@code element} and every descendant from {@code oldPrefix} to {@code newPrefix}. */
    private static void reprefixIds(JsonNode element, String oldPrefix, String newPrefix) {
        if (!(element instanceof ObjectNode obj)) {
            return;
        }
        String id = obj.path("id").asText(null);
        if (id != null && (id.equals(oldPrefix) || id.startsWith(oldPrefix + "/"))) {
            obj.put("id", newPrefix + id.substring(oldPrefix.length()));
        }
        String type = obj.path("type").asText();
        if ("Group".equals(type)) {
            for (JsonNode child : obj.path("Group").path("elements")) {
                reprefixIds(child, oldPrefix, newPrefix);
            }
        } else if ("Rule".equals(type) && obj.path("Rule") instanceof ObjectNode rule) {
            // a carried rule's ABSOLUTE condition operands must re-base to the new root too (probe 01-de
            // Finding 6) — else the sub-model references the old foreign root (MVK_INVALID_ROOT_GROUP). The
            // errorEntityRelPath is relative ('../X') so it rides along unchanged.
            reprefixText(rule, "errorCondition", oldPrefix, newPrefix);
        } else if ("Computation".equals(type) && obj.path("Computation") instanceof ObjectNode comp) {
            reprefixText(comp, "commonPrecondition", oldPrefix, newPrefix);
            for (JsonNode alt : comp.path("computationAlternatives")) {
                if (alt instanceof ObjectNode altObj) {
                    reprefixText(altObj, "precondition", oldPrefix, newPrefix);
                    reprefixText(altObj, "operation", oldPrefix, newPrefix);
                }
            }
        }
    }

    /** Re-prefix every ABSOLUTE path operand in {@code holder.field} from {@code oldPrefix} to {@code newPrefix},
     *  only at a path-segment boundary (so {@code /Order/ShippingExtra} is never matched by {@code /Order/Shipping}). */
    private static void reprefixText(ObjectNode holder, String field, String oldPrefix, String newPrefix) {
        String text = holder.path(field).asText(null);
        if (text == null || !text.contains(oldPrefix)) {
            return;
        }
        String rebased = text.replaceAll(
                java.util.regex.Pattern.quote(oldPrefix) + "(?=[/)\\],\\s]|$)",
                java.util.regex.Matcher.quoteReplacement(newPrefix));
        holder.put(field, rebased);
    }

    /** Find any element (field/group/rule/computation) by its full-name id; {@code null} if absent. */
    private ObjectNode findById(String id) {
        ObjectNode[] found = {null};
        for (JsonNode rootGroup : root.path("content").path("modelRoot").path("rootGroups")) {
            findById(rootGroup, id, found);
        }
        return found[0];
    }

    private void findById(JsonNode node, String id, ObjectNode[] found) {
        if (found[0] != null) {
            return;
        }
        if (node instanceof ObjectNode obj && id.equals(obj.path("id").asText(null))) {
            found[0] = obj;
            return;
        }
        if ("Group".equals(node.path("type").asText())) {
            for (JsonNode child : node.path("Group").path("elements")) {
                findById(child, id, found);
            }
        }
    }

    /**
     * Rename the type definition {@code oldId} to {@code newId} — its {@code typeDefinitions} entry AND
     * every using field's {@code typeDefinitionId}, in step, so nothing dangles (task #3, a structural
     * refactor: a typedef never appears in condition DSL). Fails fast if {@code oldId} is absent; a
     * collision with an existing id is the caller's gate to refuse, with {@link #validate()} as backstop.
     */
    public ModelDocument renameTypeDefinition(String oldId, String newId) {
        JsonNode tds = root.path("content").path("typeDefinitions");
        boolean renamed = false;
        if (tds.isArray()) {
            for (JsonNode td : tds) {
                if (oldId.equals(td.path("id").asText())) {
                    ObjectNode node = (ObjectNode) td;
                    node.put("id", newId);
                    if (oldId.equals(node.path("name").asText())) {
                        node.put("name", newId);   // a default name (== id) tracks the rename; a custom one stays
                    }
                    renamed = true;
                }
            }
        }
        if (!renamed) {
            throw new IllegalArgumentException("no type definition with id: " + oldId);
        }
        walk((path, node, isGroup) -> {
            JsonNode ref = node.path("Field").path("fieldType").path("TypeDefType");
            if (!isGroup && oldId.equals(ref.path("typeDefinitionId").asText(null))) {
                ((ObjectNode) ref).put("typeDefinitionId", newId);
            }
        });
        return this;
    }

    /**
     * Extract the fields at {@code fieldPaths} — which must all share an identical CONCRETE type — into
     * one reusable type definition {@code newId}, repointing each field at it (task #4, a structural
     * refactor). Fails fast if a field is absent, already typed by a definition, or the fields do not
     * share a single concrete type; {@link #validate()} is the kernel backstop.
     */
    public ModelDocument extractTypeDefinition(String newId, List<String> fieldPaths) {
        JsonNode shared = sharedConcreteFieldType(fieldPaths);
        JsonNode tds = root.path("content").path("typeDefinitions");
        ArrayNode array = tds.isArray() ? (ArrayNode) tds
                : ((ObjectNode) root.path("content")).putArray("typeDefinitions");
        ObjectNode td = array.addObject();
        td.put("id", newId);
        td.put("name", newId);
        td.set("fieldType", shared.deepCopy());
        for (String fieldPath : fieldPaths) {
            setFieldType(fieldPath, new FieldTypeSpec.TypeDefRef(newId));
        }
        return this;
    }

    /**
     * Whether the fields at {@code fieldPaths} all share one concrete type to factor out — present and
     * not already a {@code TypeDefType}, identical across the set. The CLI gate calls this before
     * mutating (see {@code SafetyChecks}); {@link #extractTypeDefinition} relies on the same answer.
     */
    public boolean fieldsShareConcreteType(List<String> fieldPaths) {
        try {
            sharedConcreteFieldType(fieldPaths);
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private JsonNode sharedConcreteFieldType(List<String> fieldPaths) {
        if (fieldPaths.isEmpty()) {
            throw new IllegalArgumentException("no fields to extract");
        }
        JsonNode shared = null;
        for (String fieldPath : fieldPaths) {
            JsonNode field = locate(fieldPath, false);
            if (field == null) {
                throw new IllegalArgumentException("no field at: " + fieldPath);
            }
            JsonNode fieldType = field.path("Field").path("fieldType");
            if ("TypeDefType".equals(fieldType.path("type").asText())) {
                throw new IllegalArgumentException(fieldPath + " is already typed by a definition");
            }
            if (shared == null) {
                shared = fieldType;
            } else if (!shared.equals(fieldType)) {
                throw new IllegalArgumentException("fields do not share an identical type: " + fieldPath);
            }
        }
        return shared;
    }

    /**
     * Inline the type definition {@code id} — replace every using field's {@code TypeDefType} with the
     * definition's underlying concrete type, then drop the definition (task #4, the inverse of extract).
     * Fails fast if {@code id} is absent.
     */
    public ModelDocument inlineTypeDefinition(String id) {
        JsonNode underlying = typeDefinitionFieldType(id);
        if (underlying == null) {
            throw new IllegalArgumentException("no type definition with id: " + id);
        }
        walk((path, node, isGroup) -> {
            JsonNode fieldType = node.path("Field").path("fieldType");
            if (!isGroup && id.equals(fieldType.path("TypeDefType").path("typeDefinitionId").asText(null))) {
                ((ObjectNode) node.path("Field")).set("fieldType", underlying.deepCopy());
            }
        });
        removeTypeDefinition(id);
        return this;
    }

    private JsonNode typeDefinitionFieldType(String id) {
        JsonNode tds = root.path("content").path("typeDefinitions");
        if (tds.isArray()) {
            for (JsonNode td : tds) {
                if (id.equals(td.path("id").asText())) {
                    return td.path("fieldType");
                }
            }
        }
        return null;
    }

    /** Add a field at {@code groupPath} typed by the type definition {@code typeDefId}. */
    public ModelDocument addTypedefField(String groupPath, String name, String typeDefId) {
        ElementDraft.Field field = new ElementDraft.Field(
                name, new FieldTypeSpec.TypeDefRef(typeDefId), Requiredness.OPTIONAL, Map.of(), List.of());
        containerElements(groupPath).add(DocumentModelDraft.fieldNode(field, groupPath, locales()));
        return this;
    }

    /**
     * The include alias the group at {@code groupPath} mounts ({@code Group.modelAlias}), or empty if it is
     * not an include mount. A mount group's own {@code elements} would <em>shadow</em> the include (the kernel
     * stops expanding it, silently — KERNEL-FINDINGS), so callers use this to refuse editing into a mount.
     */
    public Optional<String> mountAliasAt(String groupPath) {
        JsonNode group = locate(groupPath, true);
        if (group == null) {
            return Optional.empty();
        }
        String alias = group.path("Group").path("modelAlias").asText(null);
        return alias == null || alias.isBlank() ? Optional.empty() : Optional.of(alias);
    }

    // --- includes (header.modelReferences + mount groups) ---------------------------------------

    /** Declare a model include: a header {@code modelReference} aliased {@code alias} → {@code reference}. */
    public ModelDocument addInclude(String alias, String reference) {
        modelReferences().addObject()
                .put("alias", alias)
                .put("modelType", "document")
                .put("purpose", "include")
                .put("reference", reference);
        return this;
    }

    /**
     * Declare a <b>type-definition import</b>: a header {@code modelReference} with
     * {@code purpose: "typeDefinitions"} (the kernel constant {@code PURPOSE_4_ONLY_TYPE_DEFS}). The
     * referenced model's type-defs merge into this model's type-def namespace by id at expand, so a field
     * can be typed by an imported id with a plain {@code typeDefinitionId} (no alias prefix). The alias is a
     * label (defaults to {@code reference}); unlike an include, an import is <em>not</em> mounted.
     */
    public ModelDocument addImport(String reference) {
        modelReferences().addObject()
                .put("alias", reference)
                .put("modelType", "document")
                .put("purpose", IMPORT_PURPOSE)
                .put("reference", reference);
        return this;
    }

    /** The header's {@code modelReferences} array, created if absent. */
    private ArrayNode modelReferences() {
        JsonNode refs = root.path("header").path("modelReferences");
        return refs.isArray() ? (ArrayNode) refs
                : ((ObjectNode) root.path("header")).putArray("modelReferences");
    }

    /** Mount a declared include at {@code parentPath} as a group named {@code name} (a {@code modelAlias} group). */
    public ModelDocument mount(String parentPath, String name, String alias) {
        return mount(parentPath, name, alias, 1);
    }

    /** Mount a declared include as a <b>repeatable</b> sub-form ({@code repeatability} rows; FEATURE-GAPS §2). */
    public ModelDocument mount(String parentPath, String name, String alias, int repeatability) {
        return mount(parentPath, name, alias, MountOptions.repeatable(repeatability));
    }

    /**
     * Mount a declared include with explicit {@link MountOptions} — repeatability plus whether to exclude
     * the included model's rules / computations on expand (FEATURE-GAPS §3.9).
     */
    public ModelDocument mount(String parentPath, String name, String alias, MountOptions options) {
        ArrayNode container = parentPath.isEmpty() ? rootGroups() : containerElements(parentPath);
        container.add(DocumentModelDraft.mountNode(
                new ElementDraft.Mount(name, alias, options, List.of()), parentPath));
        return this;
    }

    /**
     * Extract the group at {@code groupPath} into a standalone sub-model — returned as DM-JSON — and
     * replace it in <em>this</em> base with a <b>mount</b> of that sub-model at the same name, so every
     * composed path is preserved (task #7, a Tier-3 structural refactor). The sub-model reuses the base's
     * envelope (locales, config) with header id {@code reference} and a single root group = the extracted
     * subtree rebased to {@code /<name>}; the base gains the {@code modelReference} + {@code modelAlias}
     * mount. Rules inside the subtree travel into the sub-model (they reference relatively). A rule inside
     * the subtree that references <em>outside</em> it, or a repeatable group, is the caller's gate to
     * refuse (the kernel re-validate of the composed pair is the backstop). Fails fast if the group is absent.
     */
    public String extractGroupAsInclude(String groupPath, String reference) {
        JsonNode group = locate(groupPath, true);
        if (group == null) {
            throw new IllegalArgumentException("no group at: " + groupPath);
        }
        String mountName = group.path("name").asText();

        // the sub-model: the base envelope, its own id, no inherited includes, one root = the rebased subtree.
        ObjectNode sub = root.deepCopy();
        ObjectNode subHeader = (ObjectNode) sub.path("header");
        subHeader.put("id", reference);
        subHeader.putArray("modelReferences");   // empty (not absent — the kernel expects a list), no inherited includes
        // the deep-copy carried the parent's modelInfo.name — rename it for the new sub-model (else an
        // extracted "address" sub-model stays named "person"; probe P4).
        JsonNode subInfo = sub.path("content").path("modelInfo");
        if (subInfo.isObject()) {
            ((ObjectNode) subInfo).put("name", reference);
        }
        ObjectNode subGroup = group.deepCopy();
        reprefixIds(subGroup, groupPath, "/" + mountName);
        ((ObjectNode) sub.path("content").path("modelRoot")).putArray("rootGroups").add(subGroup);
        String subModelJson;
        try {
            subModelJson = MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(sub);
        } catch (JsonProcessingException e) {
            throw new UncheckedIOException(new IOException("failed to serialize the extracted sub-model", e));
        }

        // the base: drop the group, declare the reference, mount it at the same name (path preserved).
        removeGroup(groupPath);
        addInclude(reference, reference);
        mount(groupPath.substring(0, groupPath.lastIndexOf('/')), mountName, reference);
        return subModelJson;
    }

    /**
     * Inline the include aliased {@code alias} — the inverse of {@link #extractGroupAsInclude}: fold the
     * included model's content (passed as {@code includedModelJson}) back into every mount as a real group
     * (rebasing the sub-model's root path to the mount path), then drop the header reference. The included
     * JSON is the caller's to supply (it lives in a separate file; this class is resolver-free). Fails fast
     * if no mount carries {@code alias}.
     */
    public ModelDocument inlineInclude(String alias, String includedModelJson) {
        ModelDocument included = read(includedModelJson);
        JsonNode includedRoot = included.root.path("content").path("modelRoot").path("rootGroups").path(0);
        if (includedRoot.isMissingNode()) {
            throw new IllegalArgumentException("included model has no root group for alias: " + alias);
        }
        String includedRootId = includedRoot.path("id").asText();
        JsonNode includedElements = includedRoot.path("Group").path("elements");
        boolean[] folded = {false};
        inlineMounts(rootGroups(), alias, includedRootId, includedElements, folded);
        if (!folded[0]) {
            throw new IllegalArgumentException("no mount with alias: " + alias);
        }
        removeInclude(alias);   // mounts no longer carry modelAlias, so this only drops the header reference
        return this;
    }

    private void inlineMounts(JsonNode elements, String alias, String includedRootId, JsonNode includedElements,
            boolean[] folded) {
        for (JsonNode element : elements) {
            if (!"Group".equals(element.path("type").asText())) {
                continue;
            }
            ObjectNode body = (ObjectNode) element.path("Group");
            if (alias.equals(body.path("modelAlias").asText(null))) {
                String mountPath = element.path("id").asText();
                body.remove("modelAlias");
                ArrayNode destination = body.putArray("elements");
                for (JsonNode child : includedElements) {
                    JsonNode copy = child.deepCopy();
                    reprefixIds(copy, includedRootId, mountPath);
                    destination.add(copy);
                }
                folded[0] = true;
            } else if (body.path("elements").isArray()) {
                inlineMounts(body.path("elements"), alias, includedRootId, includedElements, folded);
            }
        }
    }

    /** Every mount-group path that mounts the include aliased {@code alias} ({@code modelAlias}), in document order (task #2). */
    public List<String> mountsOfInclude(String alias) {
        List<String> out = new ArrayList<>();
        walk((path, node, isGroup) -> {
            if (isGroup && alias.equals(node.path("Group").path("modelAlias").asText(null))) {
                out.add(path);
            }
        });
        return out;
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

    /** Re-point the include {@code alias} at a different referenced model — the mounts, keyed by alias, are untouched. */
    public ModelDocument setIncludeReference(String alias, String newReference) {
        JsonNode refs = root.path("header").path("modelReferences");
        if (refs.isArray()) {
            for (JsonNode ref : refs) {
                if (alias.equals(ref.path("alias").asText())) {
                    ((ObjectNode) ref).put("reference", newReference);
                    return this;
                }
            }
        }
        throw new IllegalArgumentException("no include with alias: " + alias);
    }

    /**
     * Rename an include's alias — its header {@code modelReference} <em>and</em> every mount's {@code
     * modelAlias}, in step, so nothing dangles. Mount group names/ids are unchanged, so rules over mounted
     * content (keyed by path, not alias) are unaffected. Fails fast if the alias is present nowhere.
     */
    public ModelDocument renameInclude(String oldAlias, String newAlias) {
        boolean renamed = false;
        JsonNode refs = root.path("header").path("modelReferences");
        if (refs.isArray()) {
            for (JsonNode ref : refs) {
                if (oldAlias.equals(ref.path("alias").asText())) {
                    ((ObjectNode) ref).put("alias", newAlias);
                    renamed = true;
                }
            }
        }
        boolean[] mountRenamed = {false};
        renameMounts(rootGroups(), oldAlias, newAlias, mountRenamed);
        if (!renamed && !mountRenamed[0]) {
            throw new IllegalArgumentException("no include with alias: " + oldAlias);
        }
        return this;
    }

    private void renameMounts(ArrayNode elements, String oldAlias, String newAlias, boolean[] any) {
        for (JsonNode el : elements) {
            if (!"Group".equals(el.path("type").asText())) {
                continue;
            }
            ObjectNode body = (ObjectNode) el.path("Group");
            if (oldAlias.equals(body.path("modelAlias").asText())) {
                body.put("modelAlias", newAlias);
                any[0] = true;
            } else if (body.path("elements").isArray()) {
                renameMounts((ArrayNode) body.path("elements"), oldAlias, newAlias, any);
            }
        }
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
        // never write a local child into an include mount: the kernel would use the local elements and stop
        // expanding the include, silently discarding the mounted content (KERNEL-FINDINGS). The loud backstop
        // under every add/move; the CLI runs SafetyChecks.notAnIncludeMount first for a structured refusal.
        mountAliasAt(groupPath).ifPresent(alias -> {
            throw new IllegalArgumentException("group '" + groupPath + "' mounts the include '" + alias
                    + "' — adding a child would shadow the mounted content; edit the included model, or use a non-mount group");
        });
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
            boolean fieldRefByShortNameAllowed, List<String> supportedCharacters) {}
}
