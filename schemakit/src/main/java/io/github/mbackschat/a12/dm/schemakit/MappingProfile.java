package io.github.mbackschat.a12.dm.schemakit;

/**
 * The customizable import decisions (SCHEMAKIT-SPEC §4b) — a value object of named, defaulted strategies for
 * the choice-driven mappings. Each strategy is an enum so illegal states are unrepresentable.
 *
 * <p><b>The default is best-effort: import as much as possible, flag every guess.</b> {@link #defaults()}
 * maximizes coverage — a bare {@code number} gets a default scale, a scalar array becomes a repeatable group,
 * an unbounded array/map gets a fallback row cap, a recursive {@code $ref} expands one level. This matters
 * most for <em>structural containers</em>: omitting an array/map would prune its entire nested subtree, so the
 * default reaches in and recurses. None of it is silent — every assumption rides the transcoding report.
 * {@link #strict()} is the opposite stance (omit everything uncertain) for the interop purist; a consumer
 * overrides any single decision via {@code with…} (CLI flags build one over {@code defaults()}/{@code strict()}).
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
        int recursionDepth,
        int defaultRepeatCap) {

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

    /**
     * Where a foreign enum value's label comes from, JSON Schema having no label keyword: the value itself, the
     * {@code x-enumNames} display-name array beside {@code enum}, or none. An a12 export's
     * {@code x-a12-enum-labels} carriage wins over every strategy.
     */
    public enum EnumLabels { DERIVE, X_ENUM_NAMES, BLANK }

    /** Best-effort defaults — maximize coverage, flag every guess in the report (SCHEMAKIT-SPEC §4b). */
    public static MappingProfile defaults() {
        return new MappingProfile(
                NumberScale.DEFAULT_SCALE, 2,
                ArrayOfScalar.REPEATABLE_SINGLE,
                MapStrategy.REPEATABLE_KEY_VALUE,
                UnionStrategy.SUPERSET_RULE,
                FormatStrategy.PATTERN,
                BooleanKind.BOOLEAN,
                EnumLabels.DERIVE,
                1,        // recursive $ref expands one level by default
                1000);    // an array/map with no maxItems/maxProperties is capped at 1000 rows (a chosen bound)
    }

    /** Conservative: omit everything uncertain — only directly-mapping structure + exact rules import. */
    public static MappingProfile strict() {
        return new MappingProfile(
                NumberScale.OMIT, 2,
                ArrayOfScalar.OMIT,
                MapStrategy.OMIT,
                UnionStrategy.OMIT,
                FormatStrategy.IGNORE,
                BooleanKind.BOOLEAN,
                EnumLabels.DERIVE,
                0,    // no recursive expansion
                0);   // no unbounded-container cap → omit
    }

    public MappingProfile withNumberScale(NumberScale scale, int defaultScale) {
        return new MappingProfile(scale, defaultScale, arrayOfScalar, mapStrategy, unionStrategy,
                format, booleanKind, enumLabels, recursionDepth, defaultRepeatCap);
    }

    public MappingProfile withBooleanKind(BooleanKind kind) {
        return new MappingProfile(numberScale, defaultScale, arrayOfScalar, mapStrategy, unionStrategy,
                format, kind, enumLabels, recursionDepth, defaultRepeatCap);
    }

    public MappingProfile withArrayOfScalar(ArrayOfScalar strategy) {
        return new MappingProfile(numberScale, defaultScale, strategy, mapStrategy, unionStrategy,
                format, booleanKind, enumLabels, recursionDepth, defaultRepeatCap);
    }

    public MappingProfile withMapStrategy(MapStrategy strategy) {
        return new MappingProfile(numberScale, defaultScale, arrayOfScalar, strategy, unionStrategy,
                format, booleanKind, enumLabels, recursionDepth, defaultRepeatCap);
    }

    public MappingProfile withUnionStrategy(UnionStrategy strategy) {
        return new MappingProfile(numberScale, defaultScale, arrayOfScalar, mapStrategy, strategy,
                format, booleanKind, enumLabels, recursionDepth, defaultRepeatCap);
    }

    public MappingProfile withFormat(FormatStrategy strategy) {
        return new MappingProfile(numberScale, defaultScale, arrayOfScalar, mapStrategy, unionStrategy,
                strategy, booleanKind, enumLabels, recursionDepth, defaultRepeatCap);
    }

    public MappingProfile withEnumLabels(EnumLabels strategy) {
        return new MappingProfile(numberScale, defaultScale, arrayOfScalar, mapStrategy, unionStrategy,
                format, booleanKind, strategy, recursionDepth, defaultRepeatCap);
    }

    public MappingProfile withRecursionDepth(int depth) {
        return new MappingProfile(numberScale, defaultScale, arrayOfScalar, mapStrategy, unionStrategy,
                format, booleanKind, enumLabels, depth, defaultRepeatCap);
    }

    public MappingProfile withDefaultRepeatCap(int cap) {
        return new MappingProfile(numberScale, defaultScale, arrayOfScalar, mapStrategy, unionStrategy,
                format, booleanKind, enumLabels, recursionDepth, cap);
    }
}
