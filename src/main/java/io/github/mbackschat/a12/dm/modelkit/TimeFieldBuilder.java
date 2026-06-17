package io.github.mbackschat.a12.dm.modelkit;

/** Configures a time-of-day field's constant format. */
public final class TimeFieldBuilder extends FieldAttrs<TimeFieldBuilder> {
    private String format;

    public TimeFieldBuilder format(String format) {
        this.format = format;
        return this;
    }

    protected FieldTypeSpec spec() {
        return new FieldTypeSpec.TimeSpec(format);
    }
}
