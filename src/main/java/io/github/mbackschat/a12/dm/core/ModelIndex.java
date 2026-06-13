package io.github.mbackschat.a12.dm.core;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.mgmtp.a12.kernel.md.model.api.IDocumentModel;
import com.mgmtp.a12.kernel.md.model.api.IElement;
import com.mgmtp.a12.kernel.md.model.api.IField;
import com.mgmtp.a12.kernel.md.model.api.IGroup;
import com.mgmtp.a12.kernel.md.model.api.fieldtypes.IEnumerationType;
import com.mgmtp.a12.kernel.md.model.api.fieldtypes.IFieldType;
import com.mgmtp.a12.kernel.md.model.api.fieldtypes.INumberType;

/**
 * A queryable index over a fully-expanded DocumentModel — the foundation everything else binds to
 * (proposal §5a / F0). Maps each element's absolute path to its model node so the AST, renderer,
 * and pre-checks can resolve paths, operand kinds, and repetition structure.
 *
 * <p>Build via {@link #of(IDocumentModel)} on a model already expanded + consistency-checked by
 * {@link ModelLoader}. Lookups fail fast with {@link IllegalArgumentException} on an unknown path or
 * a path that resolves to the wrong kind of node.
 */
public final class ModelIndex {

    private final IDocumentModel model;
    private final Map<String, IElement> byPath;

    private ModelIndex(IDocumentModel model, Map<String, IElement> byPath) {
        this.model = model;
        this.byPath = byPath;
    }

    /** Index an already-expanded, consistency-checked model (see {@link ModelLoader}). */
    public static ModelIndex of(IDocumentModel expandedModel) {
        Map<String, IElement> byPath = new LinkedHashMap<>();
        index(expandedModel.getContent().getDocumentModelRoot(), "", byPath);
        return new ModelIndex(expandedModel, byPath);
    }

    /**
     * Key every element by its <b>name-path</b> — the join of {@code getName()} segments, which is the
     * path a rule condition references ({@code getFullName()}) and the form the renderer/handles use.
     * <b>Not</b> {@code getId()}: an element's id is an opaque identifier (often a hash) that equals
     * the path only in models authored that way (e.g. rulekit's own fixtures); real-world models —
     * the kernel-mm metamodels, the A12 Tools apps — carry id ≠ name, so keying by id resolved ~0% of
     * their references (F-IDX).
     */
    private static void index(IGroup group, String prefix, Map<String, IElement> byPath) {
        for (IElement element : group.getElements()) {
            String path = prefix + "/" + element.getName();
            byPath.put(path, element);
            if (element instanceof IGroup childGroup) {
                index(childGroup, path, byPath);
            }
        }
    }

    /** The underlying expanded model. */
    public IDocumentModel model() {
        return model;
    }

    /**
     * The model's declared locales (header {@code locales}, e.g. {@code en_US}) — any valid Java
     * locale code is possible, EN/DE are <em>not</em> special here. Error messages must cover every
     * declared locale ({@code MVK_ERROR_MESSAGE_FOR_LANGUAGE_MISSING} — verified); rulekit
     * additionally rejects messages for <em>undeclared</em> locales (the kernel tolerates them
     * silently, which usually hides a typo or dead text).
     */
    public List<String> locales() {
        List<java.util.Locale> locales = model.getHeader().getLocales();
        return locales == null ? List.of() : locales.stream().map(java.util.Locale::toString).toList();
    }

    /** The {@link ValueKind} of the field at {@code fieldPath} (type-definitions resolved). */
    public ValueKind kindOf(String fieldPath) {
        return ValueKind.of(effectiveType(field(fieldPath)));
    }

    /**
     * The kernel's resolved {@link IFieldType} for the field at {@code fieldPath} (type-definitions
     * resolved) — the typed read-back surface (cast to {@code INumberType}/{@code IStringType}/… for a
     * slot's value). Used to confirm the engine <em>honored</em> an authored field-config slot, which a
     * load alone can't (the deserializer ignores unknown keys).
     */
    public IFieldType fieldType(String fieldPath) {
        return effectiveType(field(fieldPath));
    }

    /**
     * The declared decimal scale (max fractional digits) of the number field at {@code fieldPath}.
     *
     * @throws IllegalArgumentException if the field is not a number
     */
    public int numberScale(String fieldPath) {
        IFieldType type = effectiveType(field(fieldPath));
        if (!(type instanceof INumberType number)) {
            throw new IllegalArgumentException(
                    fieldPath + " is not a number field (kind=" + ValueKind.of(type) + ")");
        }
        return number.getMaxFractionalDigits();
    }

