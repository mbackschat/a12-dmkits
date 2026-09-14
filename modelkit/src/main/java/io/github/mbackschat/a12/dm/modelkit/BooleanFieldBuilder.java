package io.github.mbackschat.a12.dm.modelkit;

/**
 * Configures a boolean-valued field — the three-state {@code Boolean} or the two-state {@code Confirm}.
 *
 * <p>One builder for both kinds because they take the same two members; which kind is being authored is the
 * caller's choice of {@code booleanField} or {@code confirmField}, not a setting here. The two differ in
 * their EVALUATION (an unspecified Confirm reads as False, an unspecified Boolean is unknown, §1), and that
 * is not a property a field declaration configures.
 *
 * <p>The custom values are A12's {@code notInDCustomTrueValue} / {@code notInDCustomFalseValue}: the text a
 * model stores for true and for false when they are not the defaults. They are set independently — a model
 * may override one and leave the other — so neither implies the other, and leaving both unset writes neither.
 */
public final class BooleanFieldBuilder extends FieldAttrs<BooleanFieldBuilder> {
    private final FieldKind kind;
    private String trueValue;
    private String falseValue;

    BooleanFieldBuilder(FieldKind kind) {
        this.kind = kind;
    }

    /** The text this model stores for TRUE, where it is not A12's default. */
    public BooleanFieldBuilder trueValue(String stored) {
        this.trueValue = stored;
        return this;
    }

    /** The text this model stores for FALSE, where it is not A12's default. */
    public BooleanFieldBuilder falseValue(String stored) {
        this.falseValue = stored;
        return this;
    }

    protected FieldTypeSpec spec() {
        return kind == FieldKind.CONFIRM
                ? new FieldTypeSpec.ConfirmSpec(trueValue, falseValue)
                : new FieldTypeSpec.BooleanSpec(trueValue, falseValue);
    }
}
