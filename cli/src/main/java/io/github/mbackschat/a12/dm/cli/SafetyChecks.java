package io.github.mbackschat.a12.dm.cli;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import io.github.mbackschat.a12.dm.adapter.KernelAdapter;
import io.github.mbackschat.a12.dm.modelkit.ModelDocument;
import io.github.mbackschat.a12.dm.rulekit.validate.Diagnostic;

/**
 * Reusable safety preconditions for refactor ops (CLI-SPEC §6). Each refactor registers the checks
 * that fit its blast radius into a {@link SafetyGate}; new refactors add their own factories here.
 *
 * <p>The reference gate ({@link #referencedBy}) backs safe-delete: an element still referenced by a
 * rule/computation may not be removed (it would dangle the referrers) unless {@code --cascade}. Rename
 * does <em>not</em> use it — it rewrites references via {@code adapter.ReferenceRewriter} instead.
 */
final class SafetyChecks {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private SafetyChecks() {
    }

    /** Refuse a rename whose new path collides with an existing field or group (the collision gate). */
    static SafetyCheck pathAvailable(ModelDocument doc, String newPath) {
        return () -> doc.fieldPaths().contains(newPath) || doc.groupPaths().contains(newPath)
                ? Optional.of(nameExists(newPath, "field or group",
                        "choose a name not already used by a sibling, or rename/remove the existing one first"))
                : Optional.empty();
    }

    /**
     * Refuse a typedef rename when {@code newId} is already a declared type definition — a rename must not
     * collide with (and silently merge into) an existing one. The new-id check runs before any mutation.
     */
    static SafetyCheck typeDefIdAvailable(ModelDocument doc, String newId) {
        return () -> doc.typeDefinitionIds().contains(newId)
                ? Optional.of(nameExists(newId, "type definition",
                        "choose a new id that is not already declared, or remove the existing '" + newId + "' first"))
                : Optional.empty();
    }

    /**
     * Refuse a typedef extract when the {@code fieldPaths} do not all exist and share one concrete type —
     * there is no single type to factor out (and we never silently coerce). Runs before any mutation.
     */
    static SafetyCheck fieldsShareConcreteType(ModelDocument doc, List<String> fieldPaths) {
        return () -> {
            if (doc.fieldsShareConcreteType(fieldPaths)) {
                return Optional.empty();
            }
            Diagnostic diagnostic = Diagnostic.precheck(Optional.of("RK_TYPE_MISMATCH"),
                    "the fields to extract must all exist and share one concrete type: " + fieldPaths,
                    Diagnostic.Where.arg(null, "--from"),
                    Optional.of("pass --from fields that exist and have the same type (none already a typedef)"),
                    Optional.empty());
            return Optional.of(new Refusal("fields do not share one concrete type — refusing", null, diagnostic));
        };
    }

    /**
     * Refuse extracting a <b>repeatable</b> group as an include — the kernel requires an include's root
     * group to be single and non-repeatable (RK_REPEATABLE_INCLUDE_ROOT).
     */
    static SafetyCheck includableAsRoot(ModelDocument doc, String groupPath) {
        return () -> {
            if (!doc.isRepeating(groupPath)) {
                return Optional.empty();
            }
            Diagnostic diagnostic = Diagnostic.precheck(Optional.of("RK_REPEATABLE_INCLUDE_ROOT"),
                    groupPath + " is repeatable; an include's root group must be single and non-repeatable",
                    Diagnostic.Where.path(groupPath),
                    Optional.of("extract a non-repeatable group, or make this group non-repeatable first"),
                    Optional.empty());
            return Optional.of(new Refusal(groupPath + " is repeatable — cannot be an include root", null, diagnostic));
        };
    }

    /**
     * Refuse extracting a group whose subtree contains a rule referencing a field <b>outside</b> the
     * subtree — that rule can't move into a standalone sub-model (it wouldn't see the field). Rules whose
     * references stay inside travel fine. References pointing <em>into</em> the subtree from outside are
     * unaffected (the mount preserves the path), so they don't block. RK_OUTWARD_REFERENCE.
     */
    static SafetyCheck subtreeSelfContained(KernelAdapter adapter, String groupPath) {
        return () -> {
            String inside = groupPath + "/";
            Set<String> escaping = new TreeSet<>();
            for (String rule : adapter.ruleFullNames()) {
                if (!rule.startsWith(inside)) {
                    continue;   // only rules that would move into the sub-model
                }
                if (adapter.referencedFields(rule).stream().anyMatch(field -> !field.startsWith(inside))) {
                    escaping.add(rule);
                }
            }
            if (escaping.isEmpty()) {
                return Optional.empty();
            }
            ObjectNode data = MAPPER.createObjectNode();
            ArrayNode array = data.putArray("escapingRules");
            escaping.forEach(array::add);
            Diagnostic diagnostic = Diagnostic.precheck(Optional.of("RK_OUTWARD_REFERENCE"),
                    "the subtree has " + escaping.size() + " rule(s) referencing fields outside it; they cannot move into a standalone sub-model",
                    Diagnostic.Where.path(groupPath),
                    Optional.of("move those rules out of the subtree, or extract a larger group that contains what they reference"),
                    Optional.empty());
            return Optional.of(new Refusal(groupPath + " has outward references — refusing to extract", data, diagnostic));
        };
    }

    /** The RK_NAME_EXISTS refusal: a rename whose chosen new name/id is already taken (the collision gate). */
    static Refusal nameExists(String name, String kind, String fix) {
        Diagnostic diagnostic = Diagnostic.precheck(Optional.of("RK_NAME_EXISTS"),
                "a " + kind + " named '" + name + "' already exists",
                Diagnostic.Where.path(name), Optional.of(fix), Optional.empty());
        return new Refusal("'" + name + "' already exists — refusing", null, diagnostic);
    }

    /** The RK_REFERENCED refusal for safe-delete: the referrers in {@code data.referencedBy}, with a fix. */
    static Refusal referencedBy(String target, Set<String> rules, Set<String> computations, String fix) {
        ObjectNode data = MAPPER.createObjectNode();
        ObjectNode referencedBy = data.putObject("referencedBy");
        ArrayNode ruleArray = referencedBy.putArray("rules");
        rules.forEach(ruleArray::add);
        ArrayNode computationArray = referencedBy.putArray("computations");
        computations.forEach(computationArray::add);
        Diagnostic diagnostic = Diagnostic.precheck(Optional.of("RK_REFERENCED"),
                target + " is referenced by " + (rules.size() + computations.size())
                        + " element(s); removing it would dangle them",
                Diagnostic.Where.path(target), Optional.of(fix), Optional.empty());
        return new Refusal(target + " is referenced — refusing to remove", data, diagnostic);
    }
}
