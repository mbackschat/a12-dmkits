package io.github.mbackschat.a12.dm.cli.group;

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

/**
 * {@code dmtool group add --parent <path> --name <n> [--repeatable <max>]} — add a group and write the
 * model back (in place; {@code -o}/{@code --dry-run}). {@code --repeatable <max>} (≥2) makes it a repetition
 * list. Re-bound through the kernel; a kernel-invalid result is a {@code rejected} envelope.
 */
@Command(name = "add", mixinStandardHelpOptions = true,
        description = "Add a group to --parent (--repeatable <max> for a repetition list).")
final class GroupAddCommand extends BaseCommand {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @ParamKind(ParamKind.Kind.GROUP_PATH)
    @Option(names = "--parent", required = true, paramLabel = "<path>",
            description = "the parent group, by full name-path, e.g. /Order")
    String parent;

    @ParamKind(ParamKind.Kind.NEW_NAME)
    @Option(names = "--name", required = true, paramLabel = "<name>", description = "the new group's name")
    String name;

    @ParamKind(ParamKind.Kind.SCALAR)
    @Option(names = "--repeatable", paramLabel = "<max>",
            description = "make the group a repetition list with this max repetitions (≥2)")
    Integer repeatable;

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
        ModelResolver resolver = workspace.resolverFor(model);
        String target = parent + "/" + name;
        ModelDocument doc = ModelDocument.read(modelJson);
        if (!parent.isEmpty()) {   // "" = a new root group, which has no parent to check
            Optional<Refusal> refusal = new SafetyGate()
                    .require(SafetyChecks.exists(doc, parent, true))
                    .firstFailure();
            if (refusal.isPresent()) {
                return refuse("group", "add", refusal.get());
            }
        }
        String resultJson;
        try {
            if (repeatable != null) {
                doc.addRepeatableGroup(parent, name, repeatable);
            } else {
                doc.addGroup(parent, name);
            }
            resultJson = doc.validate(resolver).toJson();
        } catch (ModelConsistencyException e) {
            return emitKernelRejection("group", "add", "the group is rejected by the kernel", e);
        } catch (RuntimeException e) {
            return fail(e.getMessage());
        }
        ObjectNode changed = MAPPER.createObjectNode();
        changed.put("added", target);
        changed.put("repeatable", repeatable != null);
        if (dryRun) {
            ResultEnvelope env = ResultEnvelope.preview("group", "add", "would add group " + target, changed);
            out().print(env.toJsonString());
            return env.exitCode();
        }
        String dest = outputPath != null ? outputPath : model;
        try {
            Files.writeString(Path.of(dest), resultJson);
        } catch (IOException e) {
            return fail("cannot write output: " + e.getMessage());
        }
        ResultEnvelope env = ResultEnvelope.applied("group", "add", "added group " + target, changed, dest);
        out().print(env.toJsonString());
        return env.exitCode();
    }
}
