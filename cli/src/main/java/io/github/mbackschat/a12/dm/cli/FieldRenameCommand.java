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
 * {@code dmtool field rename <fieldPath> --to <newName>} — a Tier-2 safe rename (CLI-SPEC §6). The safety
 * gate runs first: a field still referenced by a rule/computation is <b>refused</b> ({@code RK_REFERENCED})
 * — references are not rewritten (that is the parked engine) — and a colliding new name is refused
 * ({@code RK_NAME_EXISTS}). Only when free does it rename the declaration (name + path); kernel-revalidated.
 */
@Command(name = "rename", mixinStandardHelpOptions = true,
        description = "Rename a field; a referenced field (or a taken name) is refused.")
final class FieldRenameCommand extends BaseCommand {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Parameters(index = "0", paramLabel = "<fieldPathInModel>", description = "the field's full name-path")
    String field;

    @Option(names = "--to", required = true, paramLabel = "<newName>", description = "the field's new name")
    String newName;

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
        String newPath = field.substring(0, field.lastIndexOf('/') + 1) + newName;
        IDocumentModelReferenceResolver resolver = includes.resolverFor(model);

        // gate: only a name collision blocks now — referrers are rewritten, not refused (CLI-SPEC §6).
        Optional<Refusal> refusal = new SafetyGate()
                .require(SafetyChecks.pathAvailable(doc, newPath))
                .firstFailure();
        if (refusal.isPresent()) {
            return refuse("field", "rename", refusal.get());
        }

        KernelAdapter adapter = KernelAdapter.ofJson(modelJson, resolver);
        Set<String> rewroteReferences = new TreeSet<>(adapter.whereUsed(field));
        rewroteReferences.addAll(adapter.whereUsedInComputations(field));

        String resultJson;
        try {
            // rewrite every referrer to the new path (kernel MoveSupportDM), then rename the declaration.
            ModelDocument renamed = ModelDocument.read(
                    ReferenceRewriter.rewriteReferences(modelJson, Map.of(field, newPath)));
            renamed.renameField(field, newName);
            resultJson = renamed.validate(resolver).toJson();
        } catch (ModelConsistencyException e) {
            return emitKernelRejection("field", "rename", "rename left the model invalid", e);
        } catch (RuntimeException e) {
            return fail(e.getMessage());
        }
        ObjectNode changed = MAPPER.createObjectNode();
        changed.put("renamed", field);
        changed.put("to", newPath);
        ArrayNode refs = changed.putArray("rewroteReferences");
        rewroteReferences.forEach(refs::add);
        if (dryRun) {
            ResultEnvelope env = ResultEnvelope.preview("field", "rename",
                    "would rename " + field + " to " + newPath, changed);
            out().print(env.toJsonString());
            return env.exitCode();
        }
        String dest = outputPath != null ? outputPath : model;
        try {
            Files.writeString(Path.of(dest), resultJson);
        } catch (IOException e) {
            return fail("cannot write output: " + e.getMessage());
        }
        ResultEnvelope env = ResultEnvelope.applied("field", "rename", "renamed " + field + " to " + newPath,
                changed, dest);
        out().print(env.toJsonString());
        return env.exitCode();
    }
}
