package io.github.mbackschat.a12.dm.cli;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.Set;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.mgmtp.a12.kernel.md.model.api.services.IDocumentModelReferenceResolver;

import io.github.mbackschat.a12.dm.adapter.KernelAdapter;
import io.github.mbackschat.a12.dm.core.ModelConsistencyException;
import io.github.mbackschat.a12.dm.modelkit.ModelDocument;
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
abstract class ReferenceRewritingRefactorCommand extends BaseCommand {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Option(names = {"--output", "-o"}, paramLabel = "<file>",
            description = "write the result here instead of in place (overwriting the model)")
    String outputPath;

    @Option(names = "--dry-run", description = "preview only — write NOTHING")
    boolean dryRun;

    @Mixin
    IncludeDirOption includes = new IncludeDirOption();

    /** The refactor to run — its gate, reference entity-moves, node change, labels (one of the factories). */
    abstract StructureRefactor refactor();

    @Override
    public final Integer call() {
        String model = model();
        String modelJson = readModelJson(model);
        if (modelJson == null) {
            return 2;
        }
        StructureRefactor refactor = refactor();
        ModelDocument doc = ModelDocument.read(modelJson);
        IDocumentModelReferenceResolver resolver = includes.resolverFor(model);
        KernelAdapter adapter = KernelAdapter.ofJson(modelJson, resolver);

        // gate: op-specific preconditions, checked before any mutation (CLI-SPEC §6).
        Optional<Refusal> refusal = refactor.firstFailure(doc, adapter);
        if (refusal.isPresent()) {
            return refuse(refactor.target(), refactor.op(), refusal.get());
        }
        Set<String> rewroteReferences = refactor.referrers(adapter, doc);

        String resultJson;
        try {
            // rewrite every reference to the new path (kernel MoveSupportDM), apply the node change, re-validate.
            resultJson = refactor.rewriteAndMutate(modelJson).validate(resolver).toJson();
        } catch (ModelConsistencyException e) {
            return emitKernelRejection(refactor.target(), refactor.op(), refactor.op() + " left the model invalid", e);
        } catch (RuntimeException e) {
            return fail(e.getMessage());
        }

        ObjectNode changed = MAPPER.createObjectNode();
        changed.put(refactor.changedKey(), refactor.source());
        changed.put("to", refactor.newPath());
        ArrayNode refs = changed.putArray("rewroteReferences");
        rewroteReferences.forEach(refs::add);

        if (dryRun) {
            ResultEnvelope env = ResultEnvelope.preview(refactor.target(), refactor.op(),
                    "would " + refactor.op() + " " + refactor.source() + " to " + refactor.newPath(), changed);
            out().print(env.toJsonString());
            return env.exitCode();
        }
        String dest = outputPath != null ? outputPath : model;
        try {
            Files.writeString(Path.of(dest), resultJson);
        } catch (IOException e) {
            return fail("cannot write output: " + e.getMessage());
        }
        ResultEnvelope env = ResultEnvelope.applied(refactor.target(), refactor.op(),
                refactor.changedKey() + " " + refactor.source() + " to " + refactor.newPath(), changed, dest);
        out().print(env.toJsonString());
        return env.exitCode();
    }
}
