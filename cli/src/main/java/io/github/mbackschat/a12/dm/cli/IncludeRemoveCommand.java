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
 * {@code dmtool include remove <alias>} — remove an include (its header model-reference <em>and</em> every
 * mount group) and write the model back. There is no proactive safe-delete gate for includes; a rule still
 * referencing the mounted content is caught by the kernel on re-bind — a {@code rejected} envelope.
 */
@Command(name = "remove", mixinStandardHelpOptions = true,
        description = "Remove an include (header reference + its mounts); a rule still using it is rejected.")
final class IncludeRemoveCommand extends BaseCommand {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @ParamKind(ParamKind.Kind.INCLUDE_ALIAS)
    @Parameters(index = "0", paramLabel = "<alias>", description = "the include alias to remove")
    String alias;

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
        if (model == null) {
            return fail("pass -m <model.json> (before or after the verb)");
        }
        String modelJson;
        try {
            modelJson = Files.readString(Path.of(model));
        } catch (IOException e) {
            return fail("cannot read model: " + e.getMessage());
        }
        IDocumentModelReferenceResolver resolver = workspace.resolverFor(model);
        ModelDocument doc = ModelDocument.read(modelJson);
        Optional<Refusal> refusal = new SafetyGate()
                .require(SafetyChecks.includeExists(doc, alias))
                .firstFailure();
        if (refusal.isPresent()) {
            return refuse("include", "remove", refusal.get());
        }
        String resultJson;
        try {
            doc.removeInclude(alias);
            resultJson = doc.validate(resolver).toJson();
        } catch (ModelConsistencyException e) {
            return emitKernelRejection("include", "remove", "removal left the model invalid", e);
        } catch (RuntimeException e) {
            return fail(e.getMessage());
        }
        ObjectNode changed = MAPPER.createObjectNode();
        changed.put("removed", alias);
        if (dryRun) {
            ResultEnvelope env = ResultEnvelope.preview("include", "remove", "would remove include " + alias, changed);
            out().print(env.toJsonString());
            return env.exitCode();
        }
        String dest = outputPath != null ? outputPath : model;
        try {
            Files.writeString(Path.of(dest), resultJson);
        } catch (IOException e) {
            return fail("cannot write output: " + e.getMessage());
        }
        ResultEnvelope env = ResultEnvelope.applied("include", "remove", "removed include " + alias, changed, dest);
        out().print(env.toJsonString());
        return env.exitCode();
    }
}
