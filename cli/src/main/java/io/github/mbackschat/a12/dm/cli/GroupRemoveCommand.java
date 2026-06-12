package io.github.mbackschat.a12.dm.cli;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.TreeSet;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.mgmtp.a12.kernel.md.model.api.services.IDocumentModelReferenceResolver;

import io.github.mbackschat.a12.dm.adapter.KernelAdapter;
import io.github.mbackschat.a12.dm.core.ModelConsistencyException;
import io.github.mbackschat.a12.dm.edit.EditSession;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

/**
 * {@code dmtool group remove <groupPath>} — remove a group (and everything under it) and write the model
 * back. The safe-delete gate (CLI-SPEC §6): a group directly referenced by a rule/computation is refused
 * unless {@code --cascade}; a removal that dangles a descendant-field reference is caught by the kernel gate
 * (a {@code rejected} envelope).
 */
@Command(name = "remove", mixinStandardHelpOptions = true,
        description = "Remove a group (and its contents); a referenced group is refused unless --cascade.")
final class GroupRemoveCommand extends BaseCommand {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Parameters(index = "0", paramLabel = "<groupPathInModel>",
            description = "the group's full name-path, e.g. /Order/BillingAddress")
    String group;

    @Option(names = "--cascade",
            description = "also remove the rules/computations that reference the group (else a referenced group is refused)")
    boolean cascade;

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
        if (model == null) {
            return fail("pass -m <model.json> (before or after the verb)");
        }
        String modelJson;
        try {
            modelJson = Files.readString(Path.of(model));
        } catch (IOException e) {
            return fail("cannot read model: " + e.getMessage());
        }
        IDocumentModelReferenceResolver resolver = includes.resolverFor(model);
        EditSession session = EditSession.read(modelJson, resolver);
        ArrayNode cascaded = MAPPER.createArrayNode();
        try {
            KernelAdapter adapter = KernelAdapter.ofJson(modelJson, resolver);
            Set<String> refRules = new TreeSet<>(adapter.whereUsed(group));
            Set<String> refComputations = new TreeSet<>(adapter.whereUsedInComputations(group));
            if (!refRules.isEmpty() || !refComputations.isEmpty()) {
                if (!cascade) {
                    return refuse("group", "remove", SafetyChecks.referencedBy(group, refRules, refComputations,
                            "pass --cascade to remove the referrers too, or remove them first"));
                }
                for (String r : refRules) {
                    session.removeRule(r);
                    cascaded.add(r);
                }
                for (String c : refComputations) {
                    session.removeComputation(c);
                    cascaded.add(c);
                }
            }
            session.removeGroup(group);
        } catch (RuntimeException e) {
            return fail(e.getMessage());
        }

        String resultJson;
        try {
            resultJson = session.validate().toJson();
        } catch (ModelConsistencyException e) {
            return emitKernelRejection("group", "remove", "removal left the model invalid", e);
        }
        ObjectNode changed = MAPPER.createObjectNode();
        changed.put("removed", group);
        changed.set("cascaded", cascaded);
        if (dryRun) {
            ResultEnvelope env = ResultEnvelope.preview("group", "remove", "would remove " + group, changed);
            out().print(env.toJsonString());
            return env.exitCode();
        }
        String dest = outputPath != null ? outputPath : model;
        try {
            Files.writeString(Path.of(dest), resultJson);
        } catch (IOException e) {
            return fail("cannot write output: " + e.getMessage());
        }
        ResultEnvelope env = ResultEnvelope.applied("group", "remove", "removed " + group, changed, dest);
        out().print(env.toJsonString());
        return env.exitCode();
    }
}
