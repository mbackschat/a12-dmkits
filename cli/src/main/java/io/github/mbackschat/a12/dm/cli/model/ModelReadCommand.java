package io.github.mbackschat.a12.dm.cli.model;

import io.github.mbackschat.a12.dm.cli.BaseCommand;
import io.github.mbackschat.a12.dm.cli.ReadPayloads;
import io.github.mbackschat.a12.dm.cli.ConsistencyEvidence;
import io.github.mbackschat.a12.dm.cli.ResultEnvelope;
import io.github.mbackschat.a12.dm.cli.WorkspaceOption;

import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.github.mbackschat.a12.dm.adapter.KernelAdapter;
import io.github.mbackschat.a12.dm.adapter.ModelRead;
import io.github.mbackschat.a12.dm.rulekit.read.ReadComputation;
import io.github.mbackschat.a12.dm.rulekit.read.ReadResult;
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

    @Mixin
    WorkspaceOption workspace = new WorkspaceOption();

    @Override
    public Integer call() {
        String model = model();
        String json = readModelJson(model);
        if (json == null) {
            return 2;
        }
        try {
            KernelAdapter adapter = KernelAdapter.ofJson(json, workspace.resolverFor(model));
            ModelRead m = adapter.readModel();
            ObjectNode data = MAPPER.createObjectNode();
            ObjectNode rules = data.putObject("rules");
            for (Map.Entry<String, ReadResult> e : m.rules().entrySet()) {
                ReadPayloads.putRuleRead(rules.putObject(e.getKey()), e.getValue());
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
            // This verb WAS gated all along (KernelAdapter.ofJson -> checkAndOpen), so its old `valid:true` was
            // coincidentally correct — it had already bailed on an inconsistent model. Coincidence is not a
            // contract: the gate is now reported as provenance and the unearned verdict is gone.
            ResultEnvelope env = ResultEnvelope.queryResult(resultIdentity(),
                    "read " + m.rules().size() + " rule(s), " + m.computations().size() + " computation(s)",
                    data, ConsistencyEvidence.of(adapter));
            out().print(env.toJsonString());
            return env.exitCode();
        } catch (RuntimeException e) {
            throw withFailureContext("read failed: ", e);
        }
    }

    /** Exhaustive for the reason {@code ReadPayloads.putRuleRead} states; a computation has no refused state. */
    private static void putComputationOutcome(ObjectNode node, ReadComputation read) {
        switch (read) {
            case ReadComputation.Converted converted -> {
                node.put("converted", true);
                node.put("calculatedField", converted.computation().computedField().path().value());
            }
            case ReadComputation.Opaque opaque -> {
                node.put("converted", false);
                node.put("reason", opaque.reason());
            }
        }
    }
}
