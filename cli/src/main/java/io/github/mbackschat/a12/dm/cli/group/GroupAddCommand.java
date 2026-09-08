package io.github.mbackschat.a12.dm.cli.group;

import io.github.mbackschat.a12.dm.cli.BaseCommand;
import io.github.mbackschat.a12.dm.cli.ConsistencyEvidence;
import io.github.mbackschat.a12.dm.cli.ModelPaths;
import io.github.mbackschat.a12.dm.cli.ModelWrite;
import io.github.mbackschat.a12.dm.cli.ParamKind;
import io.github.mbackschat.a12.dm.cli.Refusal;
import io.github.mbackschat.a12.dm.cli.SafetyChecks;
import io.github.mbackschat.a12.dm.cli.SafetyGate;
import io.github.mbackschat.a12.dm.cli.WorkspaceOption;

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
            description = "the parent group, by full name-path, e.g. /Order; `/` (equivalently the empty "
                    + "string) adds a new ROOT group")
    String parent;

    @ParamKind(ParamKind.Kind.NEW_NAME)
    @Option(names = "--name", required = true, paramLabel = "<name>", description = "the new group's name")
    String name;

    @ParamKind(ParamKind.Kind.SCALAR)
    @Option(names = "--repeatable", paramLabel = "<max>",
            description = "make the group a repetition list with this max repetitions (≥2 — 1 IS the "
                    + "non-repeatable encoding, which the kernel reads as not repeatable, so omit the flag "
                    + "rather than passing 1)")
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
        String modelJson = readModelJson(model);
        if (modelJson == null) {
            return 2;
        }
        ModelResolver resolver = workspace.resolverFor(model);
        String parent = ModelPaths.rootLevelParent(this.parent);
        String target = parent + "/" + name;
        ModelDocument doc = ModelDocument.read(modelJson);
        if (!parent.isEmpty()) {   // "" = a new root group, which has no parent to check
            Optional<Refusal> refusal = new SafetyGate()
                    .require(SafetyChecks.parentExists(doc, parent))
                    .firstFailure();
            if (refusal.isPresent()) {
                return refuse(refusal.get());
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
            return emitKernelRejection("the group is rejected by the kernel", e);
        } catch (RuntimeException e) {
            return failEditGate(e);
        }
        ObjectNode changed = MAPPER.createObjectNode();
        changed.put("added", target);
        changed.put("repeatable", repeatable != null);
        return emitMutation(ModelWrite.of(resultIdentity())
                .summaries("would add group " + target,
                        "added group " + target)
                .changed(changed)
                .json(resultJson)
                .gatedBy(ConsistencyEvidence.of(doc))
                .dryRun(dryRun)
                .destination(outputPath, model));
    }
}
