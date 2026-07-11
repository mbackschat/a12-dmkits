package io.github.mbackschat.a12.dm.core;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;

import com.mgmtp.a12.kernel.md.model.api.IDocumentModel;
import com.mgmtp.a12.kernel.md.model.api.IElement;
import com.mgmtp.a12.kernel.md.model.api.IField;
import com.mgmtp.a12.kernel.md.model.api.IGroup;
import com.mgmtp.a12.kernel.md.model.api.fieldtypes.IBooleanType;
import com.mgmtp.a12.kernel.md.model.api.fieldtypes.IConfirmType;
import com.mgmtp.a12.kernel.md.model.api.fieldtypes.ICustomFieldType;
import com.mgmtp.a12.kernel.md.model.api.fieldtypes.IDateFragmentType;
import com.mgmtp.a12.kernel.md.model.api.fieldtypes.IDateRangeType;
import com.mgmtp.a12.kernel.md.model.api.fieldtypes.IDateTimeType;
import com.mgmtp.a12.kernel.md.model.api.fieldtypes.IDateType;
import com.mgmtp.a12.kernel.md.model.api.fieldtypes.IEnumerationType;
import com.mgmtp.a12.kernel.md.model.api.fieldtypes.IFieldType;
import com.mgmtp.a12.kernel.md.model.api.fieldtypes.INumberType;
import com.mgmtp.a12.kernel.md.model.api.fieldtypes.IStringType;
import com.mgmtp.a12.kernel.md.model.api.fieldtypes.ITimeType;

/**
 * A queryable index over a fully-expanded DocumentModel — the foundation everything else binds to
 * (PROJECT-DESIGN §5a / F0). Maps each element's absolute path to its model node so the AST, renderer,
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
        return valueKind(effectiveType(field(fieldPath)));
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
                    fieldPath + " is not a number field (kind=" + valueKind(type) + ")");
        }
        return number.getMaxFractionalDigits();
    }

    /**
     * The declared inclusive lower bound of the number field at {@code fieldPath} ({@code minimum}), or empty
     * when the field is not a number or no bound is declared. A read-only config accessor (no throw on a
     * non-number, so callers can probe any field) — see {@link #numberMax}/{@link #stringMinLength}/etc.
     */
    public Optional<BigDecimal> numberMin(String fieldPath) {
        return effectiveType(field(fieldPath)) instanceof INumberType n ? n.getMinValue() : Optional.empty();
    }

    /** The declared inclusive upper bound ({@code maximum}) of the number field, or empty (see {@link #numberMin}). */
    public Optional<BigDecimal> numberMax(String fieldPath) {
        return effectiveType(field(fieldPath)) instanceof INumberType n ? n.getMaxValue() : Optional.empty();
    }

    /** The declared {@code minLength} of the string field, or empty when not a string / unset. */
    public OptionalInt stringMinLength(String fieldPath) {
        return effectiveType(field(fieldPath)) instanceof IStringType s
                ? s.getMinLength().stream().mapToInt(Integer::intValue).findFirst() : OptionalInt.empty();
    }

    /** The declared {@code maxLength} of the string field, or empty when not a string / unset. */
    public OptionalInt stringMaxLength(String fieldPath) {
        return effectiveType(field(fieldPath)) instanceof IStringType s
                ? s.getMaxLength().stream().mapToInt(Integer::intValue).findFirst() : OptionalInt.empty();
    }

    /** The declared regex {@code pattern} of the string field, or empty when not a string / no pattern. */
    public Optional<String> stringPattern(String fieldPath) {
        return effectiveType(field(fieldPath)) instanceof IStringType s
                ? s.getPattern().filter(p -> !p.isEmpty()) : Optional.empty();
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
                    fieldPath + " is not an enumeration field (kind=" + valueKind(type) + ")");
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
                    fieldPath + " is not an enumeration field (kind=" + valueKind(type) + ")");
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

    /** The group's maximum repetitions — the repeatability count ({@code 1} = non-repeatable). */
    public int maxRepetitions(String groupPath) {
        return group(groupPath).getRepeatability();
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

    /** Whether a group exists at {@code path} (the non-throwing peek behind {@code groupAt}). */
    public boolean isGroup(String path) {
        return byPath.get(path) instanceof IGroup;
    }

    /** The element's short name (last path segment, as modeled). */
    public String nameOf(String path) {
        return require(path).getName();
    }

    private static IFieldType effectiveType(IField field) {
        return field.getEffectiveType().orElseGet(field::getFieldType);
    }

    /**
     * Classify an (already type-def-resolved) kernel {@link IFieldType} into a {@link ValueKind}. The kernel
     * exposes no readable type tag ({@code IFieldType} is a bare marker), so the kind is recovered by dispatch
     * on the concrete public subtype. Order is subtype-first: {@code IDateTimeType} and {@code ITimeType} both
     * extend {@code IDateType}, so they must be tested before it. (Lives here, on the kernel-typed index,
     * rather than on the kernel-free {@link ValueKind} enum.)
     */
    private static ValueKind valueKind(IFieldType type) {
        if (type instanceof IDateTimeType) {
            return ValueKind.DATE_TIME;
        }
        if (type instanceof ITimeType) {
            return ValueKind.TIME;
        }
        if (type instanceof IDateType) {
            return ValueKind.DATE;
        }
        if (type instanceof IDateFragmentType) {
            return ValueKind.DATE_FRAGMENT;
        }
        if (type instanceof IDateRangeType) {
            return ValueKind.DATE_RANGE;
        }
        if (type instanceof INumberType) {
            return ValueKind.NUMBER;
        }
        if (type instanceof IStringType) {
            return ValueKind.STRING;
        }
        if (type instanceof IEnumerationType) {
            return ValueKind.ENUMERATION;
        }
        if (type instanceof IConfirmType) {
            return ValueKind.CONFIRM;
        }
        if (type instanceof IBooleanType) {
            return ValueKind.BOOLEAN;
        }
        if (type instanceof ICustomFieldType) {
            return ValueKind.CUSTOM;
        }
        return ValueKind.UNKNOWN;
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
