package io.github.mbackschat.a12.dm.cli.include;

import io.github.mbackschat.a12.dm.cli.BaseCommand;
import io.github.mbackschat.a12.dm.cli.ConsistencyEvidence;
import io.github.mbackschat.a12.dm.cli.ModelWrite;
import io.github.mbackschat.a12.dm.cli.ParamKind;
import io.github.mbackschat.a12.dm.cli.Refusal;
import io.github.mbackschat.a12.dm.cli.SafetyChecks;
import io.github.mbackschat.a12.dm.cli.SafetyGate;
import io.github.mbackschat.a12.dm.cli.WorkspaceOption;

import java.nio.file.Path;
import java.util.Optional;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.mbackschat.a12.dm.core.ModelResolver;

import io.github.mbackschat.a12.dm.core.ModelConsistencyException;
import io.github.mbackschat.a12.dm.core.ReferenceResolvers;
import io.github.mbackschat.a12.dm.modelkit.ModelDocument;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

/**
 * {@code dmtool include inline <alias>} — the inverse of {@code group extract} (CLI-SPEC §6): fold a mounted
 * include's content back into the base as a real group and drop the header reference. The included model is
 * resolved by id from the include-dir (its content lives in a separate file); the result re-validates
 * through the kernel. {@code changed.inlined}/{@code reference} name what was folded back in.
 */
@Command(name = "inline", mixinStandardHelpOptions = true,
        description = "Fold a mounted include back into the base as a real group; drops the reference.")
final class IncludeInlineCommand extends BaseCommand {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @ParamKind(ParamKind.Kind.INCLUDE_ALIAS)
    @Parameters(index = "0", paramLabel = "<alias>", description = "the include alias to inline (see `include read`)")
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
        String modelJson = readModelJson(model);
        if (modelJson == null) {
            return 2;
        }
        ModelDocument doc = ModelDocument.read(modelJson);
        Optional<Refusal> refusal = new SafetyGate()
                .require(SafetyChecks.includeExists(doc, alias))
                .firstFailure();
        if (refusal.isPresent()) {
            return refuse(refusal.get());
        }
        String reference = doc.includes().stream()
                .filter(i -> i.alias().equals(alias)).map(ModelDocument.Include::reference)
                .findFirst().orElseThrow();

        Path[] roots = workspace.roots(model).toArray(Path[]::new); // -w / $DMTOOL_WORKSPACE / the model's folder
        Optional<String> includedJson = ReferenceResolvers.documentModelJson(reference, roots);
        if (includedJson.isEmpty()) {
            // Both affordances, because this message reaches both transports: over MCP there is no -w to pass
            // (the server owns the workspace), and `references` is the only way to make the model available.
            return fail("cannot resolve the included model '" + reference
                    + "': on the CLI, pass -w <dir> pointing at the directory that holds it;"
                    + " over MCP, send its DM-JSON in `references`");
        }

        ModelResolver resolver = workspace.resolverFor(model);
        String resultJson;
        try {
            doc.inlineInclude(alias, includedJson.get());
            resultJson = doc.validate(resolver).toJson();
        } catch (ModelConsistencyException e) {
            return emitKernelRejection("inline left the model invalid", e);
        } catch (RuntimeException e) {
            return failEditGate(e);
        }

        ObjectNode changed = MAPPER.createObjectNode();
        changed.put("inlined", alias);
        changed.put("reference", reference);
        return emitMutation(ModelWrite.of(resultIdentity())
                .summaries("would inline include " + alias,
                        "inlined include " + alias + " (folded " + reference + " back in)")
                .changed(changed)
                .json(resultJson)
                .gatedBy(ConsistencyEvidence.of(doc))
                .dryRun(dryRun)
                .destination(outputPath, model));
    }
}
