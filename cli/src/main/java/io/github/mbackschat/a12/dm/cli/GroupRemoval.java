package io.github.mbackschat.a12.dm.cli;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

import io.github.mbackschat.a12.dm.adapter.KernelAdapter;

/**
 * The safe-delete cascade computation for {@code group remove} — shared by the standalone
 * {@code group remove} verb and the {@code apply} {@code group remove} op so the two stay in lockstep
 * (G12, the apply ↔ standalone op-set parity). The §6 gate: a group referenced by a rule/computation
 * <b>outside</b> its subtree is refused unless {@code cascade}; referrers <b>inside</b> the subtree
 * (a composite group's own internal rules) go away with the group and never gate. Callers own the actual
 * {@code removeGroup}; on cascade they first remove the {@link Plan#cascadeRules()}/
 * {@link Plan#cascadeComputations()} this returns.
 *
 * <p>"Referenced" here means the group <em>node itself</em> is named (e.g. {@code GroupFilled(/G)}) —
 * a rule referencing a descendant <em>field</em> is not surfaced by {@link KernelAdapter#whereUsed};
 * removing the group then dangles that field, which the terminal kernel gate rejects.
 */
public final class GroupRemoval {

    private GroupRemoval() {
    }

    /** Either a {@link Refusal} (referenced, no cascade) or the external referrers to cascade-remove. */
    public record Plan(Optional<Refusal> refusal, List<String> cascadeRules, List<String> cascadeComputations) {

        public boolean refused() {
            return refusal.isPresent();
        }
    }

    /**
     * Compute the removal plan for {@code group}: its external referrers are rules/computations that
     * reference the group node and live outside its subtree. If any exist and {@code !cascade}, the plan
     * carries a {@code RK_REFERENCED} {@link Refusal} (with {@code fixHint}); with {@code cascade} it carries
     * those referrers for the caller to remove. No external referrers → an empty, non-refused plan.
     */
    public static Plan plan(KernelAdapter adapter, String group, boolean cascade, String fixHint) {
        // REACHABILITY, not exactness: a rule naming an ANCESTOR group reaches into this one exactly as a rule
        // naming this group reaches into its fields, and removing the group then narrows that rule's expansion
        // while leaving the model kernel-valid — so nothing downstream objects (CG13).
        Set<String> reaching = KernelAdapter.referrersReaching(adapter.referrersByEntity(), group);
        Set<String> rules = new TreeSet<>(adapter.ruleFullNames());
        rules.retainAll(reaching);
        Set<String> computations = new TreeSet<>(reaching);
        computations.removeAll(rules);
        String subtree = group + "/";
        rules.removeIf(r -> r.startsWith(subtree));
        computations.removeIf(c -> c.startsWith(subtree));
        if (rules.isEmpty() && computations.isEmpty()) {
            return new Plan(Optional.empty(), List.of(), List.of());
        }
        if (!cascade) {
            return new Plan(Optional.of(SafetyChecks.referencedBy(group, rules, computations, fixHint)),
                    List.of(), List.of());
        }
        return new Plan(Optional.empty(), List.copyOf(rules), List.copyOf(computations));
    }
}
