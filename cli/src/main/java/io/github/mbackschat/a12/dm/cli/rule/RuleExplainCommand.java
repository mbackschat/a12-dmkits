package io.github.mbackschat.a12.dm.cli.rule;

import io.github.mbackschat.a12.dm.cli.BaseCommand;
import io.github.mbackschat.a12.dm.cli.ModelPaths;
import io.github.mbackschat.a12.dm.cli.ResultEnvelope;
import io.github.mbackschat.a12.dm.cli.WorkspaceOption;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import com.fasterxml.jackson.databind.node.ObjectNode;

import io.github.mbackschat.a12.dm.adapter.KernelAdapter;
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Parameters;

/**
 * {@code dmtool rule explain <rulePath>} — project a rule's condition into a structured {@code
 * RuleExplanation} (operator glossary + a backlinked structural tree + referenced fields + iteration scope
 * + an NL gloss). A read op: the explanation rides {@code data}; an Opaque construct the converter doesn't
 * model comes back as {@code explained:false} + reason (tolerant, never an error). The whole-computation
 * explanation is {@code computation explain}.
 */
@Command(name = "explain", mixinStandardHelpOptions = true,
        description = "Project a rule's condition as a structured explanation (operators, tree, refs, gloss).")
final class RuleExplainCommand extends BaseCommand {

    @Parameters(index = "0", paramLabel = "<rulePathInModel>",
            description = "the rule's full name — its slash-path in the model, e.g. /Order/SomeRule")
    String rule;

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
        try {
            BoundModel bound = BoundModel.ofJson(json, workspace.resolverFor(model));
            KernelAdapter adapter = KernelAdapter.of(bound);
            String problem = ModelPaths.ruleProblem(adapter.ruleFullNames(), adapter.computationFullNames(), rule);
            if (problem != null) {
                return fail(problem);
            }
            ObjectNode data = RuleExplainData.build(adapter, bound, rule);
            String summary = RuleExplainData.isOpaque(data) ? rule + " is Opaque (not modeled)" : "explained " + rule;
            ResultEnvelope env = ResultEnvelope.read("rule", "explain", summary, true, List.of(), data);
            out().print(env.toJsonString());
            return env.exitCode();
        } catch (RuntimeException e) {
            return fail("explain failed: " + e.getMessage());
        }
    }
}
