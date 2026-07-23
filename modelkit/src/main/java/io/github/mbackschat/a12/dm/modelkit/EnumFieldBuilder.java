package io.github.mbackschat.a12.dm.modelkit;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Configures an enumeration field — stored values (each with optional per-locale labels) and
 * optional {@code categories} that classify those values positionally.
 */
public final class EnumFieldBuilder extends FieldAttrs<EnumFieldBuilder> {
    private final List<FieldTypeSpec.EnumValue> values = new ArrayList<>();
    private final List<FieldTypeSpec.EnumCategory> categories = new ArrayList<>();
    private boolean alphabeticalSorting;
    private String invalidValueMessage;
    private String sValue;

    public EnumFieldBuilder value(String value) {
        values.add(new FieldTypeSpec.EnumValue(value, Map.of()));
        return this;
    }

    public EnumFieldBuilder value(String value, Consumer<LabelBuilder> labels) {
        LabelBuilder lb = new LabelBuilder();
        labels.accept(lb);
        values.add(new FieldTypeSpec.EnumValue(value, lb.labels));
        return this;
    }

    /**
     * Add a named classification: one category value per enum value, in the order the values were
     * added ({@code classifications[i]} classifies value {@code i}). Length is checked at build.
     */
    public EnumFieldBuilder category(String name, String... classifications) {
        categories.add(new FieldTypeSpec.EnumCategory(name, List.of(classifications)));
        return this;
    }

    /** Present the values sorted alphabetically rather than in declared order (a UI hint the kernel records). */
    public EnumFieldBuilder alphabeticalSorting() {
        this.alphabeticalSorting = true;
        return this;
    }

    /** The error text shown when an out-of-domain value is supplied (one text, used for every declared locale). */
    public EnumFieldBuilder invalidValueMessage(String text) {
        this.invalidValueMessage = text;
        return this;
    }

    /**
     * The stored-token <b>S-value</b> (DM-JSON {@code sValue}) — the model-owned default an eligible empty
     * INDEX field receives during full validation (SPEC-2026-07-23-14). Must name one of the declared
     * {@link #value(String) values} (checked at build — the kernel stages it into evaluation unvalidated).
     */
    public EnumFieldBuilder sValue(String storedToken) {
        this.sValue = storedToken;
        return this;
    }

    protected FieldTypeSpec spec() {
        if (values.isEmpty()) {
            throw new IllegalStateException("an enumeration field needs at least one value");
        }
        return new FieldTypeSpec.EnumSpec(values, categories, alphabeticalSorting, invalidValueMessage,
                Map.of(), sValue);
    }
}
