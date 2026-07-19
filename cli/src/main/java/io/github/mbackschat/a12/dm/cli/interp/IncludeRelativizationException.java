package io.github.mbackschat.a12.dm.cli.interp;

import java.util.List;

/**
 * The kernel-free interpreter eval path mounted an <b>included</b> model's own rule/computation whose condition
 * carries an absolute reference the assembly layer <b>cannot remap</b> onto its post-mount location — a first
 * path segment naming no mounted top-group (a genuinely-unresolvable absolute; INTERPRETER-FINDINGS IF146). A
 * mount is remapped as a MOVE, so descending, bare {@code /RG} group, MODEL_ROOT, and {@code $}-outer included
 * references DO evaluate on the default path; only an unmappable absolute refuses loudly with
 * {@code RK_INTERPRETER_INCLUDE_RULES_UNSUPPORTED} rather than risk a silent-wrong-result.
 */
public final class IncludeRelativizationException extends RuntimeException {

    private final transient List<String> rules;

    IncludeRelativizationException(List<String> rules) {
        super("cannot evaluate " + rules.size() + " rule(s)/computation(s) from an included model on the "
                + "default path — an absolute reference the assembly layer cannot remap onto its post-mount "
                + "location (its first segment names no mounted group): " + rules);
        this.rules = List.copyOf(rules);
    }

    /** The names of the mounted included rules/computations that would need relativization. */
    public List<String> rules() {
        return rules;
    }
}
