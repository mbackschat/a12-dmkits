package io.github.mbackschat.a12.dm.cli;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import io.github.mbackschat.a12.dm.adapter.KernelAdapter;
import io.github.mbackschat.a12.dm.core.EntityPaths;
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
public final class SafetyChecks {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** The locales the attachment-group template can author its generated rules in (the kernel's two DSL languages). */
    private static final Set<String> ATTACHMENT_LOCALES = Set.of("en_US", "de_DE");

    private SafetyChecks() {
    }

    /** A multi-select group must be repeatable — refuse a non-repeatable {@code --max-rows} (&lt; 2). */
    public static SafetyCheck multiSelectMinRows(int maxRows) {
        return () -> {
            if (maxRows >= 2) {
                return Optional.empty();
            }
            Diagnostic diagnostic = Diagnostic.precheck(Optional.of(RkCode.MULTISELECT_MIN_ROWS.code()),
                    "a multi-select needs at least 2 rows (it must be repeatable); --max-rows was " + maxRows,
                    Diagnostic.Where.arg(null, "--max-rows"),
                    Optional.of(RkCode.MULTISELECT_MIN_ROWS.fix()), Optional.empty());
            return Optional.of(new Refusal(
                    "multi-select --max-rows " + maxRows + " is not repeatable — refusing", null, diagnostic));
        };
    }

    /**
     * The attachment-group template generates complete en/de rules — refuse a model that declares any other
     * locale (the generated rules would lack a message for it, and couldn't render in its DSL keywords).
     */
    public static SafetyCheck attachmentLocaleSupported(ModelDocument doc) {
        return () -> {
            List<String> unsupported = doc.locales().stream()
                    .filter(locale -> !ATTACHMENT_LOCALES.contains(locale)).toList();
            if (unsupported.isEmpty()) {
                return Optional.empty();
            }
            Diagnostic diagnostic = Diagnostic.precheck(Optional.of(RkCode.ATTACHMENT_LOCALE_UNSUPPORTED.code()),
                    "the attachment template generates en/de rules; the model declares unsupported locale(s) " + unsupported,
                    Diagnostic.Where.arg(null, "-m"),
                    Optional.of(RkCode.ATTACHMENT_LOCALE_UNSUPPORTED.fix()), Optional.empty());
            return Optional.of(new Refusal(
                    "the model declares non-en/de locale(s) " + unsupported
                            + " — the attachment template can't author its rules for them", null, diagnostic));
        };
    }

    /** Refuse a rename whose new path collides with an existing field or group (the collision gate). */
    public static SafetyCheck pathAvailable(ModelDocument doc, String newPath) {
        return () -> doc.fieldPaths().contains(newPath) || doc.groupPaths().contains(newPath)
                ? Optional.of(nameExists(newPath, "field or group",
                        "choose a name not already used by a sibling, or rename/remove the existing one first"))
                : Optional.empty();
    }

    /**
     * Refuse a typedef rename when {@code newId} is already a declared type definition — a rename must not
     * collide with (and silently merge into) an existing one. The new-id check runs before any mutation.
     */
    public static SafetyCheck typeDefIdAvailable(ModelDocument doc, String newId) {
        return () -> doc.typeDefinitionIds().contains(newId)
                ? Optional.of(nameExists(newId, "type definition",
                        "choose a new id that is not already declared, or remove the existing '" + newId + "' first"))
                : Optional.empty();
    }

    /**
     * Refuse a typedef extract when the {@code fieldPaths} do not all exist and share one concrete type —
     * there is no single type to factor out (and we never silently coerce). Runs before any mutation.
     */
    public static SafetyCheck fieldsShareConcreteType(ModelDocument doc, List<String> fieldPaths) {
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
    public static SafetyCheck includableAsRoot(ModelDocument doc, String groupPath) {
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
     * Refuse extracting a group whose subtree contains a rule <b>or computation</b> referencing an entity
     * <b>outside</b> the subtree — it can't move into a standalone sub-model (it wouldn't see what it reads).
     * Elements whose references stay inside travel fine. References pointing <em>into</em> the subtree from
     * outside are unaffected (the mount preserves the path), so they don't block. RK_OUTWARD_REFERENCE.
     *
     * <p><b>Both element kinds, and the subtree root counts as inside</b> — the two halves of {@code CG17}, which
     * were wrong in opposite directions at once:
     *
     * <ul>
     *   <li>{@code ruleFullNames()} deliberately EXCLUDES computations, so looping it alone let a computation
     *       reading an outside field move into a sub-model that cannot see its own operand. Every other
     *       reference site in this repo pairs {@code whereUsed} with {@code whereUsedInComputations}; this one
     *       did not.</li>
     *   <li>Membership was {@code startsWith(groupPath + "/")}, and {@code "/Order/Shipping"} does not start with
     *       {@code "/Order/Shipping/"} — so a rule referencing the extracted group ITSELF was reported as
     *       escaping the subtree it is the root of. The reference travels fine (the group becomes the sub-model's
     *       root), so that was an over-refusal naming a problem the author cannot fix. A refusal we invent is
     *       the more dangerous half, because nothing downstream ever asks whether it was right.</li>
     * </ul>
     */
    public static SafetyCheck subtreeSelfContained(KernelAdapter adapter, String groupPath) {
        return () -> {
            Set<String> escaping = new TreeSet<>();
            collectEscaping(adapter, groupPath, adapter.ruleFullNames(), adapter::referencedFields, escaping);
            collectEscaping(adapter, groupPath, adapter.computationFullNames(),
                    adapter::referencedFieldsOfComputation, escaping);
            if (escaping.isEmpty()) {
                return Optional.empty();
            }
            ObjectNode data = MAPPER.createObjectNode();
            ArrayNode array = data.putArray("escapingRules");
            escaping.forEach(array::add);
            Diagnostic diagnostic = Diagnostic.precheck(Optional.of("RK_OUTWARD_REFERENCE"),
                    "the subtree has " + escaping.size() + " rule(s)/computation(s) referencing entities outside it; they cannot move into a standalone sub-model",
                    Diagnostic.Where.path(groupPath),
                    Optional.of("move those rules or computations out of the subtree, or extract a larger group that contains what they reference"),
                    Optional.empty());
            return Optional.of(new Refusal(groupPath + " has outward references — refusing to extract", data, diagnostic));
        };
    }

    /** Add every element of {@code elements} that lives inside {@code groupPath} and references something
     *  outside it, reading each element's references through {@code referencesOf} (the accessor differs by
     *  element kind, which is exactly what the rule-only version got wrong). */
    private static void collectEscaping(KernelAdapter adapter, String groupPath, Set<String> elements,
            java.util.function.Function<String, Set<String>> referencesOf, Set<String> escaping) {
        for (String element : elements) {
            if (!EntityPaths.isInside(element, groupPath)) {
                continue;   // only elements that would move into the sub-model
            }
            if (referencesOf.apply(element).stream()
                    .anyMatch(entity -> !EntityPaths.isInside(entity, groupPath))) {
                escaping.add(element);
            }
        }
    }

    /**
     * Refuse a refactor whose <b>target element</b> does not exist — RK_NO_SUCH_FIELD / RK_NO_SUCH_GROUP,
     * located at the offending path. The source must resolve before any reference rewrite or node change is
     * attempted, so a typo'd path yields a structured, corrective diagnostic (not a bare downstream error).
     */
    public static SafetyCheck exists(ModelDocument doc, String path, boolean isGroup) {
        return exists(doc, path, isGroup,
                "pass an existing " + (isGroup ? "group" : "field")
                        + "'s full name-path (see `model describe` or `" + (isGroup ? "group" : "field") + " read`)");
    }

    /**
     * As {@link #exists(ModelDocument, String, boolean)} with a caller-supplied corrective, for a slot whose
     * likeliest wrong value is not merely a mistyped path. The {@code --parent} slots use it: an author who
     * meant the ROOT level needs to be told that spelling, not told to go and read the model
     * ({@link ModelPaths#missingParentHint}).
     */
    public static SafetyCheck exists(ModelDocument doc, String path, boolean isGroup, String hint) {
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
                    Optional.of(hint),
                    Optional.empty());
            return Optional.of(new Refusal(kind + " '" + path + "' does not exist — refusing", null, diagnostic));
        };
    }

