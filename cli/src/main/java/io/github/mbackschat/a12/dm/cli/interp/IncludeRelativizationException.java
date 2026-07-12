package io.github.mbackschat.a12.dm.cli.interp;

import java.util.List;

/**
 * The kernel-free interpreter eval path (decoupling Stage 2) mounted an <b>included</b> model's own
 * rule/computation whose condition uses a <b>rare reference shape</b> the kernel-free assembly layer does not
 * yet relativize — a MODEL_ROOT-absolute ref, a bare {@code /RG} group reference, or an outer-correlated
 * {@code [$/…]} absolute ref (mounting drops the included root-group name; INTERPRETER-FINDINGS IF122, the G1′
 * accepted caveat). Relative and single-root-group-absolute included conditions ARE relativized and evaluate on
 * the default path; only these rare shapes refuse loudly with {@code RK_INTERPRETER_INCLUDE_RULES_UNSUPPORTED}
 * rather than risk a silent-wrong-result.
 */
public final class IncludeRelativizationException extends RuntimeException {

    private final transient List<String> rules;

    IncludeRelativizationException(List<String> rules) {
        super("cannot evaluate " + rules.size() + " rule(s)/computation(s) from an included model on the "
                + "default path — a rare reference shape needs relativization the assembly layer does not yet "
                + "perform: " + rules);
        this.rules = List.copyOf(rules);
    }

    /** The names of the mounted included rules/computations that would need relativization. */
    public List<String> rules() {
        return rules;
    }
}
