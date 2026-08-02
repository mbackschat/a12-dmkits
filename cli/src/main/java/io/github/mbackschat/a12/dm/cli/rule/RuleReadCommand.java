package io.github.mbackschat.a12.dm.cli.rule;

import io.github.mbackschat.a12.dm.cli.BaseCommand;
import io.github.mbackschat.a12.dm.cli.ConsistencyEvidence;
import io.github.mbackschat.a12.dm.cli.ModelPaths;
import io.github.mbackschat.a12.dm.cli.ResultEnvelope;
import io.github.mbackschat.a12.dm.cli.WorkspaceOption;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import io.github.mbackschat.a12.dm.adapter.KernelAdapter;
import io.github.mbackschat.a12.dm.rulekit.read.ReadResult;
import io.github.mbackschat.a12.dm.modelkit.ModelDocument;
import io.github.mbackschat.a12.dm.rulekit.dsl.ConditionLanguage;
import io.github.mbackschat.a12.dm.rulekit.dsl.DslRenderer;
import io.github.mbackschat.a12.dm.rulekit.validate.RkCode;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Option;
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

    @Parameters(index = "0", arity = "0..1", paramLabel = "<rulePathInModel>",
            description = "the rule's full name — its slash-path in the model, e.g. /Order/SomeRule; "
                    + "omit to list every rule's full name (or address it with --rule)")
    String rule;

    @Option(names = "--rule", paramLabel = "<rulePathInModel>",
            description = "the rule's full name — the named-flag alias for the positional (the locator contract: "
                    + "the target-noun flag always works). The positional wins if both are given.")
    String ruleFlag;

    @Mixin
    WorkspaceOption workspace = new WorkspaceOption();

    @Override
    public Integer call() {
        String model = model();
        if (model == null) {
            return fail("pass -m <model.json> (before or after the verb)");
        }
        if (rule == null) {   // locator contract: --rule is the named alias for the positional
            rule = ruleFlag;
        }
        String json;
        try {
            json = Files.readString(Path.of(model));
        } catch (IOException e) {
            return fail("cannot read model: " + e.getMessage());
        }
        try {
            KernelAdapter adapter = KernelAdapter.ofJson(json, workspace.resolverFor(model));
            if (rule == null) {
                return readNameIndex("rules", adapter.ruleFullNames(), ConsistencyEvidence.of(adapter));
            }
            String problem = ModelPaths.ruleProblem(adapter.ruleFullNames(), adapter.computationFullNames(), rule);
            if (problem != null) {
                // structured refusal (RK_NO_SUCH_RULE), carrying the rich `problem` corrective (which detects
                // a computation mistaken for a rule, a short-name/file-path slip, and lists the rules) as the
                // fix — so a missing read target is machine-readable, not a plain-text stderr line
                return refuseNotFound(RkCode.NO_SUCH_RULE, "no rule at '" + rule + "'", rule, problem);
            }
            ReadResult read = adapter.read(rule);
            ObjectNode data = MAPPER.createObjectNode();
            data.put("rule", rule);
            data.put("severity", adapter.ruleSeverity(rule));
            data.put("errorCode", adapter.ruleErrorCode(rule));
            adapter.ruleErrorField(rule).ifPresent(f -> data.put("errorField", f));
            putRuleOutcome(data, read);
            RuleMessages.putInto(data, adapter.ruleMessages(rule));
            // target-specific reads retain their metadata projection (G45); `meta` is the write owner
            // (rules have no label slot). Read from ModelDocument — the metadata sits on the element node,
            // not the KernelAdapter's condition/message view.
            ModelDocument doc = ModelDocument.read(json);
            putIfNotEmpty(data, "annotations", doc.annotations(rule));
            putIfNotEmpty(data, "internalDescription", doc.internalDescription(rule));
            putIfNotEmpty(data, "externalDescription", doc.externalDescription(rule));
            ResultEnvelope env = ResultEnvelope.queryResult(resultIdentity(),
                    read instanceof ReadResult.Converted ? "read " + rule : "read " + rule + " (Opaque)",
                    data, ConsistencyEvidence.of(adapter));
            out().print(env.toJsonString());
            return env.exitCode();
        } catch (RuntimeException e) {
            return failOrRethrowDomainRejection("read failed: ", e);
        }
    }

    /** Echo a per-locale/per-name metadata map under {@code key}, only when it carries something (mirrors group read). */
    private static void putIfNotEmpty(ObjectNode data, String key, Map<String, String> m) {
        if (m != null && !m.isEmpty()) {
            data.set(key, MAPPER.valueToTree(m));
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
