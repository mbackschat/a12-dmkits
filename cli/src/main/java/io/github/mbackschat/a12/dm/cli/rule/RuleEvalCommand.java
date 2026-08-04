package io.github.mbackschat.a12.dm.cli.rule;

import io.github.mbackschat.a12.dm.cli.BaseCommand;
import io.github.mbackschat.a12.dm.cli.CustomSupportOptions;
import io.github.mbackschat.a12.dm.cli.ModelPaths;
import io.github.mbackschat.a12.dm.cli.ParamKind;
import io.github.mbackschat.a12.dm.cli.ResultEnvelope;
import io.github.mbackschat.a12.dm.cli.RuntimeEvidence;
import io.github.mbackschat.a12.dm.cli.WorkspaceOption;
import io.github.mbackschat.a12.dm.cli.interp.InterpreterEngine;
import io.github.mbackschat.a12.dm.interpreter.DocumentInputError;
import io.github.mbackschat.a12.dm.interpreter.ModelPreparationError;
import io.github.mbackschat.a12.dm.interpreter.model.UndeclaredReference;

import java.nio.file.Path;
import java.util.List;

import io.github.mbackschat.a12.dm.interpreter.UnsupportedModelTimeZoneException;
import io.github.mbackschat.a12.dm.interpreter.InvalidModelTimeZoneException;
import io.github.mbackschat.a12.dm.rulekit.validate.RkCode;

import com.fasterxml.jackson.databind.node.ObjectNode;

import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

