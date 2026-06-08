package io.github.mbackschat.a12.dm.cli;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import io.github.mbackschat.a12.dm.adapter.ReadResult;
import io.github.mbackschat.a12.dm.adapter.ReadComputation;
import io.github.mbackschat.a12.dm.adapter.KernelAdapter;
import io.github.mbackschat.a12.dm.adapter.ModelRead;
import io.github.mbackschat.a12.dm.rulekit.dsl.Computation;
import io.github.mbackschat.a12.dm.rulekit.dsl.ConditionLanguage;
import io.github.mbackschat.a12.dm.rulekit.dsl.DslRenderer;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

/**
 * The {@code show} verb (CLI-SPEC §4) — read <em>existing</em> conditions back into rulekit's typed AST
 * (F10) and render them as DSL, via the opt-in kernel adapter. (Named {@code show} because it is a
 * read/display verb; the kernel→rulekit conversion it drives is the adapter's {@code read} operation —
 * the verb was renamed off that internal term, which read as an <em>import</em>.) Two modes:
 *
 * <ul>
 *   <li>{@code --rule <path>} — one validation rule's condition;</li>
 *   <li>{@code --all} — the whole model: every validation rule <em>and</em> every computation, as two
 *       disjoint sections plus a summary (the unified whole-model read).</li>
 * </ul>
 *
 * <p>Tolerant: a construct the converter doesn't model yet comes back as {@code Opaque} (preserved
 * verbatim + a reason), never an error. This is the read-back foundation {@code explain} builds on (the
 * richer, annotated read — {@code show} is the plain canonical render).
 */
@Command(name = "read", mixinStandardHelpOptions = true,
        description = "Read existing conditions as DSL, read back into rulekit's typed AST (one --rule, or --all for the whole model).")
final class ReadCommand extends BaseCommand {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final DslRenderer EN = new DslRenderer(ConditionLanguage.EN);

    @Parameters(index = "0", paramLabel = "<model.json>",
            description = "the DM-JSON model FILE (a file path) — distinct from --rule, which is a path inside it")
    String model;

    @Option(names = "--rule", paramLabel = "<rulePathInModel>",
            description = "a single rule's full name — its slash-path in the model, e.g. /Order/SomeRule "
                    + "(group path + rule name; NOT the short name 'SomeRule', the opaque element id, or a file path)")
    String rule;

    @Option(names = "--all",
            description = "show the whole model: every validation rule and every computation")
    boolean all;

    @Mixin
    IncludeDirOption includes = new IncludeDirOption();

    @Override
    public Integer call() {
        if (all == (rule != null)) {                         // exactly one of --rule / --all
            return fail("pass exactly one of --rule <path> or --all");
        }
        String modelJson;
        try {
            modelJson = Files.readString(Path.of(model));
        } catch (IOException e) {
            return fail("cannot read model: " + e.getMessage());
        }
        try {
            KernelAdapter adapter = KernelAdapter.ofJson(modelJson, includes.resolverFor(model));
            return all ? readWholeModel(adapter) : readOneRule(adapter);
        } catch (RuntimeException e) {
            return fail("read failed: " + e.getMessage());
        }
    }

    private Integer readOneRule(KernelAdapter adapter) {
        String problem = ModelPaths.ruleProblem(adapter.ruleFullNames(), adapter.computationFullNames(), rule);
        if (problem != null) {
            return fail(problem);
        }
        ObjectNode root = MAPPER.createObjectNode();
        root.put("rule", rule);
        putRuleOutcome(root, adapter.read(rule));
        out().print(pretty(root));
        return 0;
    }

    private Integer readWholeModel(KernelAdapter adapter) {
        ModelRead m = adapter.readModel();
        ObjectNode root = MAPPER.createObjectNode();
        ObjectNode rules = root.putObject("rules");
        for (Map.Entry<String, ReadResult> e : m.rules().entrySet()) {
            putRuleOutcome(rules.putObject(e.getKey()), e.getValue());
        }
        ObjectNode comps = root.putObject("computations");
        for (Map.Entry<String, ReadComputation> e : m.computations().entrySet()) {
            putComputationOutcome(comps.putObject(e.getKey()), e.getValue());
        }
        ObjectNode summary = root.putObject("summary");
        summary.put("rules", m.rules().size());
        summary.put("convertedRules", m.convertedRules());
        summary.put("computations", m.computations().size());
        summary.put("convertedComputations", m.convertedComputations());
        summary.put("fullyTyped", m.fullyTyped());
        out().print(pretty(root));
        return 0;
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
            Computation c = converted.computation();
            node.put("converted", true);
            node.put("calculatedField", c.computedField().path());
        } else if (read instanceof ReadComputation.Opaque opaque) {
            node.put("converted", false);
            node.put("reason", opaque.reason());
        }
    }

    private static String pretty(ObjectNode root) {
        try {
            return MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(root) + "\n";
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("cannot serialize show result", e);
        }
    }
}
