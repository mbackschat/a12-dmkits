package io.github.mbackschat.a12.dm.schemakit.spike;

/**
 * The customizable import decisions (SCHEMAKIT-SPEC §4b; proposal §5/§9) — a value object of named,
 * defaulted strategies for the choice-driven mappings. {@link #defaults()} carries the agreed defaults;
 * a consumer overrides any single decision via {@code with…}. (CLI flags build one.) Each strategy is an
 * enum so illegal states are unrepresentable.
 *
 * <p>Decisions consulted today: {@link #numberScale}, {@link #booleanKind}, {@link #arrayOfScalar},
 * {@link #mapStrategy}. The remaining structural strategies ({@link #unionStrategy}, {@link #format},
 * {@link #recursionDepth}) are declared here and wired by their own increments (tasks #4–#6/#10).
 */
public record MappingProfile(
        NumberScale numberScale,
        int defaultScale,
        ArrayOfScalar arrayOfScalar,
        MapStrategy mapStrategy,
        UnionStrategy unionStrategy,
        FormatStrategy format,
        BooleanKind booleanKind,
        EnumLabels enumLabels,
        int recursionDepth) {

    /** A bare {@code number} (no {@code multipleOf}) has no faithful a12 scale. */
    public enum NumberScale { OMIT, INTEGER, DEFAULT_SCALE }

    /** An {@code array} of scalar items. */
    public enum ArrayOfScalar { OMIT, MULTI_SELECT, VALUE_LIST, REPEATABLE_SINGLE }

    /** An object used as a map ({@code additionalProperties}/{@code patternProperties}). */
    public enum MapStrategy { OMIT, REPEATABLE_KEY_VALUE, IGNORE }

    /** {@code oneOf}/{@code anyOf} of same-kind object shapes. */
    public enum UnionStrategy { SUPERSET_RULE, FIRST_VARIANT, OMIT }

    /** A string {@code format}. */
    public enum FormatStrategy { PATTERN, CUSTOM_TYPE, IGNORE }

    /** Three-state vs checkbox for a JSON {@code boolean}. */
    public enum BooleanKind { BOOLEAN, CONFIRM }

    /** Where an enum value's label comes from (JSON Schema enums carry no labels). */
    public enum EnumLabels { DERIVE, X_ENUM_NAMES, BLANK }

    /** The agreed defaults (SCHEMAKIT-SPEC §4b). */
    public static MappingProfile defaults() {
        return new MappingProfile(
                NumberScale.OMIT, 2,
                ArrayOfScalar.OMIT,
                MapStrategy.REPEATABLE_KEY_VALUE,
                UnionStrategy.SUPERSET_RULE,
                FormatStrategy.PATTERN,
                BooleanKind.BOOLEAN,
                EnumLabels.DERIVE,
                0);   // recursive $ref omitted by default; > 0 = bounded expansion
    }

    public MappingProfile withNumberScale(NumberScale scale, int defaultScale) {
        return new MappingProfile(scale, defaultScale, arrayOfScalar, mapStrategy, unionStrategy,
                format, booleanKind, enumLabels, recursionDepth);
    }

    public MappingProfile withBooleanKind(BooleanKind kind) {
        return new MappingProfile(numberScale, defaultScale, arrayOfScalar, mapStrategy, unionStrategy,
                format, kind, enumLabels, recursionDepth);
    }

    public MappingProfile withArrayOfScalar(ArrayOfScalar strategy) {
        return new MappingProfile(numberScale, defaultScale, strategy, mapStrategy, unionStrategy,
                format, booleanKind, enumLabels, recursionDepth);
    }

    public MappingProfile withMapStrategy(MapStrategy strategy) {
        return new MappingProfile(numberScale, defaultScale, arrayOfScalar, strategy, unionStrategy,
                format, booleanKind, enumLabels, recursionDepth);
    }
}
