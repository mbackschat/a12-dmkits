package io.github.mbackschat.a12.dm.cli.rule;

import io.github.mbackschat.a12.dm.cli.BaseCommand;
import io.github.mbackschat.a12.dm.cli.CustomSupportOptions;
import io.github.mbackschat.a12.dm.cli.DocInstance;
import io.github.mbackschat.a12.dm.cli.ParamKind;
import io.github.mbackschat.a12.dm.cli.ResultEnvelope;
import io.github.mbackschat.a12.dm.cli.WorkspaceOption;
import io.github.mbackschat.a12.dm.cli.interp.IncludeRelativizationException;
import io.github.mbackschat.a12.dm.cli.interp.InterpreterEngine;
import io.github.mbackschat.a12.dm.core.UnresolvedReferenceException;

import java.io.IOException;
import java.util.List;
import java.util.Map;

import io.github.mbackschat.a12.dm.rulekit.validate.RkCode;

import com.fasterxml.jackson.databind.node.ObjectNode;

import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

/**
 * {@code dmtool rule eval <rulePath> --instance <document.json>} — the rule-scope RUNTIME verb: the scoped,
 * suppression-aware polarity check for ONE rule against a document INSTANCE (FEATURE-GAPS). The rule-first
 * peer of {@code model eval}: it answers <b>fired</b> / <b>passed</b> / <b>suppressed</b> — the last being a
 * rule the engine never evaluated because a field it references is formally invalid (reporting that as a pass
 * would mislead, so a plain "did it fire" verb isn't enough). Read-only; the verdict rides {@code data}.
 *
 * <p>Runtime, single-rule scope — the runtime peer of the static candidate-check {@code rule check}; like
 * {@code model eval} it runs the form-engine flow (compute → apply → validate) by default ({@code
 * --no-computations} opts to raw). Evaluation runs on the native-safe {@code dm.interpreter} — the sole runtime
 * eval engine on every target (the user-facing {@code --kernel} eval flag was retired). See the
 * check/eval/compute taxonomy in CLI-SPEC.
 */
@Command(name = "eval", mixinStandardHelpOptions = true,
        description = "Evaluate ONE rule against a document INSTANCE: fired / passed / suppressed (runtime "
                + "polarity; applies computations first by default, like model eval).")
final class RuleEvalCommand extends BaseCommand {

    @ParamKind(ParamKind.Kind.RULE_PATH)
    @Parameters(index = "0", paramLabel = "<rulePathInModel>",
            description = "the rule's full name — its slash-path in the model, e.g. /Order/SomeRule")
    String rule;

    @Option(names = "--instance", required = true, paramLabel = "<document.json>",
            description = "the document INSTANCE (field data) — a nested JSON tree under \"fields\". See `schema model eval`.")
    String instance;

    @Option(names = "--locale", defaultValue = "en_US", paramLabel = "<locale>",
            description = "the evaluation locale (default: ${DEFAULT-VALUE})")
    String localeTag;

    @Option(names = "--no-computations",
            description = "evaluate against the document's STORED values as-is. By DEFAULT, rule eval first runs "
                    + "the model's computations and applies their values (the form-engine flow), so a rule over a "
                    + "COMPUTED field sees the computed value — matching the running app. Use this to evaluate against "
                    + "stored values directly.")
    boolean noComputations;

    @Mixin
    WorkspaceOption workspace = new WorkspaceOption();

    @Mixin
    CustomSupportOptions customSupport = new CustomSupportOptions();

    @Override
    public Integer call() {
        String modelPath = model();
        String json = readModelJson(modelPath);   // null (after printing the failure) if no -m / unreadable
        if (json == null) {
            return 2;
        }
        Map<String, Object> fields;
        try {
            fields = DocInstance.readFields(instance);
        } catch (IllegalArgumentException badShape) {
            return fail(badShape.getMessage());
        } catch (IOException e) {
            return fail("cannot read document: " + e.getMessage());
        }
        try {
            // Pre-expand kernel-free so an includes-bearing model evaluates with its mounted content present
            // (decoupling Stage 2 / A4); refuse loudly if a surviving included element retains an unmapped ref.
            String interpJson;
            try {
                interpJson = InterpreterEngine.expandForEval(json, workspace.resolverFor(modelPath));
            } catch (IncludeRelativizationException e) {
                return refusePrecheck("rule", "eval", RkCode.INTERPRETER_INCLUDE_RULES_UNSUPPORTED,
                        e.getMessage(), modelPath, RkCode.INTERPRETER_INCLUDE_RULES_UNSUPPORTED.fix());
            } catch (UnresolvedReferenceException e) {
                return refusePrecheck("rule", "eval", RkCode.UNRESOLVED_REFERENCE, e.getMessage(), modelPath,
                        RkCode.UNRESOLVED_REFERENCE.fix());
            }
            // a structured refusal — the interpreter otherwise throws "no such rule", a bare error.
            if (!InterpreterEngine.ruleFullNames(interpJson).contains(rule)) {
                return refuseNotFound("rule", "eval", RkCode.NO_SUCH_RULE,
                        "rule '" + rule + "' does not exist — refusing", rule, RkCode.NO_SUCH_RULE.fix());
            }
            ObjectNode data = InterpreterEngine.ruleTestData(interpJson, fields, localeTag, rule,
                    customSupport.predefinedTypes(), customSupport.customConditions(),
                    customSupport.customFieldTypes(), !noComputations);
            ResultEnvelope refusal = customSupport.strictRefusal("rule", "eval", data);
            if (refusal != null) {
                out().print(refusal.toJsonString());
                return refusal.exitCode();
            }
            ResultEnvelope env = ResultEnvelope.read("rule", "eval", summary(data), true, List.of(), data);
            out().print(env.toJsonString());
            return env.exitCode();
        } catch (IllegalArgumentException badDoc) {
            return fail(badDoc.getMessage());
        } catch (RuntimeException e) {
            return fail("rule eval failed: " + e.getMessage());
        }
    }

    /** The human summary from the verdict + rule in {@code data}. */
    private static String summary(ObjectNode data) {
        String r = data.path("rule").asText();
        return switch (data.path("verdict").asText()) {
            case "fired" -> r + " FIRED on this instance (a violation)";
            case "passed" -> r + " passed (evaluated; condition false)";
            case "suppressed" -> r + " was NOT evaluated — a referenced field is formally invalid";
            case "unsupported" -> r + " could NOT be evaluated — it uses a construct the engine doesn't model "
                    + "(e.g. a CustomCondition with no impl)";
            default -> r;
        };
    }
}
