package io.github.mbackschat.a12.dm.cli;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.mgmtp.a12.kernel.md.model.api.services.IDocumentModelReferenceResolver;

import io.github.mbackschat.a12.dm.adapter.KernelAdapter;
import io.github.mbackschat.a12.dm.adapter.ReferenceRewriter;
import io.github.mbackschat.a12.dm.core.ModelConsistencyException;
import io.github.mbackschat.a12.dm.modelkit.ModelDocument;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

/**
 * {@code dmtool group move <groupPath> --to <newParentGroup>} — a reference-preserving move (CLI-SPEC §6).
 * It re-homes the group and every descendant (their id-paths move in step; an internal relative-pathed rule
 * travels intact). The safety gate runs first: a destination group that does not exist is <b>refused</b>
 * ({@code RK_NO_SUCH_GROUP}), a destination inside the moved subtree is refused ({@code RK_MOVE_INTO_SELF} —
 * it would orphan the subtree), and a name already taken under the destination is refused
 * ({@code RK_NAME_EXISTS}). Only when safe does it rewrite every reference into the subtree to its new path
 * (the kernel's {@code MoveSupportDM}) and re-home the group; kernel-revalidated.
 */
@Command(name = "move", mixinStandardHelpOptions = true,
        description = "Move a group (re-homing its subtree) to a different parent; references are rewritten.")
final class GroupMoveCommand extends BaseCommand {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Parameters(index = "0", paramLabel = "<groupPathInModel>", description = "the group's full name-path")
    String group;

    @Option(names = "--to", required = true, paramLabel = "<newParentGroup>",
            description = "the destination group's full name-path (the group keeps its name)")
    String newParent;

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
        String newPath = newParent + "/" + group.substring(group.lastIndexOf('/') + 1);
        IDocumentModelReferenceResolver resolver = includes.resolverFor(model);

        // gate: destination must exist, lie outside the moved subtree, and have the child name free — pre-mutation.
        Optional<Refusal> refusal = new SafetyGate()
                .require(SafetyChecks.groupExists(doc, newParent))
                .require(SafetyChecks.notIntoOwnSubtree(group, newParent))
                .require(SafetyChecks.pathAvailable(doc, newPath))
                .firstFailure();
        if (refusal.isPresent()) {
            return refuse("group", "move", refusal.get());
        }

        KernelAdapter adapter = KernelAdapter.ofJson(modelJson, resolver);
        String inside = group + "/";
        List<String> subtree = new ArrayList<>();
        subtree.add(group);
        doc.fieldPaths().forEach(p -> { if (p.startsWith(inside)) subtree.add(p); });
        doc.groupPaths().forEach(p -> { if (p.startsWith(inside) && !p.equals(group)) subtree.add(p); });
        Set<String> rewroteReferences = new TreeSet<>();
        for (String path : subtree) {
            rewroteReferences.addAll(adapter.whereUsed(path));
            rewroteReferences.addAll(adapter.whereUsedInComputations(path));
        }

        String resultJson;
        try {
            // rewrite every reference into the subtree to the new path (kernel MoveSupportDM), then re-home the group.
            ModelDocument moved = ModelDocument.read(
                    ReferenceRewriter.rewriteReferences(modelJson, Map.of(group, newPath)));
            moved.moveGroup(group, newParent);
            resultJson = moved.validate(resolver).toJson();
        } catch (ModelConsistencyException e) {
            return emitKernelRejection("group", "move", "move left the model invalid", e);
        } catch (RuntimeException e) {
            return fail(e.getMessage());
        }
        ObjectNode changed = MAPPER.createObjectNode();
        changed.put("moved", group);
        changed.put("to", newPath);
        ArrayNode refs = changed.putArray("rewroteReferences");
        rewroteReferences.forEach(refs::add);
        if (dryRun) {
            ResultEnvelope env = ResultEnvelope.preview("group", "move",
                    "would move " + group + " to " + newPath, changed);
            out().print(env.toJsonString());
            return env.exitCode();
        }
        String dest = outputPath != null ? outputPath : model;
        try {
            Files.writeString(Path.of(dest), resultJson);
        } catch (IOException e) {
            return fail("cannot write output: " + e.getMessage());
        }
        ResultEnvelope env = ResultEnvelope.applied("group", "move", "moved " + group + " to " + newPath,
                changed, dest);
        out().print(env.toJsonString());
        return env.exitCode();
    }
}
