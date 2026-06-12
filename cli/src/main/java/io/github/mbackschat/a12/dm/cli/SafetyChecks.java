package io.github.mbackschat.a12.dm.cli;

import java.util.ArrayList;
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
 * <p>The first is the <b>reference gate</b> shared by safe-delete and safe-rename: an element still
 * referenced by a rule/computation may not be removed or renamed — doing so would dangle the
 * referrers. The op-specific {@code fix} carries the right next step (remove offers {@code --cascade};
 * rename asks to update the referrers first), over one common {@code data.referencedBy} shape.
 */
final class SafetyChecks {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private SafetyChecks() {
    }

    /** Refuse the refactor while any rule/computation references {@code target} (RK_REFERENCED). */
    static SafetyCheck notReferenced(KernelAdapter adapter, String target, String fix) {
        return () -> {
            Set<String> rules = new TreeSet<>(adapter.whereUsed(target));
            Set<String> computations = new TreeSet<>(adapter.whereUsedInComputations(target));
            return rules.isEmpty() && computations.isEmpty()
                    ? Optional.empty()
                    : Optional.of(referencedBy(target, rules, computations, fix, "rename", "renaming"));
        };
    }

    /** Refuse a rename whose new path collides with an existing field or group (the collision gate). */
    static SafetyCheck pathAvailable(ModelDocument doc, String newPath) {
        return () -> doc.fieldPaths().contains(newPath) || doc.groupPaths().contains(newPath)
                ? Optional.of(nameExists(newPath, "field or group",
                        "choose a name not already used by a sibling, or rename/remove the existing one first"))
                : Optional.empty();
    }

    /**
     * Refuse a group rename when any element in the subtree (the group or a descendant field/group) is
     * referenced by a rule/computation <b>outside</b> the subtree — that external reference would dangle
     * (we don't rewrite references). Rules <em>inside</em> the subtree travel with it and are fine.
     */
    static SafetyCheck subtreeNotExternallyReferenced(KernelAdapter adapter, ModelDocument doc, String groupPath) {
        return () -> {
            String inside = groupPath + "/";
            List<String> subtree = new ArrayList<>();
            subtree.add(groupPath);
            doc.fieldPaths().forEach(p -> { if (p.startsWith(inside)) subtree.add(p); });
            doc.groupPaths().forEach(p -> { if (p.startsWith(inside) && !p.equals(groupPath)) subtree.add(p); });
            Set<String> external = new TreeSet<>();
            for (String path : subtree) {
                adapter.whereUsed(path).forEach(r -> { if (!r.startsWith(inside)) external.add(r); });
                adapter.whereUsedInComputations(path).forEach(r -> { if (!r.startsWith(inside)) external.add(r); });
            }
            return external.isEmpty() ? Optional.empty() : Optional.of(externallyReferenced(groupPath, external));
        };
    }

    private static Refusal externallyReferenced(String target, Set<String> referrers) {
        ObjectNode data = MAPPER.createObjectNode();
        ArrayNode array = data.putArray("referencedBy");
        referrers.forEach(array::add);
        Diagnostic diagnostic = Diagnostic.precheck(Optional.of("RK_REFERENCED"),
                target + " has " + referrers.size() + " referrer(s) outside it; renaming it would dangle them",
                Diagnostic.Where.path(target),
                Optional.of("rename or move the external referrers first"), Optional.empty());
        return new Refusal(target + " is referenced from outside — refusing to rename", data, diagnostic);
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

    /** The RK_NAME_EXISTS refusal: a rename whose chosen new name/id is already taken (the collision gate). */
    static Refusal nameExists(String name, String kind, String fix) {
        Diagnostic diagnostic = Diagnostic.precheck(Optional.of("RK_NAME_EXISTS"),
                "a " + kind + " named '" + name + "' already exists",
                Diagnostic.Where.path(name), Optional.of(fix), Optional.empty());
        return new Refusal("'" + name + "' already exists — refusing", null, diagnostic);
    }

    /** The RK_REFERENCED refusal for safe-delete (the {@code remove} wording). */
    static Refusal referencedBy(String target, Set<String> rules, Set<String> computations, String fix) {
        return referencedBy(target, rules, computations, fix, "remove", "removing");
    }

    /**
     * The RK_REFERENCED refusal: the referrers in {@code data.referencedBy}, with a corrective {@code fix}.
     * {@code refusingTo}/{@code gerund} word the message to the op ({@code remove}/{@code removing},
     * {@code rename}/{@code renaming}), so safe-delete and safe-rename share one data shape.
     */
    static Refusal referencedBy(String target, Set<String> rules, Set<String> computations, String fix,
            String refusingTo, String gerund) {
        ObjectNode data = MAPPER.createObjectNode();
        ObjectNode referencedBy = data.putObject("referencedBy");
        ArrayNode ruleArray = referencedBy.putArray("rules");
        rules.forEach(ruleArray::add);
        ArrayNode computationArray = referencedBy.putArray("computations");
        computations.forEach(computationArray::add);
        Diagnostic diagnostic = Diagnostic.precheck(Optional.of("RK_REFERENCED"),
                target + " is referenced by " + (rules.size() + computations.size())
                        + " element(s); " + gerund + " it would dangle them",
                Diagnostic.Where.path(target), Optional.of(fix), Optional.empty());
        return new Refusal(target + " is referenced — refusing to " + refusingTo, data, diagnostic);
    }
}
