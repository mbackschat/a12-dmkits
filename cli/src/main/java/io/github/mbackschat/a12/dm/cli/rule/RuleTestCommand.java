package io.github.mbackschat.a12.dm.cli.rule;

import io.github.mbackschat.a12.dm.cli.BaseCommand;
import io.github.mbackschat.a12.dm.cli.DocInstance;
import io.github.mbackschat.a12.dm.cli.ModelPaths;
import io.github.mbackschat.a12.dm.cli.ResultEnvelope;
import io.github.mbackschat.a12.dm.cli.WorkspaceOption;

import java.io.IOException;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import io.github.mbackschat.a12.dm.adapter.KernelAdapter;
import io.github.mbackschat.a12.dm.adapter.RuleTestOutcome;
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

/**
 * {@code dmtool rule test <rulePath> --doc <document.json>} — the scoped, suppression-aware polarity check
 * for ONE rule against a document INSTANCE (FEATURE-GAPS §3.3). The rule-first convenience over {@code doc
 * eval}: it answers <b>fired</b> / <b>passed</b> / <b>suppressed</b> — the last being a rule the kernel never
 * evaluated because a field it references is formally invalid (reporting that as a pass would mislead, so a
 * plain "did it fire" verb isn't enough). Read-only; the verdict rides {@code data}.
 */
@Command(name = "test", mixinStandardHelpOptions = true,
        description = "Test ONE rule against a document INSTANCE: fired / passed / suppressed (runtime polarity).")
final class RuleTestCommand extends BaseCommand {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Parameters(index = "0", paramLabel = "<rulePathInModel>",
            description = "the rule's full name — its slash-path in the model, e.g. /Order/SomeRule")
    String rule;

    @Option(names = "--doc", required = true, paramLabel = "<document.json>",
            description = "the document INSTANCE (field data) — a nested JSON tree under \"fields\". See `schema model eval`.")
    String doc;

    @Option(names = "--locale", defaultValue = "en_US", paramLabel = "<locale>",
            description = "the evaluation locale (default: ${DEFAULT-VALUE})")
    String localeTag;

    @Mixin
    WorkspaceOption workspace = new WorkspaceOption();

    @Override
    public Integer call() {
        String modelPath = model();
        String json = readModelJson(modelPath);   // null (after printing the failure) if no -m / unreadable
        if (json == null) {
            return 2;
        }
        Map<String, Object> fields;
        try {
            fields = DocInstance.readFields(doc);
        } catch (IllegalArgumentException badShape) {
            return fail(badShape.getMessage());
        } catch (IOException e) {
            return fail("cannot read document: " + e.getMessage());
        }
        Locale locale = Locale.forLanguageTag(localeTag.replace('_', '-'));
        try {
            BoundModel bound = BoundModel.ofJson(json, workspace.resolverFor(modelPath));
            KernelAdapter adapter = KernelAdapter.of(bound);
            String problem = ModelPaths.ruleProblem(adapter.ruleFullNames(), adapter.computationFullNames(), rule);
            if (problem != null) {
                return fail(problem);
            }
            RuleTestOutcome outcome = adapter.testRule(rule, fields, locale);
            ResultEnvelope env = ResultEnvelope.read("rule", "test", summary(outcome), true, List.of(), data(outcome));
            out().print(env.toJsonString());
            return env.exitCode();
        } catch (IllegalArgumentException badDoc) {
            return fail(badDoc.getMessage());
        } catch (RuntimeException e) {
            return fail("rule test failed: " + e.getMessage());
        }
    }

    private static String summary(RuleTestOutcome outcome) {
        return switch (outcome.verdict()) {
            case FIRED -> outcome.rule() + " FIRED on this instance (a violation)";
            case PASSED -> outcome.rule() + " passed (evaluated; condition false)";
            case SUPPRESSED -> outcome.rule() + " was NOT evaluated — a referenced field is formally invalid";
        };
    }

    private static ObjectNode data(RuleTestOutcome outcome) {
        ObjectNode data = MAPPER.createObjectNode();
        data.put("rule", outcome.rule());
        data.put("fired", outcome.fired());
        data.put("verdict", outcome.verdict().name().toLowerCase(Locale.ROOT));
        outcome.message().ifPresent(m -> data.put("message", m));
        if (!outcome.suppressedBy().isEmpty()) {
            ArrayNode arr = data.putArray("suppressedBy");
            for (RuleTestOutcome.Suppression s : outcome.suppressedBy()) {
                ObjectNode node = arr.addObject();
                node.put("field", s.field());
                node.put("formalErrorCode", s.formalErrorCode());
            }
        }
        return data;
    }
}
