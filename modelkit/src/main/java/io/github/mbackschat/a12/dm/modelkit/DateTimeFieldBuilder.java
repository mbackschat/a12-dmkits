package io.github.mbackschat.a12.dm.modelkit;

/** Configures a date-time field's constant format. */
public final class DateTimeFieldBuilder extends FieldAttrs<DateTimeFieldBuilder> {
    private String format;

    public DateTimeFieldBuilder format(String format) {
        this.format = format;
        return this;
    }

    protected FieldTypeSpec spec() {
        return new FieldTypeSpec.DateTimeSpec(format);
    }
}
