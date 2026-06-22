package io.github.mbackschat.a12.dm.core;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/**
 * {@link ValueKind#fromKernelType(String)} — the read-side inverse of the kernel {@code fieldType.type}
 * discriminator, the single source the CLI's read/echo surfaces use so a field's value type is always
 * reported with the clean {@code ValueKind} vocabulary (never the raw kernel string). Locked against
 * the exact discriminators {@code DocumentModelDraft.writeFieldType} emits.
 */
class ValueKindTest {

    @Test
    void mapsEveryKernelDiscriminatorToItsValueKind() {
        assertEquals(ValueKind.NUMBER, ValueKind.fromKernelType("NumberType"));
        assertEquals(ValueKind.STRING, ValueKind.fromKernelType("StringType"));
        assertEquals(ValueKind.ENUMERATION, ValueKind.fromKernelType("EnumerationType"));
        assertEquals(ValueKind.BOOLEAN, ValueKind.fromKernelType("BooleanType"));
        assertEquals(ValueKind.CONFIRM, ValueKind.fromKernelType("ConfirmType"));
        assertEquals(ValueKind.DATE, ValueKind.fromKernelType("DateType"));
        assertEquals(ValueKind.DATE_TIME, ValueKind.fromKernelType("DateTimeType"));
        assertEquals(ValueKind.TIME, ValueKind.fromKernelType("TimeType"));
        assertEquals(ValueKind.DATE_FRAGMENT, ValueKind.fromKernelType("DateFragmentType"));
        assertEquals(ValueKind.DATE_RANGE, ValueKind.fromKernelType("DateRangeType"));
        // the kernel discriminator for a custom type is CustomFieldType (not "CustomType")
        assertEquals(ValueKind.CUSTOM, ValueKind.fromKernelType("CustomFieldType"));
    }

    @Test
    void typeDefIsNotClassifiedHere_theCallerResolvesItFirst() {
        // a type-def reference has no value kind of its own — the caller follows it to the underlying
        // discriminator before classifying; an unresolved TypeDefType is honestly UNKNOWN
        assertEquals(ValueKind.UNKNOWN, ValueKind.fromKernelType("TypeDefType"));
    }

    @Test
    void unrecognizedOrNullIsUnknown() {
        assertEquals(ValueKind.UNKNOWN, ValueKind.fromKernelType("WidgetType"));
        assertEquals(ValueKind.UNKNOWN, ValueKind.fromKernelType(null));
    }
}
