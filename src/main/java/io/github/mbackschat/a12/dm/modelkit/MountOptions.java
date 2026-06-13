package io.github.mbackschat.a12.dm.modelkit;

/**
 * How a model include is mounted (FEATURE-GAPS §2/§3.9): its {@code repeatability} (1 = single, &gt; 1 a
 * repeating sub-form) and whether to exclude the <em>included</em> model's own rules / computations when
 * the kernel expands the mount. The kernel ties both exclusions to a mount — {@code excludeRules} /
 * {@code excludeComputations} are valid only where {@code modelAlias} is set (metamodel rule
 * {@code EXCLUDE_RULES_INVALID}), which a mount always is.
 *
 * <p>An immutable value object built by fluent peeling so call sites read as prose and there is no
 * ambiguous boolean-pair argument: {@code MountOptions.single().excludingRules()},
 * {@code MountOptions.repeatable(3).excludingComputations()}.
 */
public record MountOptions(int repeatability, boolean excludeRules, boolean excludeComputations) {

    public MountOptions {
        if (repeatability < 1) {
            throw new IllegalArgumentException("mount repeatability must be >= 1, was " + repeatability);
        }
    }

    /** A single (non-repeatable) mount, including the model's rules and computations. */
    public static MountOptions single() {
        return new MountOptions(1, false, false);
    }

    /** A repeatable mount of up to {@code rows} rows, including the model's rules and computations. */
    public static MountOptions repeatable(int rows) {
        return new MountOptions(rows, false, false);
    }

    /** This option set, but excluding the included model's rules on expand. */
    public MountOptions excludingRules() {
        return new MountOptions(repeatability, true, excludeComputations);
    }

    /** This option set, but excluding the included model's computations on expand. */
    public MountOptions excludingComputations() {
        return new MountOptions(repeatability, excludeRules, true);
    }
}
