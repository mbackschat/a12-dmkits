package io.github.mbackschat.a12.dm.cli;

import java.util.Map;
import java.util.Optional;
import java.util.Set;

import io.github.mbackschat.a12.dm.rulekit.validate.Diagnostic;
import io.github.mbackschat.a12.dm.rulekit.validate.RkCode;

/** G45 migration correctives for metadata keys/flags removed from target-specific modifiers. */
final class MetadataRelocation {

    private static final Set<String> TARGETS = Set.of("field", "group", "rule");
    private static final Map<String, String> CANONICAL_FLAG = Map.ofEntries(
            Map.entry("label", "--label"),
            Map.entry("annotation", "--annotation"),
            Map.entry("annotations", "--annotation"),
            Map.entry("remove-annotation", "--remove-annotation"),
            Map.entry("internal-description", "--internal-description"),
            Map.entry("internalDescription", "--internal-description"),
            Map.entry("external-description", "--external-description"),
            Map.entry("externalDescription", "--external-description"),
            Map.entry("remove-internal-description", "--remove-internal-description"),
            Map.entry("remove-external-description", "--remove-external-description"));

    private MetadataRelocation() {
    }

    /** A direct replacement for an old target-specific JSON key, or empty for an ordinary unknown key. */
    static Optional<String> fix(String target, String op, String key) {
        if (!TARGETS.contains(target) || !"modify".equals(op)) {
            return Optional.empty();
        }
        String flag = CANONICAL_FLAG.get(key);
        return flag == null ? Optional.empty() : Optional.of(movedFix(flag));
    }

    /** Replace a generic unknown-key suggestion with the canonical metadata route where applicable. */
    static Diagnostic correct(String target, String op, Diagnostic diagnostic) {
        if (diagnostic.code().filter(RkCode.UNKNOWN_ARG.code()::equals).isEmpty()) {
            return diagnostic;
        }
        String key = diagnostic.where().arg().orElse("");
        return fix(target, op, key)
                .map(fix -> new Diagnostic(diagnostic.severity(), diagnostic.source(), diagnostic.code(),
                        diagnostic.summary(), diagnostic.where(), Optional.of(fix), diagnostic.explain()))
                .orElse(diagnostic);
    }

    /** A parse-error hint for a removed target-specific metadata flag. */
    static Optional<String> flagHint(String qualifiedCommand, String flag) {
        if (!(qualifiedCommand.endsWith(" group modify") || qualifiedCommand.endsWith(" rule modify"))) {
            return Optional.empty();
        }
        String canonical = CANONICAL_FLAG.get(flag.startsWith("--") ? flag.substring(2) : flag);
        return canonical == null ? Optional.empty()
                : Optional.of("→ " + flag + " " + movedFix(canonical));
    }

    private static String movedFix(String flag) {
        return "moved to `dmtool meta <ref> " + flag
                + "`; pass the element's path (or typedef id) as <ref>";
    }
}
