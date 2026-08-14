package io.github.mbackschat.a12.dm.cli;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.mbackschat.a12.dm.core.ModelResolver;

import io.github.mbackschat.a12.dm.adapter.KernelAdapter;
import io.github.mbackschat.a12.dm.core.ModelConsistencyException;
import io.github.mbackschat.a12.dm.modelkit.ModelDocument;
import io.github.mbackschat.a12.dm.rulekit.validate.Diagnostic;
import io.github.mbackschat.a12.dm.rulekit.validate.RkCode;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Option;

/**
 * Template-method base for the <b>reference-rewriting structure refactors</b> — {@code field}/{@code group}
 * {@code rename} and {@code move} (CLI-SPEC §6). All four share one flow, so it lives here once:
 *
 * <ol>
 *   <li>read the model;</li>
 *   <li>run the op-specific {@link SafetyGate} — refuse on the first failing precondition, mutating nothing;</li>
 *   <li>when safe, rewrite every reference to the element's new path via the kernel's {@code MoveSupportDM}
 *       ({@code adapter.ReferenceRewriter}), apply the node change, and re-validate (the kernel gate);</li>
 *   <li>write in place (or to {@code --output}), or preview under {@code --dry-run}.</li>
 * </ol>
 *
 * The {@code changed} envelope reports <code>{&lt;verb&gt;: source, to: newPath, rewroteReferences: [...]}</code>.
 *
 * <p>What varies per op — the gate, the reference entity-moves, the node mutation, the labels, and which
 * referrers to report — is single-sourced in the {@link StructureRefactor} a subclass returns from
 * {@link #refactor()}. The SAME {@code StructureRefactor} factories drive the {@code apply} session's
 * refactor ops, so the safety gate can never drift between the standalone verb and a batch-of-edits.
 *
 * <p>Deliberately <em>not</em> on this base (different output shapes, both still gate+edit via a
 * {@link StructureRefactor}): {@code rule rename} (rewrites no references, no {@code rewroteReferences}),
 * {@code typedef rename} (repoints fields, reports {@code repointedFields}), and
 * {@code add}/{@code remove}/{@code extract}/{@code inline}.
 */
@Command
public abstract class ReferenceRewritingRefactorCommand extends BaseCommand {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Option(names = {"--output", "-o"}, paramLabel = "<file>",
            description = "write the result here instead of in place (overwriting the model)")
    String outputPath;

    @Option(names = "--dry-run", description = "preview only — write NOTHING")
    boolean dryRun;

    @Mixin
    WorkspaceOption workspace = new WorkspaceOption();

    /** The refactor to run — its gate, reference entity-moves, node change, labels (one of the factories). */
    protected abstract StructureRefactor refactor();

    @Override
    public final Integer call() {
        String model = model();
        String modelJson = readModelJson(model);
        if (modelJson == null) {
            return 2;
        }
        StructureRefactor refactor = refactor();
        ModelDocument doc = ModelDocument.read(modelJson);
        ModelResolver resolver = workspace.resolverFor(model);
        KernelAdapter adapter = KernelAdapter.ofJson(modelJson, resolver);

        // gate: op-specific preconditions, checked before any mutation (CLI-SPEC §6).
        Optional<Refusal> refusal = refactor.firstFailure(doc, adapter);
        if (refusal.isPresent()) {
            return refuse(refusal.get());
        }
        Set<String> rewroteReferences = refactor.referrers(adapter, doc);
        // A move changes WHICH GROUP-OPERAND EXPANSIONS contain the element, and that has no text to rewrite:
        // no condition mentions the field, the model stays kernel-valid, and `rewroteReferences: []` is then
        // literally true and maximally misleading. Measured before this landed — `applied`, `KERNEL_CONFIRMED`,
        // no diagnostics — while a rule's `AllFieldsFilled(<group>)` silently lost a member (CG18).
        // An ADVISORY rather than a refusal: the move is legal and usually intended, so refusing it would
        // block ordinary work; what the author needs is to be told which rules changed meaning.
        List<Diagnostic> expansionAdvisories = expansionAdvisories(adapter, refactor);

        String resultJson;
        // The gated document is the REWRITTEN one, not the `doc` read above — that one only feeds the
        // safety gate and never sees `validate`, so publishing its (absent) receipt would silently
        // under-report the check this route really ran.
        ModelDocument rewritten;
        try {
            // rewrite every reference to the new path (kernel MoveSupportDM), apply the node change, re-validate.
            rewritten = refactor.rewriteAndMutate(modelJson).validate(resolver);
            resultJson = rewritten.toJson();
        } catch (ModelConsistencyException e) {
            return emitKernelRejection(refactor.op() + " left the model invalid", e);
        } catch (RuntimeException e) {
            return failEditGate(e);
        }

        ObjectNode changed = MAPPER.createObjectNode();
        changed.put(refactor.changedKey(), refactor.source());
        changed.put("to", refactor.newPath());
        ArrayNode refs = changed.putArray("rewroteReferences");
        rewroteReferences.forEach(refs::add);

        return emitMutation(ModelWrite.of(resultIdentity())
                .summaries("would " + refactor.op() + " " + refactor.source() + " to " + refactor.newPath(),
                        refactor.changedKey() + " " + refactor.source() + " to " + refactor.newPath())
                .changed(changed)
                .json(resultJson)
                .advisories(expansionAdvisories)
                .gatedBy(ConsistencyEvidence.of(rewritten))
                .dryRun(dryRun)
                .destination(outputPath, model));
    }

    /**
     * The rules and computations whose GROUP-OPERAND expansion changes because the element moved — one advisory
     * naming them, or none.
     *
     * <p>A move has no reference text to rewrite when the reader reaches the element through an ancestor group:
     * the kernel expands a group operand to every field below it, so moving a field out of that group silently
     * removes it from the operand while every condition string stays valid and the model stays kernel-valid.
     * Nothing downstream objects, which is why this has to be said here ({@code CG18}).
     *
     * <p>Computed from the reachability primitive on both sides and DIFFERENCED, so a reader that reaches the
     * element at its new home too — a move within one group operand's scope — produces no advisory at all. That
     * difference is what keeps this from firing on every move.
     */
    private static List<Diagnostic> expansionAdvisories(KernelAdapter adapter, StructureRefactor refactor) {
        var byEntity = adapter.referrersByEntity();
        Set<String> before = new TreeSet<>(KernelAdapter.referrersReaching(byEntity, refactor.source()));
        before.removeAll(KernelAdapter.referrersReaching(byEntity, refactor.newPath()));
        // A referrer naming the element ITSELF travels with it (MoveSupportDM rewrites that text), so only a
        // GROUP-reached reader can be losing it here.
        before.removeAll(byEntity.getOrDefault(refactor.source(), Set.of()));
        if (before.isEmpty()) {
            return List.of();
        }
        List<Diagnostic> out = new ArrayList<>();
        out.add(Diagnostic.precheckWarning(Optional.of(RkCode.MOVE_CHANGES_GROUP_EXPANSION.code()),
                refactor.source() + " is read through an enclosing group by " + before.size()
                        + " element(s), and moving it to " + refactor.newPath()
                        + " removes it from that group's expansion: " + before,
                Diagnostic.Where.path(refactor.source()),
                Optional.of("no condition text names the field, so nothing was rewritten and the model stays "
                        + "valid — check that each listed rule still means what you intend"),
                Optional.empty()));
        return out;
    }
}
