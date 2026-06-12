package io.github.mbackschat.a12.dm.cli;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.mgmtp.a12.kernel.md.model.api.services.IDocumentModelReferenceResolver;

import io.github.mbackschat.a12.dm.adapter.KernelAdapter;
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
        if (model == null) {
            return fail("pass -m <model.json> (before or after the verb)");
        }
        String modelJson;
        try {
            modelJson = Files.readString(Path.of(model));
        } catch (IOException e) {
            return fail("cannot read model: " + e.getMessage());
        }
        ModelDocument doc = ModelDocument.read(modelJson);
        String newPath = field.substring(0, field.lastIndexOf('/') + 1) + newName;
        IDocumentModelReferenceResolver resolver = includes.resolverFor(model);

        Optional<Refusal> refusal = new SafetyGate()
                .require(SafetyChecks.pathAvailable(doc, newPath))
                .require(SafetyChecks.notReferenced(KernelAdapter.ofJson(modelJson, resolver), field,
                        "rename the referrers first, or leave the field's name as is"))
                .firstFailure();
        if (refusal.isPresent()) {
            return refuse("field", "rename", refusal.get());
        }

        String resultJson;
        try {
            doc.renameField(field, newName);
            resultJson = doc.validate(resolver).toJson();
        } catch (ModelConsistencyException e) {
            return emitKernelRejection("field", "rename", "rename left the model invalid", e);
        } catch (RuntimeException e) {
            return fail(e.getMessage());
        }
        ObjectNode changed = MAPPER.createObjectNode();
        changed.put("renamed", field);
        changed.put("to", newPath);
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