    /** The {@code --parent} slot's existence check — {@link #exists} with the root-level corrective. */
    public static SafetyCheck parentExists(ModelDocument doc, String parent) {
        return exists(doc, parent, true, ModelPaths.missingParentHint("group"));
    }

    /**
     * Refuse adding a child element into a group that mounts an include ({@code includeConfig}) — A12 replaces a
     * mount's own {@code elements} with the included content, so the added child would silently vanish (KF651).
     * {@code RK_MOUNT_NOT_EDITABLE}.
     */
    public static SafetyCheck notAnIncludeMount(ModelDocument doc, String groupPath) {
        return () -> {
            Optional<String> reference = doc.mountReferenceAt(groupPath);
            if (reference.isEmpty()) {
                return Optional.empty();
            }
            Diagnostic diagnostic = Diagnostic.precheck(Optional.of("RK_MOUNT_NOT_EDITABLE"),
                    "group '" + groupPath + "' mounts the model '" + reference.get()
                            + "' — A12 replaces a mount's own elements with the included content, so a child added here would silently vanish",
                    Diagnostic.Where.arg(null, "--group"),
                    Optional.of("add the element to the included model '" + reference.get()
                            + "' instead (edit that model's structure), or choose a non-mount group"),
                    Optional.empty());
            return Optional.of(new Refusal(
                    "group '" + groupPath + "' is an include mount — refusing to add a child A12 would discard", null, diagnostic));
        };
    }

