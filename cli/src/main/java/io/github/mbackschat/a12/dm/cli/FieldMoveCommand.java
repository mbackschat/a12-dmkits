package io.github.mbackschat.a12.dm.cli;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
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
import picocli.CommandLine.Parameters;

/**
 * {@code dmtool field move <fieldPath> --to <newParentGroup>} — a reference-preserving move (CLI-SPEC §6).
 * The safety gate runs first: a destination group that does not exist is <b>refused</b>
 * ({@code RK_NO_SUCH_GROUP}) and a name already taken under the destination is refused
 * ({@code RK_NAME_EXISTS}). Only when safe does it rewrite every referrer to the field's new path (the
 * kernel's {@code MoveSupportDM}) and re-home the declaration; the result is kernel-revalidated.
 */
@Command(name = "move", mixinStandardHelpOptions = true,
        description = "Move a field to a different parent group; references are rewritten to the new path.")
final class FieldMoveCommand extends BaseCommand {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Parameters(index = "0", paramLabel = "<fieldPathInModel>", description = "the field's full name-path")
    String field;

    @Option(names = "--to", required = true, paramLabel = "<newParentGroup>",
            description = "the destination group's full name-path (the field keeps its name)")
    String newParent;

    @Option(names = {"--output", "-o"}, paramLabel = "<file>",
            description = "write the result here instead of in place (overwriting the model)")
    String outputPath;

    @Option(names = "--dry-run", description = "preview only — write NOTHING")
    boolean dryRun;

    @Mixin
    IncludeDirOption includes = new IncludeDirOption();

    @Override
    public Integer call() {
        String model = model();
        String modelJson = readModelJson(model);
        if (modelJson == null) {
            return 2;
        }
        ModelDocument doc = ModelDocument.read(modelJson);
        String newPath = newParent + "/" + field.substring(field.lastIndexOf('/') + 1);
        IDocumentModelReferenceResolver resolver = includes.resolverFor(model);

        // gate: the destination group must exist, and its child name must be free — both before any mutation.
        Optional<Refusal> refusal = new SafetyGate()
                .require(SafetyChecks.groupExists(doc, newParent))
                .require(SafetyChecks.pathAvailable(doc, newPath))
                .firstFailure();
        if (refusal.isPresent()) {
            return refuse("field", "move", refusal.get());
        }

        KernelAdapter adapter = KernelAdapter.ofJson(modelJson, resolver);
        Set<String> rewroteReferences = new TreeSet<>(adapter.whereUsed(field));
        rewroteReferences.addAll(adapter.whereUsedInComputations(field));

        String resultJson;
        try {
            // rewrite every referrer to the new path (kernel MoveSupportDM), then re-home the declaration.
            ModelDocument moved = ModelDocument.read(
                    ReferenceRewriter.rewriteReferences(modelJson, Map.of(field, newPath)));
            moved.moveField(field, newParent);
            resultJson = moved.validate(resolver).toJson();
        } catch (ModelConsistencyException e) {
            return emitKernelRejection("field", "move", "move left the model invalid", e);
        } catch (RuntimeException e) {
            return fail(e.getMessage());
        }
        ObjectNode changed = MAPPER.createObjectNode();
        changed.put("moved", field);
        changed.put("to", newPath);
        ArrayNode refs = changed.putArray("rewroteReferences");
        rewroteReferences.forEach(refs::add);
        if (dryRun) {
            ResultEnvelope env = ResultEnvelope.preview("field", "move",
                    "would move " + field + " to " + newPath, changed);
            out().print(env.toJsonString());
            return env.exitCode();
        }
        String dest = outputPath != null ? outputPath : model;
        try {
            Files.writeString(Path.of(dest), resultJson);
        } catch (IOException e) {
            return fail("cannot write output: " + e.getMessage());
        }
        ResultEnvelope env = ResultEnvelope.applied("field", "move", "moved " + field + " to " + newPath,
                changed, dest);
        out().print(env.toJsonString());
        return env.exitCode();
    }
}
