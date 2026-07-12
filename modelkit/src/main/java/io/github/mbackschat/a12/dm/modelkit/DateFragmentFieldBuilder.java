package io.github.mbackschat.a12.dm.modelkit;

/** Configures a date-fragment field (e.g. year-month) — the fragment format is required. */
public final class DateFragmentFieldBuilder extends FieldAttrs<DateFragmentFieldBuilder> {
    private String fragmentFormat;

    public DateFragmentFieldBuilder fragmentFormat(String format) {
        this.fragmentFormat = format;
        return this;
    }

    protected FieldTypeSpec spec() {
        if (fragmentFormat == null) {
            throw new IllegalStateException("a date fragment needs a fragment format");
        }
        return new FieldTypeSpec.DateFragmentSpec(fragmentFormat);
    }
}
