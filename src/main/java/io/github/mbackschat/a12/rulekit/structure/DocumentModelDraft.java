package io.github.mbackschat.a12.rulekit.structure;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * modelkit M1 — a typed builder for document-model <b>structure</b>, the peer of rulekit's rule
 * builder (fork #8 / ARCHITECTURE §5.2; rationale in {@code docs/MODELKIT-PROPOSAL.md}). Author a
 * model from scratch — {@code model(id, locale) → group(name) → field(name, kind)} — and emit
 * DM-JSON the real kernel accepts ({@code checkConsistency} + the {@code BasicConsistencyCheck} write
 * gate, proven by {@code DocumentModelDraftTest}).
 *
 * <p><b>Pure emit, by design.</b> This package depends only on Jackson — never on the kernel or on
 * rulekit's {@code dsl} (the umbrella's {@code structure ⊥ dsl} rule, D10). Validation is the
 * caller's job (load the JSON through {@code model.ModelLoader}); keeping structure kernel-free is
 * what keeps the eventual {@code :modelkit} extraction mechanical and the TypeScript twin possible.
 *
 * <p>The fluent builders are the only constructor, so a model without a locale or a root group, or a
 * group with a non-positive repeatability, can't be built.
 */
public final class DocumentModelDraft {

    /** Kernel model-version stamp written into the header (matches the fixtures' authored value). */
    private static final String MODEL_VERSION = "28.4.0";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final String id;
    private final List<String> locales;
    private final List<ElementDraft.Group> rootGroups;

    private DocumentModelDraft(String id, List<String> locales, List<ElementDraft.Group> rootGroups) {
        this.id = id;
        this.locales = locales;
        this.rootGroups = rootGroups;
    }

    /** Start a draft for the model with this id (also its info name). */
    public static Builder named(String id) {
        return new Builder(id);
    }

    /** The model id. */
    public String id() {
        return id;
    }

    /** Emit the model as pretty-printed DM-JSON — the public, polyglot write surface (D9). */
    public String toJson() {
        ObjectNode root = MAPPER.createObjectNode();

        ObjectNode header = root.putObject("header");
        header.put("id", id);
        header.put("modelType", "document");
        header.put("modelVersion", MODEL_VERSION);
        ArrayNode locs = header.putArray("locales");
        locales.forEach(code -> locs.addObject().put("code", code));
        header.putArray("labels");
        header.putArray("annotations");
        header.putArray("modelReferences");

        ObjectNode content = root.putObject("content");
        ObjectNode info = content.putObject("modelInfo");
        info.put("name", id);
        info.put("variant", "cockpit");
        info.put("revision", "Arbeitsversion");
        info.put("immutable", false);
        ObjectNode config = content.putObject("modelConfig");
        config.put("decimalSeparator", ".");
        config.put("timeZone", "Europe/Berlin");
        config.putObject("conditionLanguage").put("code", locales.get(0));
        config.put("fieldRefByShortNameAllowed", true);

        ArrayNode roots = content.putObject("modelRoot").putArray("rootGroups");
        rootGroups.forEach(group -> roots.add(groupNode(group, "")));

        try {
            return MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(root);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("failed to emit DM-JSON for model " + id, e);
        }
    }

    private static ObjectNode groupNode(ElementDraft.Group group, String parentPath) {
        String path = parentPath + "/" + group.name();
        ObjectNode node = MAPPER.createObjectNode();
        node.put("type", "Group");
        node.put("id", path);
        node.put("name", group.name());
        ObjectNode body = node.putObject("Group");
        body.put("repeatability", group.repeatability());
        ArrayNode elements = body.putArray("elements");
        for (ElementDraft element : group.elements()) {
            elements.add(switch (element) {
                case ElementDraft.Group sub -> groupNode(sub, path);
                case ElementDraft.Field field -> fieldNode(field, path);
            });
        }
        return node;
    }

    private static ObjectNode fieldNode(ElementDraft.Field field, String parentPath) {
        ObjectNode node = MAPPER.createObjectNode();
        node.put("type", "Field");
        node.put("id", parentPath + "/" + field.name());
        node.put("name", field.name());
        ObjectNode fieldType = node.putObject("Field").putObject("fieldType");
        fieldType.put("type", field.kind().jsonType());
        // Easy kinds need no type-specific slot; Number/Date carry a sensible default the kernel wants.
        switch (field.kind()) {
            case NUMBER -> fieldType.putObject("NumberType").put("maxFractionalDigits", 0);
            case DATE -> fieldType.putObject("DateType").put("format", "yyyy-MM-dd");
            default -> { /* String / Boolean / Confirm / Unspecified are bare */ }
        }
        return node;
    }

    /** Fluent builder for a {@link DocumentModelDraft}. */
    public static final class Builder {

        private final String id;
        private final List<String> locales = new ArrayList<>();
        private final List<ElementDraft.Group> rootGroups = new ArrayList<>();

        private Builder(String id) {
            this.id = id;
        }

        /** Add a declared locale (e.g. {@code "en_US"}); a full {@code language_COUNTRY} code (KERNEL-FINDINGS §13). */
        public Builder locale(String code) {
            locales.add(code);
            return this;
        }

        /** Add a top-level group, populated by {@code body}. */
        public Builder rootGroup(String name, Consumer<GroupBuilder> body) {
            GroupBuilder group = new GroupBuilder(name);
            body.accept(group);
            rootGroups.add(group.toDraft());
            return this;
        }

        /** Build the immutable draft, rejecting models that can't be valid (no locale / no group). */
        public DocumentModelDraft build() {
            if (locales.isEmpty()) {
                throw new IllegalStateException("a model needs at least one locale");
            }
            if (rootGroups.isEmpty()) {
                throw new IllegalStateException("a model needs at least one root group");
            }
            return new DocumentModelDraft(id, List.copyOf(locales), List.copyOf(rootGroups));
        }
    }

    /** Fluent builder for a group and its contents. */
    public static final class GroupBuilder {

        private final String name;
        private int repeatability = 1;
        private final List<ElementDraft> elements = new ArrayList<>();

        private GroupBuilder(String name) {
            this.name = name;
        }

        /** Make this group a repeatable list with the given maximum (≥ 2; {@code 1} is the single default). */
        public GroupBuilder repeatable(int max) {
            if (max < 2) {
                throw new IllegalArgumentException("a repeatable group needs max ≥ 2 (1 is non-repeating): " + max);
            }
            this.repeatability = max;
            return this;
        }

        /** Add a field of the given easy kind. */
        public GroupBuilder field(String name, FieldKind kind) {
            elements.add(new ElementDraft.Field(name, kind));
            return this;
        }

        /** Add a nested group, populated by {@code body}. */
        public GroupBuilder group(String name, Consumer<GroupBuilder> body) {
            GroupBuilder child = new GroupBuilder(name);
            body.accept(child);
            elements.add(child.toDraft());
            return this;
        }

        private ElementDraft.Group toDraft() {
            return new ElementDraft.Group(name, repeatability, List.copyOf(elements));
        }
    }
}
