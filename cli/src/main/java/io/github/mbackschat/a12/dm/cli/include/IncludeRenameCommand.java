package io.github.mbackschat.a12.dm.cli.include;

import io.github.mbackschat.a12.dm.cli.BaseCommand;
import io.github.mbackschat.a12.dm.cli.ParamKind;
import io.github.mbackschat.a12.dm.cli.Refusal;
import io.github.mbackschat.a12.dm.cli.ResultEnvelope;
import io.github.mbackschat.a12.dm.cli.StructureRefactor;
import io.github.mbackschat.a12.dm.cli.WorkspaceOption;

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
 * {@code dmtool include rename <alias> --to <newAlias>} — the include peer of {@code field/group/rule/typedef
 * rename} (CLI-SPEC §6). Rewrites the header {@code modelReference}'s alias <b>and</b> every mount's
 * {@code modelAlias} in step, so nothing dangles; mount group names/ids are unchanged, so rules over mounted
 * content (keyed by path, not alias) are unaffected — no AST reference-rewrite is needed. The safety gate runs
 * first: a new alias already declared is <b>refused</b> ({@code RK_NAME_EXISTS}, exit 2) and nothing is written;
 * the kernel re-validate is the backstop.
 */
@Command(name = "rename", mixinStandardHelpOptions = true,
        description = "Rename an include's alias (header reference + every mount); a taken alias is refused.")
final class IncludeRenameCommand extends BaseCommand {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @ParamKind(ParamKind.Kind.INCLUDE_ALIAS)
    @Parameters(index = "0", paramLabel = "<alias>", description = "the include's current alias")
    String alias;

    @ParamKind(ParamKind.Kind.NEW_NAME)
    @Option(names = "--to", required = true, paramLabel = "<newAlias>", description = "the new include alias")
    String newAlias;

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
        StructureRefactor refactor = StructureRefactor.includeRename(alias, newAlias);
        ModelDocument doc = ModelDocument.read(modelJson);
        IDocumentModelReferenceResolver resolver = workspace.resolverFor(model);
        KernelAdapter adapter = KernelAdapter.ofJson(modelJson, resolver);

        // the refactor safety gate (CLI-SPEC §6): check before we change anything, refuse with diagnostics.
        Optional<Refusal> refusal = refactor.firstFailure(doc, adapter);
        if (refusal.isPresent()) {
            return refuse("include", "rename", refusal.get());
        }

        String resultJson;
        try {
            resultJson = refactor.rewriteAndMutate(modelJson).validate(resolver).toJson();
        } catch (ModelConsistencyException e) {
            return emitKernelRejection("include", "rename", "rename left the model invalid", e);
        } catch (RuntimeException e) {
            return fail(e.getMessage());
        }

        ObjectNode changed = MAPPER.createObjectNode();
        changed.put("renamed", alias);
        changed.put("to", newAlias);
        if (dryRun) {
            ResultEnvelope env = ResultEnvelope.preview("include", "rename",
                    "would rename include " + alias + " to " + newAlias, changed);
            out().print(env.toJsonString());
            return env.exitCode();
        }
        String dest = outputPath != null ? outputPath : model;
        try {
            Files.writeString(Path.of(dest), resultJson);
        } catch (IOException e) {
            return fail("cannot write output: " + e.getMessage());
        }
        ResultEnvelope env = ResultEnvelope.applied("include", "rename",
                "renamed include " + alias + " to " + newAlias, changed, dest);
        out().print(env.toJsonString());
        return env.exitCode();
    }
}
