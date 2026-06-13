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
import io.github.mbackschat.a12.dm.rulekit.validate.RkCode;

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

    /**
     * Refuse a refactor whose <b>target element</b> does not exist — RK_NO_SUCH_FIELD / RK_NO_SUCH_GROUP,
     * located at the offending path. The source must resolve before any reference rewrite or node change is
     * attempted, so a typo'd path yields a structured, corrective diagnostic (not a bare downstream error).
     */
    static SafetyCheck exists(ModelDocument doc, String path, boolean isGroup) {
        return () -> {
            boolean present = isGroup ? doc.groupPaths().contains(path) : doc.fieldPaths().contains(path);
            if (present) {
                return Optional.empty();
            }
            String kind = isGroup ? "group" : "field";
            String code = isGroup ? "RK_NO_SUCH_GROUP" : "RK_NO_SUCH_FIELD";
            Diagnostic diagnostic = Diagnostic.precheck(Optional.of(code),
                    "no " + kind + " at '" + path + "'",
                    Diagnostic.Where.path(path),
                    Optional.of("pass an existing " + kind + "'s full name-path (see `doc describe` or `" + kind + " read`)"),
                    Optional.empty());
            return Optional.of(new Refusal(kind + " '" + path + "' does not exist — refusing", null, diagnostic));
        };
    }

    /**
     * Refuse a rule refactor whose target rule does not exist (RK_NO_SUCH_RULE). A rule is not a field/group,
     * so its existence is read from the bound model's rule names rather than the path tree.
     */
    static SafetyCheck ruleExists(KernelAdapter adapter, String ruleFullName) {
        return () -> {
            if (adapter.ruleFullNames().contains(ruleFullName)) {
                return Optional.empty();
            }
            Diagnostic diagnostic = Diagnostic.precheck(Optional.of("RK_NO_SUCH_RULE"),
                    "no rule at '" + ruleFullName + "'",
                    Diagnostic.Where.path(ruleFullName),
                    Optional.of("pass an existing rule's full name (group-path + name; see `rule read` or `where-used`)"),
                    Optional.empty());
            return Optional.of(new Refusal("rule '" + ruleFullName + "' does not exist — refusing", null, diagnostic));
        };
    }

    /** Refuse a typedef refactor whose target type definition is not declared (RK_NO_SUCH_TYPEDEF). */
    static SafetyCheck typedefExists(ModelDocument doc, String id) {
        return () -> doc.typeDefinitionIds().contains(id)
                ? Optional.empty()
                : Optional.of(notFound(RkCode.NO_SUCH_TYPEDEF, "type definition", id));
    }

    /** Refuse a metadata edit whose target element/type-def does not exist (RK_NO_SUCH_ELEMENT). */
    static SafetyCheck elementExists(ModelDocument doc, String pathOrTypeDefId) {
        return () -> doc.describableExists(pathOrTypeDefId)
                ? Optional.empty()
                : Optional.of(notFound(RkCode.NO_SUCH_ELEMENT, "element or type definition", pathOrTypeDefId));
    }

    /** Refuse an include refactor whose target alias is not a declared model include (RK_NO_SUCH_INCLUDE). */
    static SafetyCheck includeExists(ModelDocument doc, String alias) {
        return () -> doc.includes().stream().anyMatch(i -> i.alias().equals(alias))
                ? Optional.empty()
                : Optional.of(notFound(RkCode.NO_SUCH_INCLUDE, "include", alias));
    }

    /** Refuse a computation op whose target computation does not exist (RK_NO_SUCH_COMPUTATION). */
    static SafetyCheck computationExists(KernelAdapter adapter, String computationFullName) {
        return () -> adapter.computationFullNames().contains(computationFullName)
                ? Optional.empty()
                : Optional.of(notFound(RkCode.NO_SUCH_COMPUTATION, "computation", computationFullName));
    }

    /**
     * Refuse a multi-character decimal separator (RK_INVALID_CONFIG_VALUE) — the kernel stores the
     * {@code modelConfig} string verbatim without checking it, so a garbage separator would otherwise be
     * silently accepted (AP3). Only the decimal separator needs this pre-check; a bad time zone / condition
     * language is already rejected by the kernel on re-bind.
     */
    static SafetyCheck decimalSeparatorValid(String separator) {
        return () -> {
            if (separator == null || separator.length() == 1) {
                return Optional.empty();
            }
            Diagnostic diagnostic = Diagnostic.precheck(Optional.of(RkCode.INVALID_CONFIG_VALUE.code()),
                    "the decimal separator must be a single character, got '" + separator + "'",
                    Diagnostic.Where.arg(null, "--decimal-separator"),
                    Optional.of(RkCode.INVALID_CONFIG_VALUE.fix()), Optional.empty());
            return Optional.of(new Refusal(
                    "'" + separator + "' is not a valid decimal separator — refusing", null, diagnostic));
        };
    }

    /** A located "no such element" refusal, with the code's catalogued fix (single-source via {@link RkCode}). */
    private static Refusal notFound(RkCode code, String kind, String path) {
        Diagnostic diagnostic = Diagnostic.precheck(Optional.of(code.code()),
                "no " + kind + " at '" + path + "'",
                Diagnostic.Where.path(path), Optional.of(code.fix()), Optional.empty());
        return new Refusal(kind + " '" + path + "' does not exist — refusing", null, diagnostic);
    }

    /**
     * Refuse a move whose destination parent group does not exist (RK_NO_SUCH_GROUP) — there is nowhere to
     * re-home the element. Runs before any mutation.
     */
    static SafetyCheck groupExists(ModelDocument doc, String groupPath) {
        return () -> {
            if (doc.groupPaths().contains(groupPath)) {
                return Optional.empty();
            }
            Diagnostic diagnostic = Diagnostic.precheck(Optional.of("RK_NO_SUCH_GROUP"),
                    "no group at the destination path '" + groupPath + "'",
                    Diagnostic.Where.arg(null, "--to"),
                    Optional.of("pass --to an existing group's full name-path (see `group read` or `doc describe`)"),
                    Optional.empty());
            return Optional.of(new Refusal(
                    "destination group '" + groupPath + "' does not exist — refusing to move", null, diagnostic));
        };
    }

    /**
     * Refuse moving a group into itself or its own subtree (RK_MOVE_INTO_SELF) — re-homing a group beneath
     * one of its own descendants would orphan the whole subtree. Runs before any mutation.
     */
    static SafetyCheck notIntoOwnSubtree(String groupPath, String newParentPath) {
        return () -> {
            if (!newParentPath.equals(groupPath) && !newParentPath.startsWith(groupPath + "/")) {
                return Optional.empty();
            }
            Diagnostic diagnostic = Diagnostic.precheck(Optional.of("RK_MOVE_INTO_SELF"),
                    "cannot move " + groupPath + " into itself or its own subtree ('" + newParentPath + "')",
                    Diagnostic.Where.path(groupPath),
                    Optional.of("choose a destination group that lies outside the moved subtree"),
                    Optional.empty());
            return Optional.of(new Refusal(
                    groupPath + " cannot move into its own subtree — refusing", null, diagnostic));
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
