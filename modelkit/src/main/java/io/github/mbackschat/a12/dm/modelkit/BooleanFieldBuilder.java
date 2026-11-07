package io.github.mbackschat.a12.dm.modelkit;

/**
 * Configures a boolean-valued field — the three-state {@code Boolean} or the two-state {@code Confirm}.
 *
 * <p>One builder for both kinds because they take the same two members; which kind is being authored is the
 * caller's choice of {@code booleanField} or {@code confirmField}, not a setting here. The two differ in
 * their EVALUATION (an unspecified Confirm reads as False, an unspecified Boolean is unknown, §1), and that
 * is not a property a field declaration configures.
 *
 * <p>The custom values are A12's {@code notInDCustomTrueValue} / {@code notInDCustomFalseValue}: a model's own
 * representation of true and of false where it differs from the defaults. The model keeps them, but validation
 * never consults them, so a stored value is still the literal {@code true}/{@code false} (KERNEL-FINDINGS KF273,
 * measured). They are set independently — a model may override one and leave the other — so neither implies the
 * other, and leaving both unset writes neither.
 */
public final class BooleanFieldBuilder extends FieldAttrs<BooleanFieldBuilder> {
    private final FieldKind kind;
    private String trueValue;
    private String falseValue;

    BooleanFieldBuilder(FieldKind kind) {
        this.kind = kind;
    }

    /** The model's representation of TRUE, where it is not A12's default; validation does not consult it. */
    public BooleanFieldBuilder trueValue(String stored) {
        this.trueValue = stored;
        return this;
    }

    /** The model's representation of FALSE, where it is not A12's default; validation does not consult it. */
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
