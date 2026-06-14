package io.github.mbackschat.a12.dm.cli;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import io.github.mbackschat.a12.dm.adapter.KernelAdapter;
import io.github.mbackschat.a12.dm.adapter.ReadResult;
import io.github.mbackschat.a12.dm.rulekit.dsl.ConditionLanguage;
import io.github.mbackschat.a12.dm.rulekit.dsl.DslRenderer;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Parameters;

/**
 * {@code dmtool rule read <rulePath>} — read an existing rule's condition back into rulekit's typed AST
 * (F10) and render it as DSL, via the opt-in kernel adapter. A read op: the read-back rides {@code data}
 * ({@code converted}+{@code condition}, or {@code converted:false}+{@code canonicalText}+{@code reason} for
 * an Opaque construct the converter doesn't model — tolerant, never an error). The whole-model read is
 * {@code model read}; the annotated read is {@code rule explain}.
 */
@Command(name = "read", mixinStandardHelpOptions = true,
        description = "Read a rule's condition back as DSL (typed AST, tolerant of Opaque constructs).")
final class RuleReadCommand extends BaseCommand {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final DslRenderer EN = new DslRenderer(ConditionLanguage.EN);

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
            KernelAdapter adapter = KernelAdapter.ofJson(json, workspace.resolverFor(model));
            String problem = ModelPaths.ruleProblem(adapter.ruleFullNames(), adapter.computationFullNames(), rule);
            if (problem != null) {
                return fail(problem);
            }
            ReadResult read = adapter.read(rule);
            ObjectNode data = MAPPER.createObjectNode();
            data.put("rule", rule);
            putRuleOutcome(data, read);
            ResultEnvelope env = ResultEnvelope.read("rule", "read",
                    read instanceof ReadResult.Converted ? "read " + rule : "read " + rule + " (Opaque)",
                    true, List.of(), data);
            out().print(env.toJsonString());
            return env.exitCode();
        } catch (RuntimeException e) {
            return fail("read failed: " + e.getMessage());
        }
    }

    private static void putRuleOutcome(ObjectNode node, ReadResult read) {
        if (read instanceof ReadResult.Converted converted) {
            node.put("converted", true);
            node.put("condition", EN.render(converted.condition()));
        } else if (read instanceof ReadResult.Opaque opaque) {
            node.put("converted", false);
            node.put("canonicalText", opaque.canonicalText());
            node.put("reason", opaque.reason());
        }
    }
}
