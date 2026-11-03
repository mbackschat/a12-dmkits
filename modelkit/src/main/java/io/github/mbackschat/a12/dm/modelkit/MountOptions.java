package io.github.mbackschat.a12.dm.modelkit;

/**
 * How a model include is mounted (FEATURE-GAPS): its {@code repeatability} (1 = single, &gt; 1 a
 * repeating sub-form), whether to exclude the <em>included</em> model's own rules / computations when the
 * kernel expands the mount, and the {@link IncludeLevel} (SINGLE_RG = the include becomes one group;
 * MODEL_ROOT = its root becomes the mount — DG6/A12K-3234). All three live in the mount group's
 * {@code includeConfig} beside its {@code reference} (A12K-4102, KF651), so they exist only on a mount.
 *
 * <p>An immutable value object built by fluent peeling so call sites read as prose and there is no
 * ambiguous argument list: {@code MountOptions.single().excludingRules()},
 * {@code MountOptions.repeatable(3).asModelRoot()}.
 */
public record MountOptions(int repeatability, boolean excludeRules, boolean excludeComputations,
        IncludeLevel includeLevel) {

    public MountOptions {
        if (repeatability < 1) {
            throw new IllegalArgumentException("mount repeatability must be >= 1, was " + repeatability);
        }
        includeLevel = includeLevel == null ? IncludeLevel.SINGLE_RG : includeLevel;
    }

    /** A single (non-repeatable) mount, including the model's rules and computations. */
    public static MountOptions single() {
        return new MountOptions(1, false, false, IncludeLevel.SINGLE_RG);
    }

    /** A repeatable mount of up to {@code rows} rows, including the model's rules and computations. */
    public static MountOptions repeatable(int rows) {
        return new MountOptions(rows, false, false, IncludeLevel.SINGLE_RG);
    }

    /** This option set, but excluding the included model's rules on expand. */
    public MountOptions excludingRules() {
        return new MountOptions(repeatability, true, excludeComputations, includeLevel);
    }

    /** This option set, but excluding the included model's computations on expand. */
    public MountOptions excludingComputations() {
        return new MountOptions(repeatability, excludeRules, true, includeLevel);
    }

    /** This option set, but mounted at {@link IncludeLevel#MODEL_ROOT} (the included root becomes the mount). */
    public MountOptions asModelRoot() {
        return new MountOptions(repeatability, excludeRules, excludeComputations, IncludeLevel.MODEL_ROOT);
    }
}
