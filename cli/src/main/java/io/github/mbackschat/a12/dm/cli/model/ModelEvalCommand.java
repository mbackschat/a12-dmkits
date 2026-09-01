package io.github.mbackschat.a12.dm.cli.model;

import io.github.mbackschat.a12.dm.cli.BaseCommand;
import io.github.mbackschat.a12.dm.cli.CustomSupportOptions;
import io.github.mbackschat.a12.dm.cli.ModelPaths;
import io.github.mbackschat.a12.dm.cli.ParamKind;
import io.github.mbackschat.a12.dm.cli.ResultEnvelope;
import io.github.mbackschat.a12.dm.cli.RuntimeEvidence;
import io.github.mbackschat.a12.dm.cli.WorkspaceOption;
import io.github.mbackschat.a12.dm.cli.interp.InterpreterEngine;

import java.nio.file.Path;
import java.util.List;

import io.github.mbackschat.a12.dm.core.EntityPaths;
import io.github.mbackschat.a12.dm.interpreter.DocumentInputError;
import io.github.mbackschat.a12.dm.interpreter.UnsupportedModelTimeZoneException;
import io.github.mbackschat.a12.dm.interpreter.InvalidModelTimeZoneException;
import io.github.mbackschat.a12.dm.interpreter.model.UndeclaredReference;
import io.github.mbackschat.a12.dm.rulekit.validate.RkCode;

import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.mbackschat.a12.dm.core.ModelResolver;

import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Option;

/**
 * {@code dmtool model eval --instance <document.json>} — the runtime, model-scope evaluation verb (the runtime
 * peer of the static {@code model check}): evaluate a document INSTANCE and report which rules FIRED (the
 * empirical polarity check). By DEFAULT it runs the <b>form-engine flow</b> — compute → apply → validate — so a
 * required-or-referenced computed field is filled before validation (matching the running app); {@code
 * --no-computations} drops to the bare {@code validateFull} over stored values. Read-only: the {@code
 * fired}/{@code messages}/{@code rule} payload rides {@code data}. Narrow with {@code --rule} (existing) or a
 * candidate ({@code --condition} + {@code --field}, injected, not persisted). {@code model compute} reports the
 * computed values themselves.
 *
 * <p>Evaluation runs on the native-safe {@code dm.interpreter} — the sole runtime eval engine on every target
 * (the user-facing {@code --kernel} eval flag was retired, INTERPRETER-DROPIN-CERTIFICATE); the kernel remains
 * the {@code :adapter} differential oracle in test scope only.
 */
@Command(name = "eval", mixinStandardHelpOptions = true,
        description = "Evaluate a document INSTANCE: report which rules fire in `data` (runtime; applies "
                + "computations first by default — the form-engine flow — unless --no-computations).")
final class ModelEvalCommand extends BaseCommand {

    @Option(names = "--instance", required = true, paramLabel = "<document.json>",
            description = "the document INSTANCE (field data) in A12's canonical Document JSON — a nested tree "
                    + "mirroring the model, keyed by root group name. See `schema model eval`.")
    String instance;

    @ParamKind(ParamKind.Kind.RULE_PATH)
    @Option(names = "--rule", paramLabel = "<rulePathInModel>",
            description = "narrow the report to an EXISTING rule (its full slash-path); for a single rule's "
                    + "fired/passed/suppressed verdict, `rule eval` is the focused verb")
    String ruleId;

    @Option(names = "--condition", paramLabel = "<DSL>",
            description = "evaluate a CANDIDATE rule (with --field): a condition true on a VIOLATION; injected, not persisted")
    String condition;

    @Option(names = "--field", paramLabel = "<fieldPathInModel>",
            description = "the candidate rule's error field as a path INSIDE the model (with --condition)")
    String field;

    @Option(names = "--group", paramLabel = "<groupPathInModel>",
            description = "the candidate rule's iteration scope (group path); defaults to the field's parent"
                    + "; this verb additionally requires the error field to lie INSIDE it, which is stricter than the kernel — the kernel demands containment only once the rule ITERATES, so a non-iterating rule it would admit is refused here (a deliberate narrowing, not a kernel law)")
    String group;

    @Option(names = "--code", defaultValue = "EVAL_DOC", paramLabel = "<errorCode>",
            description = "the candidate rule's error code (default: ${DEFAULT-VALUE})")
    String code;

    @Option(names = "--severity", defaultValue = "ERROR", paramLabel = "ERROR|WARNING",
            description = "the candidate rule's severity (default: ${DEFAULT-VALUE})")
    String severity;

    @Option(names = "--name", defaultValue = "EvalDocCandidate", paramLabel = "<name>",
            description = "the candidate rule's name (default: ${DEFAULT-VALUE})")
    String name;

    @Option(names = "--locale", defaultValue = "en_US", paramLabel = "<locale>",
            description = "the evaluation locale (default: ${DEFAULT-VALUE})")
    String localeTag;

    @Option(names = "--relevant", paramLabel = "<json>",
            description = "PARTIAL validation: JSON array of current-page field/group instance paths. Omitted "
                    + "indices or [*] mean every repetition (ALL_SEMANTIC); [n] selects one row, e.g. "
                    + "'[\"/Order/Name\",\"/Order/Lines[2]/Quantity\"]'. Global fields are auto-relevant.")
    String relevant;

    @Option(names = "--no-computations",
            description = "validate the document's STORED values as-is (the bare validateFull). By DEFAULT, "
                    + "eval first runs the model's computations and applies their values (the form-engine flow), so a "
                    + "required-or-referenced COMPUTED field is filled before validation — matching the running app. "
                    + "Use this to validate stored values directly, e.g. to check a computed field's stored value "
                    + "against its computation. Computations are reported separately by `model compute`.")
    boolean noComputations;

