package io.github.mbackschat.a12.dm.cli;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

import io.github.mbackschat.a12.dm.adapter.KernelAdapter;

/**
 * The safe-delete cascade computation for {@code field remove} — the peer of {@link GroupRemoval}, shared by the
 * standalone {@code field remove} verb and the {@code apply} {@code field remove} op.
 *
 * <p><b>Why it exists as a shared class rather than an inline check.</b> The gate lived inline in
 * {@code FieldRemoveCommand} while {@code apply}'s {@code FIELD_REMOVE} had none at all, so the batch route
 * skipped CLI-SPEC §6 entirely ({@code CG13}). Two routes to one operation with the safety logic in only one of
 * them is how that drift happened; {@link GroupRemoval}'s javadoc already states the same reason for the group
 * pair, and this class closes the field half.
 *
 * <p><b>"Referenced" is REACHABILITY, and that is the whole substance of the gate.</b> An exact
 * {@code whereUsed} sees only a rule that NAMES the field. A rule naming an enclosing GROUP reads it too — the
 * kernel expands a group operand recursively to every field below it — and that case is the dangerous one,
 * because it is the one the terminal kernel check cannot catch:
 *
 * <ul>
 *   <li>Remove a field a rule names DIRECTLY: the rule dangles, so the terminal kernel gate rejects the write.
 *       Ungated, that surfaces as a kernel-shaped rejection instead of a corrective {@code RK_REFERENCED} —
 *       poor diagnostics, but the model survives.</li>
 *   <li>Remove a field reached only through {@code AllFieldsFilled(/Order/ShippingAddress)}: the rule still
 *       names a group that still exists, so the model stays <b>kernel-valid</b>. Measured before the fix, the
 *       tool reported {@code outcome: applied}, {@code verification: KERNEL_CONFIRMED}, no diagnostics — the
 *       field gone and the rule silently checking one fewer field. Nothing in the pipeline said no.</li>
 * </ul>
 *
 * <p>Hence {@link KernelAdapter#referrersReaching}, and hence a refusal that names the GROUP responsible: an
 * author told only "referenced" would look for a rule mentioning a field no rule mentions.
 */
public final class FieldRemoval {

    private FieldRemoval() {
    }

    /** Either a {@link Refusal} (reached, no cascade) or the referrers to cascade-remove. */
    public record Plan(Optional<Refusal> refusal, List<String> cascadeRules, List<String> cascadeComputations) {

        public boolean refused() {
            return refusal.isPresent();
        }
    }

    /**
     * Compute the removal plan for {@code field}: every rule and computation that READS it, directly or through
     * an ancestor group. If any exist and {@code !cascade}, the plan carries an {@code RK_REFERENCED}
     * {@link Refusal}; with {@code cascade} it carries them for the caller to remove.
     */
    public static Plan plan(KernelAdapter adapter, String field, boolean cascade, String fixHint) {
        var byEntity = adapter.referrersByEntity();
        Set<String> reaching = new TreeSet<>(KernelAdapter.referrersReaching(byEntity, field));
        Set<String> criteria = new TreeSet<>(adapter.uniquenessCriteria().within(field));
        if (reaching.isEmpty() && criteria.isEmpty()) {
            return new Plan(Optional.empty(), List.of(), List.of());
        }
        Set<String> rules = new TreeSet<>(adapter.ruleFullNames());
        rules.retainAll(reaching);
        Set<String> computations = new TreeSet<>(reaching);
        computations.removeAll(rules);
        // a criterion refuses even under --cascade, which removes rules and computations only (KF671)
        if (!cascade || !criteria.isEmpty()) {
            return new Plan(Optional.of(SafetyChecks.referencedBy(field, rules, computations, criteria,
                    reachHint(byEntity, field, fixHint))), List.of(), List.of());
        }
        return new Plan(Optional.empty(), List.copyOf(rules), List.copyOf(computations));
    }

    /**
     * The fix hint, extended with the ancestor GROUP that makes the field reachable when no rule names the field
     * itself. Without it the refusal is unactionable in exactly the case it exists for: the author searches for a
     * rule mentioning a field that no rule mentions.
     */
    private static String reachHint(java.util.Map<String, Set<String>> byEntity, String field, String base) {
        if (!byEntity.getOrDefault(field, Set.of()).isEmpty()) {
            return base;
        }
        for (int cut = field.lastIndexOf('/'); cut > 0; cut = field.lastIndexOf('/', cut - 1)) {
            String ancestor = field.substring(0, cut);
            if (!byEntity.getOrDefault(ancestor, Set.of()).isEmpty()) {
                return "no rule names this field directly — it is read through the group " + ancestor
                        + ", which a group operand expands to every field below it. " + base;
            }
        }
        return base;
    }
}
