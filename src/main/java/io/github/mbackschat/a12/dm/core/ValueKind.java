package io.github.mbackschat.a12.dm.core;

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
 * rulekit's value kinds — the vocabulary the operator type-lattice (§5b) draws on. The kernel
 * exposes no readable type tag ({@code IFieldType} is a bare marker), so the kind is recovered by
 * dispatch on the concrete public field-type subtype. Resolve {@code ITypeDefType} to its effective
 * type (via {@link com.mgmtp.a12.kernel.md.model.api.IField#getEffectiveType()}) before classifying.
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
     * Classify an (effective) field type. Order is subtype-first: {@code IDateTimeType} and
     * {@code ITimeType} both extend {@code IDateType}, so they must be tested before it.
     */
    public static ValueKind of(IFieldType type) {
        if (type instanceof IDateTimeType) {
            return DATE_TIME;
        }
        if (type instanceof ITimeType) {
            return TIME;
        }
        if (type instanceof IDateType) {
            return DATE;
        }
        if (type instanceof IDateFragmentType) {
            return DATE_FRAGMENT;
        }
        if (type instanceof IDateRangeType) {
            return DATE_RANGE;
        }
        if (type instanceof INumberType) {
            return NUMBER;
        }
        if (type instanceof IStringType) {
            return STRING;
        }
        if (type instanceof IEnumerationType) {
            return ENUMERATION;
        }
        if (type instanceof IConfirmType) {
            return CONFIRM;
        }
        if (type instanceof IBooleanType) {
            return BOOLEAN;
        }
        if (type instanceof ICustomFieldType) {
            return CUSTOM;
        }
        return UNKNOWN;
    }

    /**
     * Classify a stored kernel DM-JSON {@code fieldType.type} discriminator string (e.g.
     * {@code "NumberType"}, {@code "CustomFieldType"}) — the read-side inverse of {@code
     * FieldKind.jsonType()}, for surfaces that work on the raw model tree rather than the typed kernel
     * objects. {@code "TypeDefType"} is deliberately NOT mapped here: a type-def reference must be
     * resolved to its underlying discriminator by the caller (e.g. {@code ModelDocument.fieldKind})
     * before classifying, exactly as {@link #of(IFieldType)} expects its caller to resolve {@code
     * getEffectiveType()}. An unrecognized or {@code null} discriminator → {@link #UNKNOWN}.
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
