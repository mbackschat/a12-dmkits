package io.github.mbackschat.a12.dm.modelkit;

/** Configures a custom-typed field: a named kernel custom type with optional length bounds (FEATURE-GAPS). */
public final class CustomFieldBuilder extends FieldAttrs<CustomFieldBuilder> {
    private String typeName;
    private Integer minLength;
    private Integer maxLength;

    /** The technical name of the registered kernel custom field type (required). */
    public CustomFieldBuilder type(String name) {
        this.typeName = name;
        return this;
    }

    public CustomFieldBuilder minLength(int n) {
        this.minLength = n;
        return this;
    }

    public CustomFieldBuilder maxLength(int n) {
        this.maxLength = n;
        return this;
    }

    protected FieldTypeSpec spec() {
        if (typeName == null) {
            throw new IllegalStateException("a custom field needs a type name — call .type(\"…\")");
        }
        return new FieldTypeSpec.CustomSpec(typeName, minLength, maxLength);
    }
}
