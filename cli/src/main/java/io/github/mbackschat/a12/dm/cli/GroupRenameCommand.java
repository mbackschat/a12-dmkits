package io.github.mbackschat.a12.dm.cli;

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
 * {@code dmtool group rename <groupPath> --to <newName>} — a Tier-2 safe rename (CLI-SPEC §6). It re-homes
 * the group and every descendant (their id-paths move in step; rules inside the subtree, referencing
 * relatively, keep working). The safety gate refuses when any element in the subtree is referenced from
 * <b>outside</b> it ({@code RK_REFERENCED}) — that external reference would dangle — or the new name
 * collides ({@code RK_NAME_EXISTS}). The descendant check is the point: a rule referencing a child field
 * (not the group) must still block.
 */
@Command(name = "rename", mixinStandardHelpOptions = true,
        description = "Rename a group (re-homing its subtree); an externally-referenced subtree (or a taken name) is refused.")
final class GroupRenameCommand extends BaseCommand {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Parameters(index = "0", paramLabel = "<groupPathInModel>", description = "the group's full name-path")
    String group;

    @Option(names = "--to", required = true, paramLabel = "<newName>", description = "the group's new name")
    String newName;

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
        ModelDocument doc = ModelDocument.read(modelJson);
        String newPath = group.substring(0, group.lastIndexOf('/') + 1) + newName;
        IDocumentModelReferenceResolver resolver = includes.resolverFor(model);

        Optional<Refusal> refusal = new SafetyGate()
                .require(SafetyChecks.pathAvailable(doc, newPath))
                .require(SafetyChecks.subtreeNotExternallyReferenced(
                        KernelAdapter.ofJson(modelJson, resolver), doc, group))
                .firstFailure();
        if (refusal.isPresent()) {
            return refuse("group", "rename", refusal.get());
        }

        String resultJson;
        try {
            doc.renameGroup(group, newName);
            resultJson = doc.validate(resolver).toJson();
        } catch (ModelConsistencyException e) {
            return emitKernelRejection("group", "rename", "rename left the model invalid", e);
        } catch (RuntimeException e) {
            return fail(e.getMessage());
        }
        ObjectNode changed = MAPPER.createObjectNode();
        changed.put("renamed", group);
        changed.put("to", newPath);
        if (dryRun) {
            ResultEnvelope env = ResultEnvelope.preview("group", "rename",
                    "would rename " + group + " to " + newPath, changed);
            out().print(env.toJsonString());
            return env.exitCode();
        }
        String dest = outputPath != null ? outputPath : model;
        try {
            Files.writeString(Path.of(dest), resultJson);
        } catch (IOException e) {
            return fail("cannot write output: " + e.getMessage());
        }
        ResultEnvelope env = ResultEnvelope.applied("group", "rename", "renamed " + group + " to " + newPath,
                changed, dest);
        out().print(env.toJsonString());
        return env.exitCode();
    }
}
