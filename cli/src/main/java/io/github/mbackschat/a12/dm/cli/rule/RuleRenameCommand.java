package io.github.mbackschat.a12.dm.cli.rule;

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
import io.github.mbackschat.a12.dm.core.ModelResolver;

import io.github.mbackschat.a12.dm.adapter.KernelAdapter;
import io.github.mbackschat.a12.dm.core.ModelConsistencyException;
import io.github.mbackschat.a12.dm.modelkit.ModelDocument;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

/**
 * {@code dmtool rule rename <rulePathInModel> --to <newName>} — a Tier-2 safe rename (CLI-SPEC §6). A rule is
 * not referenced by anything (conditions reference fields, not rules), so the only gate is a name
 * collision ({@code RK_NAME_EXISTS}); the kernel re-validate is the backstop. Renames the rule's name + id.
 */
@Command(name = "rename", mixinStandardHelpOptions = true,
        description = "Rename a validation rule; a colliding name is refused.")
final class RuleRenameCommand extends BaseCommand {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @ParamKind(ParamKind.Kind.RULE_PATH)
    @Parameters(index = "0", paramLabel = "<rulePathInModel>", description = "the rule's full name — its slash-path in the model, e.g. /Order/SomeRule")
    String rule;

    @ParamKind(ParamKind.Kind.NEW_NAME)
    @Option(names = "--to", required = true, paramLabel = "<newName>", description = "the rule's new name")
    String newName;

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
        StructureRefactor refactor = StructureRefactor.ruleRename(rule, newName);
        String newPath = refactor.newPath();
        ModelDocument doc = ModelDocument.read(modelJson);
        ModelResolver resolver = workspace.resolverFor(model);
        KernelAdapter adapter = KernelAdapter.ofJson(modelJson, resolver);

        Optional<Refusal> refusal = refactor.firstFailure(doc, adapter);
        if (refusal.isPresent()) {
            return refuse("rule", "rename", refusal.get());
        }

        String resultJson;
        try {
            resultJson = refactor.rewriteAndMutate(modelJson).validate(resolver).toJson();
        } catch (ModelConsistencyException e) {
            return emitKernelRejection("rule", "rename", "rename left the model invalid", e);
        } catch (RuntimeException e) {
            return failEditGate(e);
        }
        ObjectNode changed = MAPPER.createObjectNode();
        changed.put("renamed", rule);
        changed.put("to", newPath);
        if (dryRun) {
            ResultEnvelope env = ResultEnvelope.preview("rule", "rename",
                    "would rename " + rule + " to " + newPath, changed);
            out().print(env.toJsonString());
            return env.exitCode();
        }
        String dest = outputPath != null ? outputPath : model;
        try {
            Files.writeString(Path.of(dest), resultJson);
        } catch (IOException e) {
            return fail("cannot write output: " + e.getMessage());
        }
        ResultEnvelope env = ResultEnvelope.applied("rule", "rename", "renamed " + rule + " to " + newPath,
                changed, dest);
        out().print(env.toJsonString());
        return env.exitCode();
    }
}
