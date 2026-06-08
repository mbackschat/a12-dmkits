package io.github.mbackschat.a12.dm.cli;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import io.github.mbackschat.a12.dm.adapter.Ingest;
import io.github.mbackschat.a12.dm.adapter.IngestedComputation;
import io.github.mbackschat.a12.dm.adapter.KernelAdapter;
import io.github.mbackschat.a12.dm.rulekit.context.ModelContext;
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

/**
 * The {@code explain} verb (CLI-SPEC §4 / F9) — project an existing rule or computation into a structured
 * explanation. Two modes:
 *
 * <ul>
 *   <li>{@code --rule <path>} — a validation rule's condition as a {@code RuleExplanation} (operator
 *       glossary + a structural {@code tree} whose nodes backlink into it, referenced fields, iteration
 *       scope, an NL gloss);</li>
 *   <li>{@code --computation <path>} — a computation as a {@code ComputationExplanation} (computed field,
 *       common precondition + per-alternative precondition/operation, messages, gloss).</li>
 * </ul>
 *
 * <p>Ingests the element first (F10), then explains the resulting AST — the same mechanism that works on a
 * hand-built condition.
 */
@Command(name = "explain", mixinStandardHelpOptions = true,
        description = "Project an existing rule (--rule) or computation (--computation) as a structured explanation.")
final class ExplainCommand extends BaseCommand {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Parameters(index = "0", paramLabel = "<model.json>",
            description = "the DM-JSON model FILE (a file path) — distinct from --rule/--computation, paths inside it")
    String model;

    @Option(names = "--rule", paramLabel = "<rulePathInModel>",
            description = "a validation rule's full name — its slash-path in the model, e.g. /Order/SomeRule "
                    + "(group path + rule name; NOT the short name 'SomeRule', the opaque element id, or a file path)")
    String rule;

    @Option(names = "--computation", paramLabel = "<computationPathInModel>",
            description = "a computation's full name — its slash-path in the model, e.g. /Order/TotalComp")
    String computation;

    @Mixin
    IncludeDirOption includes = new IncludeDirOption();

    @Override
    public Integer call() {
        if ((rule != null) == (computation != null)) {       // exactly one of --rule / --computation
            return fail("pass exactly one of --rule <path> or --computation <path>");
        }
        String modelJson;
        try {
            modelJson = Files.readString(Path.of(model));
        } catch (IOException e) {
            return fail("cannot read model: " + e.getMessage());
        }
        try {
            BoundModel boundModel = BoundModel.ofJson(modelJson, includes.resolverFor(model));
            KernelAdapter adapter = KernelAdapter.of(boundModel);   // reuses the BoundModel's resolver
            return rule != null ? explainRule(adapter, boundModel) : explainComputation(adapter, boundModel);
        } catch (RuntimeException e) {
            return fail("explain failed: " + e.getMessage());
        }
    }

    private Integer explainRule(KernelAdapter adapter, BoundModel boundModel) {
        String problem = ModelPaths.ruleProblem(adapter.ruleFullNames(), adapter.computationFullNames(), rule);
        if (problem != null) {
            return fail(problem);
        }
        Ingest ingest = adapter.ingest(rule);
        if (ingest instanceof Ingest.Converted converted) {
            out().print(new ModelContext(boundModel).explain(converted.condition()).toJson() + "\n");
        } else if (ingest instanceof Ingest.Opaque opaque) {
            out().print(notExplained("rule", rule, opaque.reason(), opaque.canonicalText()));
        }
        return 0;
    }

    private Integer explainComputation(KernelAdapter adapter, BoundModel boundModel) {
        if (!adapter.computationFullNames().contains(computation)) {
            return fail("'" + computation + "' is not a computation in this model — pass a computation's full"
                    + " name (its slash-path). Computations in this model: " + adapter.computationFullNames() + ".");
        }
        IngestedComputation ingest = adapter.ingestComputation(computation);
        if (ingest instanceof IngestedComputation.Converted converted) {
            out().print(new ModelContext(boundModel).explainComputation(converted.computation()).toJson() + "\n");
        } else if (ingest instanceof IngestedComputation.Opaque opaque) {
            out().print(notExplained("computation", computation, opaque.reason(), null));
        }
        return 0;
    }

    /** A construct the converter doesn't model yet — report it, don't fail (CLI-SPEC: explain is tolerant). */
    private static String notExplained(String kind, String name, String reason, String canonicalText) {
        ObjectNode root = MAPPER.createObjectNode();
        root.put(kind, name);
        root.put("explained", false);
        root.put("reason", reason);
        if (canonicalText != null) {
            root.put("canonicalText", canonicalText);
        }
        return pretty(root);
    }

    private static String pretty(ObjectNode root) {
        try {
            return MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(root) + "\n";
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("cannot serialize explain result", e);
        }
    }
}
