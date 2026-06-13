package io.github.mbackschat.a12.dm.cli;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.BiFunction;
import java.util.function.Consumer;

import io.github.mbackschat.a12.dm.adapter.KernelAdapter;
import io.github.mbackschat.a12.dm.adapter.ReferenceRewriter;
import io.github.mbackschat.a12.dm.modelkit.ModelDocument;

/**
 * One reference-rewriting / node-relocating structure refactor (CLI-SPEC §6), shared by the standalone
 * verb (a one-shot {@link ModelDocument}) and the {@code apply} session (a per-op round-trip). It
 * single-sources the things that vary per op — the safety-gate composition, the reference entity-moves
 * (empty when the op rewrites no AST references), the node mutation, and the referrer report — so the
 * gate can NEVER drift between the two callers.
 *
 * <p>The pipeline is always the same: {@link #firstFailure} (refuse on the first failing precondition,
 * mutating nothing) → {@link #rewriteAndMutate} (rewrite every reference to the new path via the kernel's
 * {@code MoveSupportDM}, then apply the node change). Only the surrounding validate/write differs and
 * stays with the caller — the standalone verb validates + writes immediately; {@code apply} re-homes the
 * result into its session and defers to the one terminal gate. This type does the gate-and-edit, nothing
 * else.
 */
final class StructureRefactor {

    private final String target;
    private final String op;
    private final String changedKey;
    private final String source;
    private final String newPath;
    private final Map<String, String> entityMoves;
    private final BiFunction<ModelDocument, KernelAdapter, SafetyGate> gate;
    private final Consumer<ModelDocument> mutate;
    private final BiFunction<KernelAdapter, ModelDocument, Set<String>> referrers;

    private StructureRefactor(String target, String op, String changedKey, String source, String newPath,
            Map<String, String> entityMoves, BiFunction<ModelDocument, KernelAdapter, SafetyGate> gate,
            Consumer<ModelDocument> mutate, BiFunction<KernelAdapter, ModelDocument, Set<String>> referrers) {
        this.target = target;
        this.op = op;
        this.changedKey = changedKey;
        this.source = source;
        this.newPath = newPath;
        this.entityMoves = entityMoves;
        this.gate = gate;
        this.mutate = mutate;
        this.referrers = referrers;
    }

    String target() {
        return target;
    }

    String op() {
        return op;
    }

    String changedKey() {
        return changedKey;
    }

    String source() {
        return source;
    }

    String newPath() {
        return newPath;
    }

    /** The first failing precondition against THIS model state, or empty when the refactor is safe. */
    Optional<Refusal> firstFailure(ModelDocument doc, KernelAdapter adapter) {
        return gate.apply(doc, adapter).firstFailure();
    }

    /** The referrers rewritten to the new path (for {@code changed.rewroteReferences}); empty for rule/typedef. */
    Set<String> referrers(KernelAdapter adapter, ModelDocument doc) {
        return referrers.apply(adapter, doc);
    }

    /**
     * Rewrite every reference (if any) then apply the node change, on a fresh document parsed from
     * {@code modelJson}. The result is NOT validated — the caller owns the kernel gate.
     */
    ModelDocument rewriteAndMutate(String modelJson) {
        String rewritten = entityMoves.isEmpty() ? modelJson
                : ReferenceRewriter.rewriteReferences(modelJson, entityMoves);
        ModelDocument doc = ModelDocument.read(rewritten);
        mutate.accept(doc);
        return doc;
    }

    // --- factories: one per refactor op, each single-sourcing its gate + entity-moves + node change ----

    static StructureRefactor fieldRename(String fieldPath, String newName) {
        String to = renamePath(fieldPath, newName);
        return new StructureRefactor("field", "rename", "renamed", fieldPath, to, Map.of(fieldPath, to),
                (doc, adapter) -> new SafetyGate()
                        .require(SafetyChecks.exists(doc, fieldPath, false))
                        .require(SafetyChecks.pathAvailable(doc, to)),
                doc -> doc.renameField(fieldPath, newName),
                (adapter, doc) -> singleReferrers(adapter, fieldPath));
    }

