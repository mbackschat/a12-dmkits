package io.github.mbackschat.a12.dm.cli.model;

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
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

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
            description = "the document INSTANCE (field data) — a nested JSON tree under \"fields\". See `schema model eval`.")
    String instance;

    @ParamKind(ParamKind.Kind.RULE_PATH)
    @Option(names = "--rule", paramLabel = "<rulePathInModel>",
            description = "narrow the report to an EXISTING rule (its full slash-path)")
    String ruleId;

    @Option(names = "--condition", paramLabel = "<DSL>",
            description = "evaluate a CANDIDATE rule (with --field): a condition true on a VIOLATION; injected, not persisted")
    String condition;

    @Option(names = "--field", paramLabel = "<fieldPathInModel>",
            description = "the candidate rule's error field as a path INSIDE the model (with --condition)")
    String field;

    @Option(names = "--group", paramLabel = "<groupPathInModel>",
            description = "the candidate rule's iteration scope (group path); defaults to the field's parent")
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
        if (model == null) {
            return fail("pass -m <model.json> (before or after the verb)");
        }
        boolean candidate = condition != null || field != null;
        if (candidate && ruleId != null) {
            return fail("pass either --rule <name> OR a candidate (--condition + --field), not both");
        }
        if (candidate && (condition == null || field == null)) {
            return fail("a candidate rule needs both --condition and --field");
        }
        String modelJson;
        Map<String, Object> fields;
        try {
            modelJson = Files.readString(Path.of(model));
        } catch (IOException e) {
            return fail("cannot read model: " + e.getMessage());
        }
        try {
            fields = DocInstance.readFields(instance);
        } catch (IllegalArgumentException badShape) {
            return fail(badShape.getMessage());
        } catch (IOException e) {
            return fail("cannot read document: " + e.getMessage());
        }
        ModelResolver resolver = workspace.resolverFor(model);

        // Resolve a candidate's group + error relpath.
        String effectiveGroup = group;
        String errorEntityRelPath = null;
        if (candidate) {
            if (effectiveGroup == null) {
                int slash = field.lastIndexOf('/');
                if (slash <= 0) {
                    return fail("--field must be an absolute path (e.g. /Group/Field)");
                }
                effectiveGroup = field.substring(0, slash);
            }
            if (!field.startsWith(effectiveGroup + "/")) {
                return fail("--field " + field + " must be inside --group " + effectiveGroup);
            }
            errorEntityRelPath = "../" + field.substring(effectiveGroup.length() + 1);
        }

        // Pre-expand the model kernel-free so an includes-bearing model is evaluated with its mounted content
        // present (decoupling Stage 2 / A4 — was silently dropped).
        String interpJson;
        try {
            interpJson = InterpreterEngine.expandForEval(modelJson, resolver);
        } catch (IncludeRelativizationException e) {
            return refusePrecheck("model", "eval", RkCode.INTERPRETER_INCLUDE_RULES_UNSUPPORTED,
                    e.getMessage(), model, RkCode.INTERPRETER_INCLUDE_RULES_UNSUPPORTED.fix());
        } catch (UnresolvedReferenceException e) {
            return refusePrecheck("model", "eval", RkCode.UNRESOLVED_REFERENCE, e.getMessage(), model,
                    RkCode.UNRESOLVED_REFERENCE.fix());
        } catch (RuntimeException e) {
            return fail("cannot expand model: " + e.getMessage());
        }

        ObjectNode data;
        try {
            if (candidate) {
                data = InterpreterEngine.evalCandidateData(interpJson, fields, localeTag,
                        effectiveGroup, name, errorEntityRelPath, condition, code, severity,
                        customSupport.predefinedTypes(), customSupport.customConditions(),
                        customSupport.customFieldTypes(), !noComputations);
            } else {
                // a structured refusal for an unknown --rule narrowing — otherwise it would silently report
                // the missing rule as fired:false.
                if (ruleId != null && !InterpreterEngine.ruleFullNames(interpJson).contains(ruleId)) {
                    return refuseNotFound("model", "eval", RkCode.NO_SUCH_RULE,
                            "rule '" + ruleId + "' does not exist — refusing", ruleId, RkCode.NO_SUCH_RULE.fix());
                }
                data = InterpreterEngine.evalData(interpJson, fields, localeTag, ruleId,
                        customSupport.predefinedTypes(), customSupport.customConditions(),
                        customSupport.customFieldTypes(), !noComputations);
            }
        } catch (IllegalArgumentException badDoc) {
            return fail(badDoc.getMessage());
        } catch (RuntimeException e) {
            return fail("evaluation failed: " + e.getMessage());
        }

        ResultEnvelope refusal = customSupport.strictRefusal("model", "eval", data);
        if (refusal != null) {
            out().print(refusal.toJsonString());
            return refusal.exitCode();
        }
        ResultEnvelope env = ResultEnvelope.read("model", "eval",
                data.get("fired").size() + " rule(s) fired", true, List.of(), data);
        out().print(env.toJsonString());
        return env.exitCode();
    }
}
