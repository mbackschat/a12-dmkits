package io.github.mbackschat.a12.dm.cli.include;

import io.github.mbackschat.a12.dm.cli.BaseCommand;
import io.github.mbackschat.a12.dm.cli.ConsistencyEvidence;
import io.github.mbackschat.a12.dm.cli.ModelWrite;
import io.github.mbackschat.a12.dm.cli.ParamKind;
import io.github.mbackschat.a12.dm.cli.Refusal;
import io.github.mbackschat.a12.dm.cli.StructureRefactor;
import io.github.mbackschat.a12.dm.cli.WorkspaceOption;

import java.util.Optional;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.mbackschat.a12.dm.core.ModelResolver;

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
        ModelResolver resolver = workspace.resolverFor(model);
        KernelAdapter adapter = KernelAdapter.ofJson(modelJson, resolver);

        // the refactor safety gate (CLI-SPEC §6): check before we change anything, refuse with diagnostics.
        Optional<Refusal> refusal = refactor.firstFailure(doc, adapter);
        if (refusal.isPresent()) {
            return refuse(refusal.get());
        }

        String resultJson;
        // The gated document is the REWRITTEN one, not the `doc` read above — that one only feeds the
        // safety gate and never sees `validate`, so publishing its (absent) receipt would silently
        // under-report the check this route really ran.
        ModelDocument rewritten;
        try {
            rewritten = refactor.rewriteAndMutate(modelJson).validate(resolver);
            resultJson = rewritten.toJson();
        } catch (ModelConsistencyException e) {
            return emitKernelRejection("rename left the model invalid", e);
        }

        ObjectNode changed = MAPPER.createObjectNode();
        changed.put("renamed", alias);
        changed.put("to", newAlias);
        return emitMutation(ModelWrite.of(resultIdentity())
                .summaries("would rename include " + alias + " to " + newAlias,
                        "renamed include " + alias + " to " + newAlias)
                .changed(changed)
                .json(resultJson)
                .gatedBy(ConsistencyEvidence.of(rewritten))
                .dryRun(dryRun)
                .destination(outputPath, model));
    }
}
