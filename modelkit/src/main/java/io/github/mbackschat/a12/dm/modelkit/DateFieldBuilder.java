package io.github.mbackschat.a12.dm.modelkit;

/** Configures a date field's constant format. */
public final class DateFieldBuilder extends FieldAttrs<DateFieldBuilder> {
    private String format;
    private DatePrecision datePrecision = DatePrecision.FULL;
    private boolean rejectBefore1900;

    public DateFieldBuilder format(String format) {
        this.format = format;
        return this;
    }

    /**
     * The date precision. Any value other than {@link DatePrecision#FULL} makes this a
     * <em>partially known date</em> (a day/month/year part may be unknown) — the field
     * {@code ValueAsDate(field, FirstDay|LastDay)} then resolves it to a concrete date.
     */
    public DateFieldBuilder datePrecision(DatePrecision precision) {
        this.datePrecision = precision;
        return this;
    }

    /**
     * Reject values before 1900-01-01 through A12's {@code DateType.youngerThan1900Check}.
     * The check applies to stored and computed values; it is off by default.
     */
    public DateFieldBuilder rejectBefore1900() {
        this.rejectBefore1900 = true;
        return this;
    }

    protected FieldTypeSpec spec() {
        return new FieldTypeSpec.DateSpec(format, datePrecision.jsonValue(), rejectBefore1900);
    }
}
