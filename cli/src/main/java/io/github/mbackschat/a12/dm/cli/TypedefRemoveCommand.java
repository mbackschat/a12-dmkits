package io.github.mbackschat.a12.dm.cli;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.mgmtp.a12.kernel.md.model.api.services.IDocumentModelReferenceResolver;

import io.github.mbackschat.a12.dm.core.ModelConsistencyException;
import io.github.mbackschat.a12.dm.modelkit.ModelDocument;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

/**
 * {@code dmtool typedef remove <id>} — remove a type definition and write the model back. There is no
 * proactive safe-delete gate for typedefs (they are not tracked by {@code whereUsed}); a typedef still used
 * by a field is caught by the kernel on re-bind — a {@code rejected} envelope carrying the kernel diagnostics.
 */
@Command(name = "remove", mixinStandardHelpOptions = true,
        description = "Remove a type definition; a typedef still used by a field is rejected by the kernel.")
final class TypedefRemoveCommand extends BaseCommand {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @ParamKind(ParamKind.Kind.TYPEDEF_ID)
    @Parameters(index = "0", paramLabel = "<typeDefId>", description = "the type definition's id")
    String id;

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
        IDocumentModelReferenceResolver resolver = includes.resolverFor(model);
        ModelDocument doc = ModelDocument.read(modelJson);
        Optional<Refusal> refusal = new SafetyGate()
                .require(SafetyChecks.typedefExists(doc, id))
                .firstFailure();
        if (refusal.isPresent()) {
            return refuse("typedef", "remove", refusal.get());
        }
        String resultJson;
        try {
            doc.removeTypeDefinition(id);
            resultJson = doc.validate(resolver).toJson();
        } catch (ModelConsistencyException e) {
            return emitKernelRejection("typedef", "remove", "removal left the model invalid", e);
        } catch (RuntimeException e) {
            return fail(e.getMessage());
        }
        ObjectNode changed = MAPPER.createObjectNode();
        changed.put("removed", id);
        if (dryRun) {
            ResultEnvelope env = ResultEnvelope.preview("typedef", "remove", "would remove typedef " + id, changed);
            out().print(env.toJsonString());
            return env.exitCode();
        }
        String dest = outputPath != null ? outputPath : model;
        try {
            Files.writeString(Path.of(dest), resultJson);
        } catch (IOException e) {
            return fail("cannot write output: " + e.getMessage());
        }
        ResultEnvelope env = ResultEnvelope.applied("typedef", "remove", "removed typedef " + id, changed, dest);
        out().print(env.toJsonString());
        return env.exitCode();
    }
}
