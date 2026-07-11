package io.github.mbackschat.a12.dm.cli.interp;

import java.util.List;

/**
 * The kernel-free interpreter eval path (decoupling Stage 2 / A4) mounted an <b>included</b> model's own
 * rules/computations, whose conditions the kernel would relativize (the included root-group name is dropped on
 * mount) but the kernel-free assembly layer does not yet rewrite (INTERPRETER-GAPS IG67). Rather than feed
 * possibly-mis-resolved conditions to the engine — a silent-wrong-result — the eval verbs refuse loudly with
 * {@code RK_INTERPRETER_INCLUDE_RULES_UNSUPPORTED}. The {@code --kernel} path (JVM) is unaffected.
 */
public final class IncludeRelativizationException extends RuntimeException {

    private final transient List<String> rules;

    IncludeRelativizationException(List<String> rules) {
        super("cannot evaluate " + rules.size() + " rule(s)/computation(s) from an included model on the "
                + "default (interpreter) path without relativization: " + rules);
        this.rules = List.copyOf(rules);
    }

    /** The names of the mounted included rules/computations that would need relativization. */
    public List<String> rules() {
        return rules;
    }
}
