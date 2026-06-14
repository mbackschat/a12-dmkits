package io.github.mbackschat.a12.dm.cli;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.mgmtp.a12.kernel.md.model.api.services.IDocumentModelReferenceResolver;

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
        if (model == null) {
            return fail("pass -m <model.json> (before or after the verb)");
        }
        String json;
        try {
            json = Files.readString(Path.of(model));
        } catch (IOException e) {
            return fail("cannot read model: " + e.getMessage());
        }
        IDocumentModelReferenceResolver resolver = workspace.resolverFor(model);
        Optional<Refusal> refusal = new SafetyGate()
                .require(SafetyChecks.ruleExists(KernelAdapter.ofJson(json, resolver), rule))
                .firstFailure();
        if (refusal.isPresent()) {
            return refuse("rule", "remove", refusal.get());
        }
        String result;
        try {
            result = BoundModel.ofJson(json, resolver).removeRule(rule).toJson();
        } catch (RuntimeException e) {
            return fail("cannot remove rule '" + rule + "': " + e.getMessage());
        }

        ObjectNode changed = MAPPER.createObjectNode();
        changed.put("removed", rule);
        if (dryRun) {
            ResultEnvelope env = ResultEnvelope.preview("rule", "remove", "would remove rule " + rule, changed);
            out().print(env.toJsonString());
            return env.exitCode();
        }
        String dest = outputPath != null ? outputPath : model;
        try {
            Files.writeString(Path.of(dest), result);
        } catch (IOException e) {
            return fail("cannot write output: " + e.getMessage());
        }
        ResultEnvelope env = ResultEnvelope.applied("rule", "remove", "removed rule " + rule, changed, dest);
        out().print(env.toJsonString());
        return env.exitCode();
    }
}
