package io.github.mbackschat.a12.dm.modelkit;

/**
 * The field kinds modelkit M1 can author — the <b>easy</b> set, each valid with no mandatory
 * type-specific slot. Each maps to a kernel DM-JSON {@code fieldType.type} discriminator. The richer
 * kinds (Enumeration, money, DateRange, Custom, TypeDef) and per-type config (min/max, pattern,
 * format) arrive in later increments (M2/M3). {@code UnspecifiedType} is deliberately absent: it is
 * an authoring placeholder the kernel rejects in a complete model ({@code
 * MVK_UNSPECIFIED_TYPE_NO_CODEGEN} — "does not specify a field type"), so modelkit can't author it.
 */
public enum FieldKind {

    STRING("StringType"),
    NUMBER("NumberType"),
    DATE("DateType"),
    BOOLEAN("BooleanType"),
    CONFIRM("ConfirmType");

    private final String jsonType;

    FieldKind(String jsonType) {
        this.jsonType = jsonType;
    }

    /** The kernel DM-JSON {@code fieldType.type} discriminator (e.g. {@code "StringType"}). */
    public String jsonType() {
        return jsonType;
    }
}
