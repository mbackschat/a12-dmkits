package io.github.mbackschat.a12.dm.cli;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.github.mbackschat.a12.dm.adapter.KernelAdapter;
import io.github.mbackschat.a12.dm.adapter.ModelRead;
import io.github.mbackschat.a12.dm.adapter.ReadComputation;
import io.github.mbackschat.a12.dm.adapter.ReadResult;
import io.github.mbackschat.a12.dm.rulekit.dsl.ConditionLanguage;
import io.github.mbackschat.a12.dm.rulekit.dsl.DslRenderer;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;

/**
 * {@code dmtool model read} — the whole-model read-back: every validation rule AND every computation, as two
 * disjoint sections plus a summary, read into rulekit's typed form (F10). A read op: the read rides {@code
 * data}; Opaque constructs come back verbatim, never an error. The single-element reads are {@code rule
 * read} / {@code computation read}.
 */
@Command(name = "read", mixinStandardHelpOptions = true,
        description = "Read the whole model — every rule and computation, as DSL — into `data`.")
final class ModelReadCommand extends BaseCommand {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final DslRenderer EN = new DslRenderer(ConditionLanguage.EN);

    @Mixin
    IncludeDirOption includes = new IncludeDirOption();

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
            ModelRead m = KernelAdapter.ofJson(json, includes.resolverFor(model)).readModel();
            ObjectNode data = MAPPER.createObjectNode();
            ObjectNode rules = data.putObject("rules");
            for (Map.Entry<String, ReadResult> e : m.rules().entrySet()) {
                putRuleOutcome(rules.putObject(e.getKey()), e.getValue());
            }
            ObjectNode comps = data.putObject("computations");
            for (Map.Entry<String, ReadComputation> e : m.computations().entrySet()) {
                putComputationOutcome(comps.putObject(e.getKey()), e.getValue());
            }
            ObjectNode summary = data.putObject("summary");
            summary.put("rules", m.rules().size());
            summary.put("convertedRules", m.convertedRules());
            summary.put("computations", m.computations().size());
            summary.put("convertedComputations", m.convertedComputations());
            summary.put("fullyTyped", m.fullyTyped());
            ResultEnvelope env = ResultEnvelope.read("model", "read",
                    "read " + m.rules().size() + " rule(s), " + m.computations().size() + " computation(s)",
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

    private static void putComputationOutcome(ObjectNode node, ReadComputation read) {
        if (read instanceof ReadComputation.Converted converted) {
            node.put("converted", true);
            node.put("calculatedField", converted.computation().computedField().path());
        } else if (read instanceof ReadComputation.Opaque opaque) {
            node.put("converted", false);
            node.put("reason", opaque.reason());
        }
    }
}
