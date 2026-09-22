package io.github.mbackschat.a12.dm.rulekit.dsl;

import io.github.mbackschat.a12.dm.core.ValueKind;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;

/**
 * A rich, read-only descriptor of one field — the navigation surface's currency (study finding 9,
 * jOOQ-{@code Meta}-style). Exploring is lenient (obtained from streams or the non-throwing
 * {@link BoundModel#fieldAt}); <em>narrowing</em> back to a typed authoring handle
 * ({@link #asNum()}, {@link #asStr()}, …) is strict and keeps every authoring guarantee — it is
 * exactly the corresponding {@link BoundModel} lookup.
 */
public final class FieldInfo {

    private final BoundModel model;
    private final String path;
    private final String name;
    private final ValueKind kind;

    FieldInfo(BoundModel model, String path, String name, ValueKind kind) {
        this.model = model;
        this.path = path;
        this.name = name;
        this.kind = kind;
    }

    public String path() {
        return path;
    }

    /** The short name (last segment, as modeled). */
    public String name() {
        return name;
    }

    public ValueKind kind() {
        return kind;
    }

    /**
     * The temporal class this field's <b>declaration</b> puts it in — {@link ValueKind#DATE},
     * {@link ValueKind#TIME} or {@link ValueKind#DATE_TIME} read off {@link #format()} for a DATE/TIME/DATE_TIME
     * field, and the field's own {@link #kind()} for everything else (a DATE_FRAGMENT stays one; so does a
     * NUMBER). This is the question every gate below the declaration actually asks: A12's temporal format
     * vocabulary is kind-independent, so a TIME field declared {@code yyyy-MM-dd} carries the Gregorian floor,
     * compares against a DATE and constructs a DATE_RANGE endpoint, while its own kind's clock operators refuse
     * it (KERNEL-FINDINGS KF211, measured on both codegen strategies).
     *
     * <p><b>For DISPATCH, never for refusal.</b> Choosing which typed handle to build is exactly what this
     * answers; refusing a field because its class disagrees with its kind would be a client-side refusal with
     * no measured kernel basis — which is why {@link BoundModel#date} still admits a kind-matching field whose
     * declared format disagrees, and why the kernel's own gate remains the arbiter of anything below.
     *
     * <p>One definition, both directions: the adapter's read path decides which handle a kernel operand becomes
     * and had kept this keyed on the kind at four sites, each of which turned a kernel-admitted condition into
     * an {@code Opaque} rule. A second copy of the letter scanning is the smell that fix exists to avoid.
     */
    public ValueKind temporalClass() {
        if (!TemporalFormats.isTemporal(kind)) {
            return kind;
        }
        String declared = format().orElseGet(() -> TemporalFormats.defaultFormat(kind));
        return switch (TemporalFormats.shapeOf(declared)) {
            case DATE -> ValueKind.DATE;
            case TIME -> ValueKind.TIME;
            case DATE_TIME -> ValueKind.DATE_TIME;
        };
    }

    /** The containing group's path (every field has one; root groups contain the top-level fields). */
    public String groupPath() {
        return path.substring(0, path.lastIndexOf('/'));
    }

    /** The containing group as a typed handle. */
    public GroupRef group() {
        return model.group(groupPath());
    }

    /** Declared decimal scale; only meaningful when {@link #kind()} is NUMBER (throws otherwise). */
    public int scale() {
        return model.num(path).scale();
    }

    // --- declared field config (read-only): the value constraints the kernel carries on the field type.
    // Each is empty when the field's kind doesn't carry it OR the constraint is unset, so any field can be
    // probed without a kind check — the read peer of the modelkit builder's min/max/minLength/maxLength/pattern.

    /** Declared inclusive lower bound ({@code minimum}); empty when not a number or unset. */
    public Optional<BigDecimal> numberMin() {
        return model.index().numberMin(path);
    }

    /** Declared inclusive upper bound ({@code maximum}); empty when not a number or unset. */
    public Optional<BigDecimal> numberMax() {
        return model.index().numberMax(path);
    }

    /** Declared minimum string length; empty when not a string or unset. */
    public OptionalInt minLength() {
        return model.index().stringMinLength(path);
    }

    /** Declared maximum string length; empty when not a string or unset. */
    public OptionalInt maxLength() {
        return model.index().stringMaxLength(path);
    }

    /** Declared regex pattern; empty when not a string or no pattern. */
    public Optional<String> pattern() {
        return model.index().stringPattern(path);
    }

    /**
     * The declared temporal {@code format} (e.g. {@code yyyy-MM-dd}, {@code HH:mm:ss}); empty when the field
     * declares none or is not temporal. This string, not the kind, is what decides how A12 classifies the
     * cell's values and which operands accept it (KERNEL-FINDINGS KF211) — which is why the typed date/time
     * handles below are obtained by it and not only by kind.
     */
    public Optional<String> format() {
        return model.index().temporalFormat(path);
    }

    /**
     * The enumeration's allowed stored values in authored order. This is the values-only read surface:
     * it does not materialize category projections.
     *
     * @throws IllegalArgumentException if this is not an enumeration field
     */
    public List<String> allowedValues() {
        return model.index().enumValues(path);
    }

    // --- annotations: the read peer of EditSession.setAnnotation (study finding 9, annotation reads) --

    /** This field's element-level annotations, in document order; empty (never {@code null}) when none. */
    public List<Annotation> annotations() {
        return model.annotationsOf(path);
    }

    /** The annotation named {@code name}, if present — the matcher the "find fields by annotation" use case needs. */
    public Optional<Annotation> annotation(String name) {
        return annotations().stream().filter(a -> a.name().equals(name)).findFirst();
    }

    // --- narrowing: lenient exploring → strict authoring (throws on kind mismatch) -------------

    public FieldRef ref() {
        return model.field(path);
    }

    public NumberRef asNum() {
        return model.num(path);
    }

    public StringRef asStr() {
        return model.str(path);
    }

    public EnumRef asEnum() {
        return model.enumOf(path);
    }

    public BoolRef asBool() {
        return model.bool(path);
    }

    public ConfirmRef asConfirm() {
        return model.confirm(path);
    }

    @Override
    public String toString() {
        return path + " (" + kind + ")";
    }
}
