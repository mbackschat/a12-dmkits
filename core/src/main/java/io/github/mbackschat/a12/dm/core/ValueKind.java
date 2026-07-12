package io.github.mbackschat.a12.dm.core;

/**
 * rulekit's value kinds — the vocabulary the operator type-lattice (§5b) draws on. A field's kind is
 * recovered by {@link ModelIndex} (which classifies the kernel's bare {@code IFieldType} marker by
 * subtype) for the typed-model path, or from the stored DM-JSON discriminator via
 * {@link #fromKernelType(String)} for surfaces that work on the raw model tree. The enum itself is
 * kernel-free.
 */
public enum ValueKind {
    NUMBER,
    STRING,
    ENUMERATION,
    BOOLEAN,
    CONFIRM,
    DATE,
    DATE_TIME,
    TIME,
    DATE_FRAGMENT,
    DATE_RANGE,
    CUSTOM,
    /** A type rulekit does not (yet) model as first-class — e.g. an unresolved type definition. */
    UNKNOWN;

    /**
     * Classify a stored kernel DM-JSON {@code fieldType.type} discriminator string (e.g.
     * {@code "NumberType"}, {@code "CustomFieldType"}) — the read-side inverse of {@code
     * FieldKind.jsonType()}, for surfaces that work on the raw model tree rather than the typed kernel
     * objects. {@code "TypeDefType"} is deliberately NOT mapped here: a type-def reference must be
     * resolved to its underlying discriminator by the caller (e.g. {@code ModelDocument.fieldKind})
     * before classifying. An unrecognized or {@code null} discriminator → {@link #UNKNOWN}.
     */
    public static ValueKind fromKernelType(String discriminator) {
        if (discriminator == null) {
            return UNKNOWN;
        }
        return switch (discriminator) {
            case "NumberType" -> NUMBER;
            case "StringType" -> STRING;
            case "EnumerationType" -> ENUMERATION;
            case "BooleanType" -> BOOLEAN;
            case "ConfirmType" -> CONFIRM;
            case "DateType" -> DATE;
            case "DateTimeType" -> DATE_TIME;
            case "TimeType" -> TIME;
            case "DateFragmentType" -> DATE_FRAGMENT;
            case "DateRangeType" -> DATE_RANGE;
            case "CustomFieldType" -> CUSTOM;
            default -> UNKNOWN;
        };
    }
}
