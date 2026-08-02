package io.github.mbackschat.a12.dm.cli.group;

import io.github.mbackschat.a12.dm.cli.BaseCommand;
import io.github.mbackschat.a12.dm.cli.ConsistencyEvidence;
import io.github.mbackschat.a12.dm.cli.GroupRemoval;
import io.github.mbackschat.a12.dm.cli.ModelWrite;
import io.github.mbackschat.a12.dm.cli.ParamKind;
import io.github.mbackschat.a12.dm.cli.Refusal;
import io.github.mbackschat.a12.dm.cli.SafetyChecks;
import io.github.mbackschat.a12.dm.cli.SafetyGate;
import io.github.mbackschat.a12.dm.cli.WorkspaceOption;

import java.util.Optional;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.mbackschat.a12.dm.core.ModelResolver;

import io.github.mbackschat.a12.dm.adapter.KernelAdapter;
import io.github.mbackschat.a12.dm.core.ModelConsistencyException;
import io.github.mbackschat.a12.dm.edit.EditSession;
import io.github.mbackschat.a12.dm.modelkit.ModelDocument;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

/**
 * {@code dmtool group remove <groupPath>} — remove a group (and everything under it) and write the model
 * back. The safe-delete gate (CLI-SPEC §6): a group referenced by a rule/computation <b>outside</b> the
 * removed subtree is refused unless {@code --cascade}. Rules/computations <b>inside</b> the subtree (e.g. a
 * composite group's own internal rules, §3.8) are removed with it and never trigger the gate. A removal that
 * dangles a descendant-field reference is caught by the kernel gate (a {@code rejected} envelope).
 */
@Command(name = "remove", mixinStandardHelpOptions = true,
        description = "Remove a group (and its contents); a referenced group is refused unless --cascade.")
final class GroupRemoveCommand extends BaseCommand {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @ParamKind(ParamKind.Kind.GROUP_PATH)
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
    WorkspaceOption workspace = new WorkspaceOption();

    @Override
    public Integer call() {
        String model = model();
        String modelJson = readModelJson(model);
        if (modelJson == null) {
            return 2;
        }
        ModelResolver resolver = workspace.resolverFor(model);
        Optional<Refusal> notFound = new SafetyGate()
                .require(SafetyChecks.exists(ModelDocument.read(modelJson), group, true))
                .firstFailure();
        if (notFound.isPresent()) {
            return refuse(notFound.get());
        }
        EditSession session = EditSession.read(modelJson, resolver);
        ArrayNode cascaded = MAPPER.createArrayNode();
        try {
            // The safe-delete cascade is computed by GroupRemoval — the SAME helper the `apply` group-remove
            // op uses, so the standalone verb and the op stay in lockstep (G12). Referrers inside the removed
            // subtree go away with the group (a composite group's internal rules) and never gate.
            KernelAdapter adapter = KernelAdapter.ofJson(modelJson, resolver);
            GroupRemoval.Plan plan = GroupRemoval.plan(adapter, group, cascade,
                    "pass --cascade to remove the referrers too, or remove them first");
            if (plan.refused()) {
                return refuse(plan.refusal().get());
            }
            for (String r : plan.cascadeRules()) {
                session.removeRule(r);
                cascaded.add(r);
            }
            for (String c : plan.cascadeComputations()) {
                session.removeComputation(c);
                cascaded.add(c);
            }
            session.removeGroup(group);
        } catch (RuntimeException e) {
            return fail(e.getMessage());
        }

        String resultJson;
        try {
            resultJson = session.validate().toJson();
        } catch (ModelConsistencyException e) {
            return emitKernelRejection("removal left the model invalid", e);
        }
        ObjectNode changed = MAPPER.createObjectNode();
        changed.put("removed", group);
        changed.set("cascaded", cascaded);
        return emitMutation(ModelWrite.of(resultIdentity())
                .summaries("would remove " + group,
                        "removed " + group)
                .changed(changed)
                .json(resultJson)
                .gatedBy(ConsistencyEvidence.of(session))
                .dryRun(dryRun)
                .destination(outputPath, model));
    }
}