/**
 * {@code dmtool rule eval <rulePath> --instance <document.json>} — the rule-scope RUNTIME verb: the scoped,
 * suppression-aware polarity check for ONE stored rule or a non-stored {@code --condition}/{@code --field}
 * candidate against a document INSTANCE (FEATURE-GAPS). The rule-first
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
        description = "Evaluate ONE stored rule, or a non-stored --condition/--field candidate, against a document "
                + "INSTANCE: fired / passed / suppressed (runtime "
                + "polarity; applies computations first by default, like model eval). For the whole model's "
                + "fired-set — or one rule within that report — use `model eval` (with --rule).")
final class RuleEvalCommand extends BaseCommand {

    @ParamKind(ParamKind.Kind.RULE_PATH)
    @Parameters(index = "0", arity = "0..1", paramLabel = "<rulePathInModel>",
            description = "a stored rule's full slash-path, e.g. /Order/SomeRule; omit for candidate mode")
    String rule;

    @Option(names = "--condition", paramLabel = "<DSL>",
            description = "evaluate a NON-STORED candidate rule (with --field): true on a violation")
    String condition;

    @Option(names = "--field", paramLabel = "<fieldPathInModel>",
            description = "the candidate's error field as an absolute model path (with --condition); its parent "
                    + "is the iteration scope")
    String field;

    @Option(names = "--code", defaultValue = "EVAL_RULE", paramLabel = "<errorCode>",
            description = "the candidate's error code (default: ${DEFAULT-VALUE})")
    String code;

    @Option(names = "--instance", required = true, paramLabel = "<document.json>",
            description = "the document INSTANCE (field data) in A12's canonical Document JSON — a nested tree "
                    + "mirroring the model, keyed by root group name. See `schema model eval`.")
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
        boolean candidate = condition != null || field != null;
        if (candidate && rule != null) {
            return fail("pass either a stored <rulePathInModel> OR a candidate (--condition + --field), not both");
        }
        if (candidate && (condition == null || field == null)) {
            return fail("a candidate rule needs both --condition and --field");
        }
        if (!candidate && rule == null) {
            return fail("pass either a stored <rulePathInModel> or a candidate (--condition + --field)");
        }
        String candidateGroup = null;
        String candidateErrorEntityRelPath = null;
        if (candidate) {
            int slash = field.lastIndexOf('/');
            if (!ModelPaths.isAbsoluteEntityPath(field)) {
                return fail("--field must be an absolute path (e.g. /Group/Field)");
            }
            candidateGroup = field.substring(0, slash);
            candidateErrorEntityRelPath = "../" + field.substring(slash + 1);
        }
        String modelPath = model();
        String json = readModelJson(modelPath);   // null (after printing the failure) if no -m / unreadable
        if (json == null) {
            return 2;
        }
        String documentJson = readInstanceJson(instance);
        if (documentJson == null) {
            return 2;
        }
        try {
            // Use the public portable workspace so included content is mounted before rule evaluation.
            String interpJson = InterpreterEngine.prepareForEval(
                    json, Path.of(modelPath).getFileName().toString(), workspace.resolverFor(modelPath));
            if (candidate && !InterpreterEngine.fieldFullNames(interpJson).contains(field)) {
                return refuseNotFound(RkCode.NO_SUCH_FIELD,
                        "field '" + field + "' does not exist — refusing candidate evaluation",
                        field, RkCode.NO_SUCH_FIELD.fix());
            }
            ObjectNode data;
            if (candidate) {
                InterpreterEngine.CandidateRule spec = InterpreterEngine.CandidateRule.builder()
                        .locale(localeTag).group(candidateGroup).name("EvalRuleCandidate")
                        .errorEntityRelPath(candidateErrorEntityRelPath).condition(condition).code(code)
                        .build();
                // The condition's own references get the same treatment as --field's: an entity the model does
                // not declare makes the rule impossible, so evaluating it would answer confidently about
                // nothing (G46). Scoped to what this focused call actually reaches — the candidate alone, plus
                // the computations only when the form-engine flow applies them.
                List<UndeclaredReference> undeclared = InterpreterEngine.undeclaredCandidateReferences(
                        interpJson, spec, InterpreterEngine.Computations.of(!noComputations));
                if (!undeclared.isEmpty()) {
                    return refuseUndeclaredReferences(undeclared);
                }
                data = InterpreterEngine.ruleTestCandidateData(interpJson, documentJson, spec,
                        customSupport.predefinedTypes(), customSupport.customConditions(),
                        customSupport.customFieldTypes(), !noComputations);
            } else {
                // a structured refusal — the interpreter otherwise throws "no such rule", a bare error.
                if (!InterpreterEngine.ruleFullNames(interpJson).contains(rule)) {
                    return refuseNotFound(RkCode.NO_SUCH_RULE,
                            "rule '" + rule + "' does not exist — refusing", rule, RkCode.NO_SUCH_RULE.fix());
                }
                List<UndeclaredReference> undeclared = InterpreterEngine.undeclaredRuleReferences(
                        interpJson, rule, InterpreterEngine.Computations.of(!noComputations));
                if (!undeclared.isEmpty()) {
                    return refuseUndeclaredReferences(undeclared);
                }
                data = InterpreterEngine.ruleTestData(interpJson, documentJson, localeTag, rule,
                        customSupport.predefinedTypes(), customSupport.customConditions(),
                        customSupport.customFieldTypes(), !noComputations);
            }
            RuntimeEvidence evidence = RuntimeEvidence.interpreter();
            ResultEnvelope refusal = customSupport.strictRefusal(resultIdentity(), data, evidence);
            if (refusal != null) {
                out().print(refusal.toJsonString());
                return refusal.exitCode();
            }
            ResultEnvelope env = ResultEnvelope.runtimeObservation(resultIdentity(), summary(data), data,
                    evidence);
            out().print(env.toJsonString());
            return env.exitCode();
        } catch (UnsupportedModelTimeZoneException e) {
            return refuseExplainedPrecheck(RkCode.INTERPRETER_TIMEZONE_UNSUPPORTED, e.getMessage(),
                    modelPath, RkCode.INTERPRETER_TIMEZONE_UNSUPPORTED.fix());
        } catch (InvalidModelTimeZoneException e) {
            return refuseExplainedPrecheck(RkCode.INVALID_CONFIG_VALUE, e.getMessage(),
                    modelPath, RkCode.INVALID_CONFIG_VALUE.fix());
        } catch (DocumentInputError badDoc) {
            return failInvalidInstance(badDoc);
        } catch (IllegalArgumentException badDoc) {
            return fail(badDoc.getMessage());
        } catch (ModelPreparationError e) {
            throw e;
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
