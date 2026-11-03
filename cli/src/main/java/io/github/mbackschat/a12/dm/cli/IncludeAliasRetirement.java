package io.github.mbackschat.a12.dm.cli;

import java.util.Optional;

/**
 * The correctives for the include alias, which DM 29.4 removed (A12K-4102, KERNEL-FINDINGS KF651): a mount names
 * the included model itself in {@code includeConfig.reference}, so the referenced model id is the include's only
 * identity and there is no alias to pass or to rename. A caller still using the DM 28 vocabulary gets told the
 * replacement instead of a bare unknown-option or unknown-op error (owner decision D3, KERNEL-31-UPGRADE-PROPOSAL).
 */
final class IncludeAliasRetirement {

    private static final String NO_ALIAS = "DM 29.4 has no include alias: a mount names the included model itself, "
            + "so the referenced model id is the include's identity";

    private IncludeAliasRetirement() {}

    /** The apply corrective for an {@code alias} arg on an include op. */
    static Optional<String> fix(String target, String arg) {
        return "include".equals(target) && "alias".equals(arg)
                ? Optional.of(NO_ALIAS + " — pass it as `reference`")
                : Optional.empty();
    }

    /** The apply corrective for the retired {@code include rename} op. */
    static Optional<String> opFix(String target, String op) {
        return "include".equals(target) && "rename".equals(op)
                ? Optional.of(renameFix("an `include set-reference` op with {reference, to}"))
                : Optional.empty();
    }

    /** The parse-error hint for {@code --alias} on an include verb, or for the retired {@code include rename}. */
    static Optional<String> parseHint(String qualifiedCommand, Iterable<String> unmatched) {
        boolean includeGroup = qualifiedCommand.endsWith(" include");
        if (!includeGroup && !qualifiedCommand.contains(" include ")) {
            return Optional.empty();
        }
        for (String arg : unmatched) {
            String flag = arg.split("=", 2)[0];
            if ("--alias".equals(flag)) {
                return Optional.of("→ " + NO_ALIAS + " — pass it as --reference <model id> (or positionally).");
            }
            if (includeGroup && "rename".equals(arg)) {
                return Optional.of("→ " + renameFix("`include set-reference <model id> --to <new model id>`"));
            }
        }
        return Optional.empty();
    }

    private static String renameFix(String replacement) {
        return "Renaming an include is retired: " + NO_ALIAS + ", so there is nothing to rename; re-point every mount "
                + "at another model with " + replacement;
    }
}