    static StructureRefactor groupRename(String groupPath, String newName) {
        String to = renamePath(groupPath, newName);
        return new StructureRefactor("group", "rename", "renamed", groupPath, to, Map.of(groupPath, to),
                (doc, adapter) -> new SafetyGate()
                        .require(SafetyChecks.exists(doc, groupPath, true))
                        .require(SafetyChecks.pathAvailable(doc, to)),
                doc -> doc.renameGroup(groupPath, newName),
                (adapter, doc) -> subtreeReferrers(adapter, doc, groupPath));
    }

    static StructureRefactor fieldMove(String fieldPath, String newParent) {
        String to = movePath(newParent, fieldPath);
        return new StructureRefactor("field", "move", "moved", fieldPath, to, Map.of(fieldPath, to),
                (doc, adapter) -> new SafetyGate()
                        .require(SafetyChecks.exists(doc, fieldPath, false))
                        .require(SafetyChecks.groupExists(doc, newParent))
                        .require(SafetyChecks.pathAvailable(doc, to)),
                doc -> doc.moveField(fieldPath, newParent),
                (adapter, doc) -> singleReferrers(adapter, fieldPath));
    }

    static StructureRefactor groupMove(String groupPath, String newParent) {
        String to = movePath(newParent, groupPath);
        return new StructureRefactor("group", "move", "moved", groupPath, to, Map.of(groupPath, to),
                (doc, adapter) -> new SafetyGate()
                        .require(SafetyChecks.exists(doc, groupPath, true))
                        .require(SafetyChecks.groupExists(doc, newParent))
                        .require(SafetyChecks.notIntoOwnSubtree(groupPath, newParent))
                        .require(SafetyChecks.pathAvailable(doc, to)),
                doc -> doc.moveGroup(groupPath, newParent),
                (adapter, doc) -> subtreeReferrers(adapter, doc, groupPath));
    }

    static StructureRefactor ruleRename(String ruleFullName, String newName) {
        String to = renamePath(ruleFullName, newName);
        // a rule is referenced by nothing, so there are no references to rewrite (entityMoves empty).
        return new StructureRefactor("rule", "rename", "renamed", ruleFullName, to, Map.of(),
                (doc, adapter) -> new SafetyGate()
                        .require(SafetyChecks.ruleExists(adapter, ruleFullName))
                        .require(SafetyChecks.pathAvailable(doc, to)),
                doc -> doc.renameRule(ruleFullName, newName),
                (adapter, doc) -> Set.of());
    }

    static StructureRefactor typedefRename(String id, String newId) {
        // a typedef never appears in a condition; renameTypeDefinition repoints the using fields' ids in step.
        return new StructureRefactor("typedef", "rename", "renamed", id, newId, Map.of(),
                (doc, adapter) -> new SafetyGate()
                        .require(SafetyChecks.typedefExists(doc, id))
                        .require(SafetyChecks.typeDefIdAvailable(doc, newId)),
                doc -> doc.renameTypeDefinition(id, newId),
                (adapter, doc) -> Set.of());
    }

    /** The new full path of a renamed element: its parent path + the new name. */
    private static String renamePath(String path, String newName) {
        return path.substring(0, path.lastIndexOf('/') + 1) + newName;
    }

    /** The new full path of a moved element: the new parent + the element's (unchanged) name. */
    private static String movePath(String newParent, String path) {
        return newParent + "/" + path.substring(path.lastIndexOf('/') + 1);
    }

    /** Referrers of a single element (a field): the rule + computation references to {@code path}. */
    private static Set<String> singleReferrers(KernelAdapter adapter, String path) {
        Set<String> out = new TreeSet<>(adapter.whereUsed(path));
        out.addAll(adapter.whereUsedInComputations(path));
        return out;
    }

    /** Referrers of a whole subtree (a group + every descendant field/sub-group): the union over it. */
    private static Set<String> subtreeReferrers(KernelAdapter adapter, ModelDocument doc, String groupPath) {
        String inside = groupPath + "/";
        List<String> subtree = new ArrayList<>();
        subtree.add(groupPath);
        doc.fieldPaths().forEach(p -> {
            if (p.startsWith(inside)) {
                subtree.add(p);
            }
        });
        doc.groupPaths().forEach(p -> {
            if (p.startsWith(inside) && !p.equals(groupPath)) {
                subtree.add(p);
            }
        });
        Set<String> out = new TreeSet<>();
        for (String path : subtree) {
            out.addAll(adapter.whereUsed(path));
            out.addAll(adapter.whereUsedInComputations(path));
        }
        return out;
    }
}
