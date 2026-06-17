package io.github.mbackschat.a12.dm.cli.typedef;

import io.github.mbackschat.a12.dm.cli.BaseCommand;
import io.github.mbackschat.a12.dm.cli.ParamKind;
import io.github.mbackschat.a12.dm.cli.ResultEnvelope;
import io.github.mbackschat.a12.dm.cli.WorkspaceOption;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.mgmtp.a12.kernel.md.model.api.services.IDocumentModelReferenceResolver;

import io.github.mbackschat.a12.dm.core.ModelConsistencyException;
import io.github.mbackschat.a12.dm.modelkit.ModelDocument;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Option;

/**
 * {@code dmtool typedef import --reference <r>} — declare that this model <b>imports the type definitions</b>
 * of another model (a header {@code modelReferences} entry with {@code purpose: "typeDefinitions"}). The
 * referenced model's type-defs merge into this model's namespace by id, so a field can be typed by an
 * imported id with a plain {@code typeDefinitionId} — distinct from {@code include add}, which mounts a
 * model's <em>structure</em>. The referenced model is resolved + kernel-validated via {@code -w/--workspace};
 * an unresolved reference is a {@code rejected} envelope.
 */
@Command(name = "import", mixinStandardHelpOptions = true,
        description = "Import another model's type definitions (purpose=typeDefinitions); not mounted, merged by id.")
final class TypedefImportCommand extends BaseCommand {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @ParamKind(ParamKind.Kind.FREEFORM)
    @Option(names = "--reference", required = true, paramLabel = "<reference>",
            description = "the type-def-providing model's header id (resolved via -w/--workspace)")
    String reference;

    @Option(names = {"--output", "-o"}, paramLabel = "<file>",
            description = "write the result here instead of in place (overwriting the model)")
    String outputPath;

    @Option(names = "--dry-run", description = "preview only — write NOTHING")
    boolean dryRun;

    @Mixin
    WorkspaceOption workspace = new WorkspaceOption();

    @Override
    public Integer call() {
        String model = model();
        String modelJson = readModelJson(model);
        if (modelJson == null) {
            return 2;
        }
        IDocumentModelReferenceResolver resolver = workspace.resolverFor(model);
        String resultJson;
        try {
            resultJson = ModelDocument.read(modelJson)
                    .addImport(reference)
                    .validate(resolver)
                    .toJson();
        } catch (ModelConsistencyException e) {
            return emitKernelRejection("typedef", "import", "the type-def import is rejected by the kernel", e);
        } catch (RuntimeException e) {
            return fail(e.getMessage());
        }
        ObjectNode changed = MAPPER.createObjectNode();
        changed.put("imported", reference);
        if (dryRun) {
            ResultEnvelope env = ResultEnvelope.preview("typedef", "import",
                    "would import type definitions from " + reference, changed);
            out().print(env.toJsonString());
            return env.exitCode();
        }
        String dest = outputPath != null ? outputPath : model;
        try {
            Files.writeString(Path.of(dest), resultJson);
        } catch (IOException e) {
            return fail("cannot write output: " + e.getMessage());
        }
        ResultEnvelope env = ResultEnvelope.applied("typedef", "import",
                "imported type definitions from " + reference, changed, dest);
        out().print(env.toJsonString());
        return env.exitCode();
    }
}
