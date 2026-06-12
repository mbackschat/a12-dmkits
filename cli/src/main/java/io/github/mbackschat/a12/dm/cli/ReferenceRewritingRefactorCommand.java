package io.github.mbackschat.a12.dm.cli;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.mgmtp.a12.kernel.md.model.api.services.IDocumentModelReferenceResolver;

import io.github.mbackschat.a12.dm.adapter.KernelAdapter;
import io.github.mbackschat.a12.dm.adapter.ReferenceRewriter;
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
 * <p>A subclass supplies only what varies: the target/op labels, the source path, the resulting path, the
 * gate's preconditions, the node mutation, and which referrers to report — {@link #singleReferrers} for a
 * field, {@link #subtreeReferrers} for a group. The shared {@code --output}/{@code --dry-run}/include-dir
 * options live here.
 *
 * <p>Deliberately <em>not</em> on this base: {@code rule rename} (a rule is referenced by nothing, so it
 * rewrites no references and skips the kernel round-trip, keeping its own formatting-preserving shape), and
 * {@code add}/{@code remove}/{@code extract}/{@code inline} (each a different shape that does not rewrite
 * references).
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

    // --- the template method ---------------------------------------------------------------------

    @Override
    public final Integer call() {
        String model = model();
        String modelJson = readModelJson(model);
        if (modelJson == null) {
            return 2;
        }
        ModelDocument doc = ModelDocument.read(modelJson);
        String source = source();
        String newPath = newPath();
        IDocumentModelReferenceResolver resolver = includes.resolverFor(model);

        // gate: op-specific preconditions, checked before any mutation (CLI-SPEC §6).
        Optional<Refusal> refusal = gate(doc, newPath).firstFailure();
        if (refusal.isPresent()) {
            return refuse(target(), op(), refusal.get());
        }

        KernelAdapter adapter = KernelAdapter.ofJson(modelJson, resolver);
        Set<String> rewroteReferences = referrers(adapter, doc);

        String resultJson;
        try {
            // rewrite every reference to the new path (kernel MoveSupportDM), then apply the node change.
            ModelDocument edited = ModelDocument.read(
                    ReferenceRewriter.rewriteReferences(modelJson, Map.of(source, newPath)));
            mutate(edited);
            resultJson = edited.validate(resolver).toJson();
        } catch (ModelConsistencyException e) {
            return emitKernelRejection(target(), op(), op() + " left the model invalid", e);
        } catch (RuntimeException e) {
            return fail(e.getMessage());
        }

        ObjectNode changed = MAPPER.createObjectNode();
        changed.put(changedKey(), source);
        changed.put("to", newPath);
        ArrayNode refs = changed.putArray("rewroteReferences");
        rewroteReferences.forEach(refs::add);

        if (dryRun) {
            ResultEnvelope env = ResultEnvelope.preview(target(), op(),
                    "would " + op() + " " + source + " to " + newPath, changed);
            out().print(env.toJsonString());
            return env.exitCode();
        }
        String dest = outputPath != null ? outputPath : model;
        try {
            Files.writeString(Path.of(dest), resultJson);
        } catch (IOException e) {
            return fail("cannot write output: " + e.getMessage());
        }
        ResultEnvelope env = ResultEnvelope.applied(target(), op(),
                changedKey() + " " + source + " to " + newPath, changed, dest);
        out().print(env.toJsonString());
        return env.exitCode();
    }

    // --- hooks the four leaves implement ----------------------------------------------------------

    /** The target-group label ({@code "field"} / {@code "group"}). */
    abstract String target();

    /** The op label ({@code "rename"} / {@code "move"}) — the envelope op and the {@code "would …"} summary. */
    abstract String op();

    /** The {@code changed} key and the applied-summary verb ({@code "renamed"} / {@code "moved"}). */
    abstract String changedKey();

    /** The element's current full name-path. */
    abstract String source();

    /** The element's resulting full name-path (after the rename/move). */
    abstract String newPath();

    /** The op-specific preconditions, checked before any mutation. */
    abstract SafetyGate gate(ModelDocument doc, String newPath);

    /** Apply the node change to the (already reference-rewritten) document. */
    abstract void mutate(ModelDocument doc);

    /** The referrers rewritten to the new path, reported under {@code changed.rewroteReferences}. */
    abstract Set<String> referrers(KernelAdapter adapter, ModelDocument doc);

    // --- referrer-collection strategies (a field vs a whole subtree) ------------------------------

    /** Referrers of a single element (a field): the rule + computation references to {@code path}. */
    protected final Set<String> singleReferrers(KernelAdapter adapter, String path) {
        Set<String> out = new TreeSet<>(adapter.whereUsed(path));
        out.addAll(adapter.whereUsedInComputations(path));
        return out;
    }

    /** Referrers of a whole subtree (a group + every descendant field/sub-group): the union over it. */
    protected final Set<String> subtreeReferrers(KernelAdapter adapter, ModelDocument doc, String groupPath) {
        String inside = groupPath + "/";
        List<String> subtree = new ArrayList<>();
        subtree.add(groupPath);
        doc.fieldPaths().forEach(p -> { if (p.startsWith(inside)) subtree.add(p); });
        doc.groupPaths().forEach(p -> { if (p.startsWith(inside) && !p.equals(groupPath)) subtree.add(p); });
        Set<String> out = new TreeSet<>();
        for (String path : subtree) {
            out.addAll(adapter.whereUsed(path));
            out.addAll(adapter.whereUsedInComputations(path));
        }
        return out;
    }
}
