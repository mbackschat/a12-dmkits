package io.github.mbackschat.a12.dm.cli.rule;

import io.github.mbackschat.a12.dm.cli.BaseCommand;
import io.github.mbackschat.a12.dm.cli.ConsistencyEvidence;
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

import io.github.mbackschat.a12.dm.adapter.KernelAdapter;
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

/**
 * {@code dmtool rule remove <rulePath>} — delete a rule and write the model back (in place; {@code -o}
 * redirects, {@code --dry-run} previews). Removal re-binds through the kernel, so a removal that breaks
 * the model fails loudly. (Rules have no dependents, so — unlike a field/group — there is no cascade gate.)
 */
@Command(name = "remove", mixinStandardHelpOptions = true,
        description = "Remove a rule and write the model back (in place; -o redirects, --dry-run previews).")
final class RuleRemoveCommand extends BaseCommand {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @ParamKind(ParamKind.Kind.RULE_PATH)
    @Parameters(index = "0", paramLabel = "<rulePathInModel>",
            description = "the rule's full name — its slash-path in the model, e.g. /Order/SomeRule")
    String rule;

    @Option(names = {"--output", "-o"}, paramLabel = "<file>",
            description = "write the result here instead of in place (overwriting the model)")
    String outputPath;

    @Option(names = "--dry-run", description = "preview only — print what would be removed and write NOTHING")
    boolean dryRun;

    @Mixin
    WorkspaceOption workspace = new WorkspaceOption();

    @Override
    public Integer call() {
        String model = model();
        String json = readModelJson(model);
        if (json == null) {
            return 2;
        }
        ModelResolver resolver = workspace.resolverFor(model);
        Optional<Refusal> refusal = new SafetyGate()
                .require(SafetyChecks.ruleExists(KernelAdapter.ofJson(json, resolver), rule))
                .firstFailure();
        if (refusal.isPresent()) {
            return refuse(refusal.get());
        }
        String result;
        BoundModel reduced;
        try {
            // removeRule re-binds through the gate, so the REDUCED model is the witness — the pre-removal
            // bind gated a model this verb is not writing.
            reduced = BoundModel.ofJson(json, resolver).removeRule(rule);
            result = reduced.toJson();
        } catch (RuntimeException e) {
            return fail("cannot remove rule '" + rule + "': " + e.getMessage());
        }

        ObjectNode changed = MAPPER.createObjectNode();
        changed.put("removed", rule);
        return emitMutation(ModelWrite.of(resultIdentity())
                .summaries("would remove rule " + rule,
                        "removed rule " + rule)
                .changed(changed)
                .json(result)
                .gatedBy(ConsistencyEvidence.of(reduced))
                .dryRun(dryRun)
                .destination(outputPath, model));
    }
}
