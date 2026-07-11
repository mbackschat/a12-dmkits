package io.github.mbackschat.a12.dm.cli.include;

import io.github.mbackschat.a12.dm.cli.BaseCommand;
import io.github.mbackschat.a12.dm.cli.ParamKind;
import io.github.mbackschat.a12.dm.cli.Refusal;
import io.github.mbackschat.a12.dm.cli.ResultEnvelope;
import io.github.mbackschat.a12.dm.cli.SafetyChecks;
import io.github.mbackschat.a12.dm.cli.SafetyGate;
import io.github.mbackschat.a12.dm.cli.WorkspaceOption;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.mbackschat.a12.dm.core.ModelResolver;

import io.github.mbackschat.a12.dm.core.ModelConsistencyException;
import io.github.mbackschat.a12.dm.modelkit.ModelDocument;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

/**
 * {@code dmtool include set-reference <alias> --to <reference>} — repoint an include at a different referenced
 * model IN PLACE (vs remove + re-add, which loses the mount's position/options). The mounts, keyed by alias, are
 * untouched — only the header {@code modelReference}'s {@code reference} changes. The safety gate refuses if the
 * alias is not declared ({@code RK_NO_SUCH_INCLUDE}); the kernel re-validate (via {@code -w/--workspace} resolver)
 * is the backstop when the new reference does not resolve.
 */
@Command(name = "set-reference", mixinStandardHelpOptions = true,
        description = "Repoint an include at a different referenced model in place (mounts kept).")
final class IncludeSetReferenceCommand extends BaseCommand {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @ParamKind(ParamKind.Kind.INCLUDE_ALIAS)
    @Parameters(index = "0", paramLabel = "<alias>", description = "the include alias to repoint")
    String alias;

    @Option(names = "--to", required = true, paramLabel = "<reference>",
            description = "the new model reference the include should resolve to")
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
        ModelResolver resolver = workspace.resolverFor(model);
        ModelDocument doc = ModelDocument.read(modelJson);
        Optional<Refusal> refusal = new SafetyGate()
                .require(SafetyChecks.includeExists(doc, alias))
                .firstFailure();
        if (refusal.isPresent()) {
            return refuse("include", "set-reference", refusal.get());
        }
        String resultJson;
        try {
            doc.setIncludeReference(alias, reference);
            resultJson = doc.validate(resolver).toJson();
        } catch (ModelConsistencyException e) {
            return emitKernelRejection("include", "set-reference", "the new reference left the model invalid", e);
        } catch (RuntimeException e) {
            return fail(e.getMessage());
        }
        ObjectNode changed = MAPPER.createObjectNode();
        changed.put("alias", alias).put("reference", reference);
        if (dryRun) {
            ResultEnvelope env = ResultEnvelope.preview("include", "set-reference",
                    "would repoint include " + alias + " to " + reference, changed);
            out().print(env.toJsonString());
            return env.exitCode();
        }
        String dest = outputPath != null ? outputPath : model;
        try {
            Files.writeString(Path.of(dest), resultJson);
        } catch (IOException e) {
            return fail("cannot write output: " + e.getMessage());
        }
        ResultEnvelope env = ResultEnvelope.applied("include", "set-reference",
                "repointed include " + alias + " to " + reference, changed, dest);
        out().print(env.toJsonString());
        return env.exitCode();
    }
}