    /**
     * Refuse a rule refactor whose target rule does not exist (RK_NO_SUCH_RULE). A rule is not a field/group,
     * so its existence is read from the bound model's rule names rather than the path tree.
     */
    public static SafetyCheck ruleExists(KernelAdapter adapter, String ruleFullName) {
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
    public static SafetyCheck typedefExists(ModelDocument doc, String id) {
        return () -> doc.typeDefinitionIds().contains(id)
                ? Optional.empty()
                : Optional.of(notFound(RkCode.NO_SUCH_TYPEDEF, "type definition", id));
    }

    /** Refuse a metadata edit whose target element/type-def does not exist (RK_NO_SUCH_ELEMENT). */
    public static SafetyCheck elementExists(ModelDocument doc, String pathOrTypeDefId) {
        return () -> doc.describableExists(pathOrTypeDefId)
                ? Optional.empty()
                : Optional.of(notFound(RkCode.NO_SUCH_ELEMENT, "element or type definition", pathOrTypeDefId));
    }

    /** Refuse a label write on a describable that has no label slot (rules, computations, and type definitions). */
    public static SafetyCheck labelSupported(ModelDocument doc, String pathOrTypeDefId, boolean labelRequested) {
        return () -> {
            if (!labelRequested || doc.labels(pathOrTypeDefId).isPresent()) {
                return Optional.empty();
            }
            Diagnostic diagnostic = Diagnostic.precheck(Optional.of(RkCode.LABEL_NOT_SUPPORTED.code()),
                    "a label applies only to a field or group; '" + pathOrTypeDefId + "' has no display-label slot",
                    Diagnostic.Where.path(pathOrTypeDefId),
                    Optional.of(RkCode.LABEL_NOT_SUPPORTED.fix()), Optional.empty());
            return Optional.of(new Refusal(
                    "the target does not support a display label — refusing", null, diagnostic));
        };
    }

    /** Refuse an include op whose referenced model is not included by this model (RK_NO_SUCH_INCLUDE). */
    public static SafetyCheck includeExists(ModelDocument doc, String reference) {
        return () -> doc.includes().stream().anyMatch(i -> i.reference().equals(reference))
                ? Optional.empty()
                : Optional.of(notFound(RkCode.NO_SUCH_INCLUDE, "include", reference));
    }

    /** Refuse a computation op whose target computation does not exist (RK_NO_SUCH_COMPUTATION). */
    public static SafetyCheck computationExists(KernelAdapter adapter, String computationFullName) {
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
    public static SafetyCheck decimalSeparatorValid(String separator) {
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
    public static SafetyCheck groupExists(ModelDocument doc, String groupPath) {
        return () -> {
            if (doc.groupPaths().contains(groupPath)) {
                return Optional.empty();
            }
            Diagnostic diagnostic = Diagnostic.precheck(Optional.of("RK_NO_SUCH_GROUP"),
                    "no group at the destination path '" + groupPath + "'",
                    Diagnostic.Where.arg(null, "--to"),
                    Optional.of("pass --to an existing group's full name-path (see `group read` or `model describe`)"),
                    Optional.empty());
            return Optional.of(new Refusal(
                    "destination group '" + groupPath + "' does not exist — refusing to move", null, diagnostic));
        };
    }

    /**
     * Refuse moving a group into itself or its own subtree (RK_MOVE_INTO_SELF) — re-homing a group beneath
     * one of its own descendants would orphan the whole subtree. Runs before any mutation.
     */
    public static SafetyCheck notIntoOwnSubtree(String groupPath, String newParentPath) {
        return () -> {
            if (!EntityPaths.isInside(newParentPath, groupPath)) {
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

    /**
     * Refuse a move whose destination parent is ALREADY the element's current parent — a no-op. Without it
     * the generic {@code pathAvailable} collision reads as a name clash ("choose a name not already used by
     * a sibling"), masking that the request was simply already satisfied (probe 09). Names the real
     * situation instead; runs BEFORE {@code pathAvailable} so the no-op message wins over the generic clash.
     * (Stays {@code RK_NAME_EXISTS} — same gate class — but with a no-op-accurate summary + fix.)
     */
    public static SafetyCheck notAlreadyUnder(String path, String newParent) {
        return () -> {
            int cut = path.lastIndexOf('/');
            String currentParent = cut <= 0 ? "" : path.substring(0, cut);
            if (!currentParent.equals(newParent)) {
                return Optional.empty();
            }
            Diagnostic diagnostic = Diagnostic.precheck(Optional.of("RK_NAME_EXISTS"),
                    "'" + path + "' is already directly under '" + newParent + "' — nothing to move",
                    Diagnostic.Where.path(path),
                    Optional.of("the element is already where you asked; a move only relocates to a DIFFERENT "
                            + "parent, so this is a no-op — nothing was written"),
                    Optional.empty());
            return Optional.of(new Refusal(
                    "'" + path + "' already under '" + newParent + "' — no move needed", null, diagnostic));
        };
    }

    /** The RK_NAME_EXISTS refusal: a rename whose chosen new name/id is already taken (the collision gate). */
    public static Refusal nameExists(String name, String kind, String fix) {
        Diagnostic diagnostic = Diagnostic.precheck(Optional.of("RK_NAME_EXISTS"),
                "a " + kind + " named '" + name + "' already exists",
                Diagnostic.Where.path(name), Optional.of(fix), Optional.empty());
        return new Refusal("'" + name + "' already exists — refusing", null, diagnostic);
    }

    /**
     * The RK_REFERENCED refusal for safe-delete: the referrers in {@code data.referencedBy}, with a fix. A document
     * uniqueness criterion naming the target replaces the fix, since {@code --cascade} removes rules and computations
     * only and A12 refuses a criterion naming a missing field (KF671).
     */
    public static Refusal referencedBy(String target, Set<String> rules, Set<String> computations,
            Set<String> criteria, String fix) {
        ObjectNode data = MAPPER.createObjectNode();
        ObjectNode referencedBy = data.putObject("referencedBy");
        ArrayNode ruleArray = referencedBy.putArray("rules");
        rules.forEach(ruleArray::add);
        ArrayNode computationArray = referencedBy.putArray("computations");
        computations.forEach(computationArray::add);
        ArrayNode criterionArray = referencedBy.putArray("documentUniquenessCriteria");
        criteria.forEach(criterionArray::add);
        String effectiveFix = criteria.isEmpty() ? fix
                : "take " + target + " out of the document uniqueness criteria " + String.join(", ", criteria)
                        + " first; --cascade removes only rules and computations, never a criterion";
        Diagnostic diagnostic = Diagnostic.precheck(Optional.of("RK_REFERENCED"),
                target + " is referenced by " + (rules.size() + computations.size() + criteria.size())
                        + " element(s); removing it would dangle them",
                Diagnostic.Where.path(target), Optional.of(effectiveFix), Optional.empty());
        return new Refusal(target + " is referenced — refusing to remove", data, diagnostic);
    }
}
