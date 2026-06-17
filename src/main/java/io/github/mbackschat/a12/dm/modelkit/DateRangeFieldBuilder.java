package io.github.mbackschat.a12.dm.modelkit;

/** Configures a date-range field — both a format and a range separator are required by the kernel. */
public final class DateRangeFieldBuilder extends FieldAttrs<DateRangeFieldBuilder> {
    private String format;
    private String separator;

    public DateRangeFieldBuilder format(String format) {
        this.format = format;
        return this;
    }

    public DateRangeFieldBuilder separator(String separator) {
        this.separator = separator;
        return this;
    }

    protected FieldTypeSpec spec() {
        if (format == null || separator == null) {
            throw new IllegalStateException("a date range needs both a format and a range separator");
        }
        return new FieldTypeSpec.DateRangeSpec(format, separator);
    }
}