    @Mixin
    WorkspaceOption workspace = new WorkspaceOption();

    @Mixin
    CustomSupportOptions customSupport = new CustomSupportOptions();

    @Override
    public Integer call() {
        String model = model();
        boolean candidate = condition != null || field != null;
        if (candidate && ruleId != null) {
            return fail("pass either --rule <name> OR a candidate (--condition + --field), not both");
        }
        if (candidate && (condition == null || field == null)) {
            return fail("a candidate rule needs both --condition and --field");
        }
        String modelJson = readModelJson(model);
        if (modelJson == null) {
            return 2;
        }
        String documentJson = readInstanceJson(instance);
        if (documentJson == null) {
            return 2;
        }
        ModelResolver resolver = workspace.resolverFor(model);

        // Resolve a candidate's group + error relpath.
        String effectiveGroup = group;
        String errorEntityRelPath = null;
        if (candidate) {
            if (!ModelPaths.isAbsoluteEntityPath(field)) {
                return refuseOptionValue(RkCode.INVALID_VALUE,
                    "--field must be an absolute path (e.g. /Group/Field), got: " + field,
                    "--field", null, RkCode.INVALID_VALUE.fix());
            }
            if (effectiveGroup == null) {
                int slash = field.lastIndexOf('/');
                effectiveGroup = field.substring(0, slash);
            }
            if (!EntityPaths.isStrictlyInside(field, effectiveGroup)) {
                return refuseOptionValue(RkCode.ERROR_FIELD_OUTSIDE_GROUP,
                    "--field " + field + " must be inside --group " + effectiveGroup,
                    "--field", field, RkCode.ERROR_FIELD_OUTSIDE_GROUP.fix());
            }
            errorEntityRelPath = "../" + field.substring(effectiveGroup.length() + 1);
        }

        // Use the public portable workspace so an includes-bearing model evaluates over its mounted content.
        String interpJson =
                InterpreterEngine.prepareForEval(modelJson, Path.of(model).getFileName().toString(), resolver);

        if (candidate && !InterpreterEngine.fieldFullNames(interpJson).contains(field)) {
            return refuseNotFound(RkCode.NO_SUCH_FIELD,
                    "field '" + field + "' does not exist — refusing candidate evaluation",
                    field, RkCode.NO_SUCH_FIELD.fix());
        }
        InterpreterEngine.CandidateRule spec = !candidate ? null : InterpreterEngine.CandidateRule.builder()
                .locale(localeTag).group(effectiveGroup).name(name).errorEntityRelPath(errorEntityRelPath)
                .condition(condition).code(code).severity(severity)
                .build();
        // The condition's own references, held to the same bar as --field's (G46). A stored rule is checked too:
        // dmtool's write paths gate every reference, but a model that arrived from anywhere else was never gated.
        // Whole-model either way — a candidate here is appended to the model and evaluated alongside every stored
        // rule — and the computations stay in scope even under --no-computations, because validation evaluates
        // the implicit computation-validation rules regardless (IF32/IF65).
        List<UndeclaredReference> undeclared = candidate
                ? InterpreterEngine.undeclaredReferencesWithCandidate(interpJson, spec)
                : InterpreterEngine.undeclaredReferences(interpJson);
        if (!undeclared.isEmpty()) {
            return refuseUndeclaredReferences(undeclared);
        }

        ObjectNode data;
        try {
            if (candidate) {
                data = InterpreterEngine.evalCandidateData(interpJson, documentJson, spec,
                        customSupport.predefinedTypes(), customSupport.customConditions(),
                        customSupport.customFieldTypes(), !noComputations, relevant);
            } else {
                // a structured refusal for an unknown --rule narrowing — otherwise it would silently report
                // the missing rule as fired:false.
                if (ruleId != null && !InterpreterEngine.ruleFullNames(interpJson).contains(ruleId)) {
                    return refuseNotFound(RkCode.NO_SUCH_RULE,
                            "rule '" + ruleId + "' does not exist — refusing", ruleId, RkCode.NO_SUCH_RULE.fix());
                }
                data = InterpreterEngine.evalData(interpJson, documentJson, localeTag, ruleId,
                        customSupport.predefinedTypes(), customSupport.customConditions(),
                        customSupport.customFieldTypes(), !noComputations, relevant);
            }
        } catch (UnsupportedModelTimeZoneException e) {
            return refuseExplainedPrecheck(RkCode.INTERPRETER_TIMEZONE_UNSUPPORTED, e.getMessage(),
                    model, RkCode.INTERPRETER_TIMEZONE_UNSUPPORTED.fix());
        } catch (InvalidModelTimeZoneException e) {
            return refuseExplainedPrecheck(RkCode.INVALID_CONFIG_VALUE, e.getMessage(),
                    model, RkCode.INVALID_CONFIG_VALUE.fix());
        } catch (DocumentInputError badDoc) {
            return failInvalidInstance(badDoc);
        } catch (IllegalArgumentException badDoc) {
            return fail(badDoc.getMessage());
        } catch (RuntimeException e) {
            return fail("evaluation failed: " + e.getMessage());
        }

        RuntimeEvidence evidence = RuntimeEvidence.interpreter();
        ResultEnvelope refusal = customSupport.strictRefusal(resultIdentity(), data, evidence);
        if (refusal != null) {
            out().print(refusal.toJsonString());
            return refusal.exitCode();
        }
        ResultEnvelope env = ResultEnvelope.runtimeObservation(resultIdentity(),
                data.get("fired").size() + " rule(s) fired", data, evidence);
        out().print(env.toJsonString());
        return env.exitCode();
    }
}