    /**
     * The allowed stored values of the enumeration field at {@code fieldPath} (the enum domain).
     * Enums compare by stored value, never display text (§5b), so these are the values to compare to.
     *
     * @throws IllegalArgumentException if the field is not an enumeration
     */
    public List<String> enumValues(String fieldPath) {
        IFieldType type = effectiveType(field(fieldPath));
        if (!(type instanceof IEnumerationType enumeration)) {
            throw new IllegalArgumentException(
                    fieldPath + " is not an enumeration field (kind=" + ValueKind.of(type) + ")");
        }
        return enumeration.getValues().stream().map(IEnumerationType.IEnumValue::getValue).toList();
    }

    /**
     * The categories of the enumeration field at {@code fieldPath}: category name → its distinct
     * category values (the model maps positionally — {@code categories[c].values[i]} pairs with
     * enum {@code values[i]}; the comparison domain is the distinct set).
     *
     * @throws IllegalArgumentException if the field is not an enumeration
     */
    public Map<String, List<String>> enumCategories(String fieldPath) {
        IFieldType type = effectiveType(field(fieldPath));
        if (!(type instanceof IEnumerationType enumeration)) {
            throw new IllegalArgumentException(
                    fieldPath + " is not an enumeration field (kind=" + ValueKind.of(type) + ")");
        }
        Map<String, List<String>> categories = new LinkedHashMap<>();
        for (IEnumerationType.IEnumCategory category : enumeration.getCategories()) {
            categories.put(category.getName(), category.getValues().stream().distinct().toList());
        }
        return categories;
    }

    /** Whether the group at {@code groupPath} can repeat (repeatability other than exactly 1). */
    public boolean isRepeating(String groupPath) {
        return group(groupPath).getRepeatability() != 1;
    }

    // --- navigation (Level 1, study finding 9): streams of facts in model order -----------------

    /** Paths of all fields, in model order. */
    public java.util.stream.Stream<String> fieldPaths() {
        return byPath.entrySet().stream()
                .filter(e -> e.getValue() instanceof IField)
                .map(Map.Entry::getKey);
    }

    /** Paths of all groups (root groups included), in model order. */
    public java.util.stream.Stream<String> groupPaths() {
        return byPath.entrySet().stream()
                .filter(e -> e.getValue() instanceof IGroup)
                .map(Map.Entry::getKey);
    }

    /** Paths of the fields directly contained in the group at {@code groupPath}, in model order. */
    public java.util.stream.Stream<String> fieldPaths(String groupPath) {
        return group(groupPath).getElements().stream()
                .filter(IField.class::isInstance)
                .map(e -> groupPath + "/" + e.getName());
    }

    /** Whether a field exists at {@code path} (the non-throwing peek behind {@code fieldAt}). */
    public boolean isField(String path) {
        return byPath.get(path) instanceof IField;
    }

    /** The element's short name (last path segment, as modeled). */
    public String nameOf(String path) {
        return require(path).getName();
    }

    private static IFieldType effectiveType(IField field) {
        return field.getEffectiveType().orElseGet(field::getFieldType);
    }

    /**
     * The kernel's {@link IField} at {@code fieldPath} — the typed read-back surface for field-level metadata
     * (its {@code isGlobal()}/{@code isTransient()}/{@code getHelperText()} and, via {@code IElement}, the
     * internal/external descriptions). The same purpose as {@link #fieldType}: confirm the engine honored an
     * authored slot the silent-ignore deserializer would otherwise hide (KERNEL-FINDINGS).
     */
    public IField field(String path) {
        if (require(path) instanceof IField field) {
            return field;
        }
        throw new IllegalArgumentException("not a field: " + path);
    }

    /**
     * The kernel's {@link IGroup} at {@code groupPath} — the typed read-back surface for group-level slots
     * the silent-ignore deserializer would otherwise hide, e.g. {@code getUsageType()} (the attachment /
     * multi-select template marker). The group peer of {@link #field}.
     */
    public IGroup group(String path) {
        if (require(path) instanceof IGroup group) {
            return group;
        }
        throw new IllegalArgumentException("not a group: " + path);
    }

    private IElement require(String path) {
        IElement element = byPath.get(path);
        if (element == null) {
            throw new IllegalArgumentException("no element at path: " + path
                    + Suggest.hint(path, byPath.keySet()));
        }
        return element;
    }
}
