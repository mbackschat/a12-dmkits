package io.github.mbackschat.a12.dm.cli.rule;

import io.github.mbackschat.a12.dm.cli.BaseCommand;
import io.github.mbackschat.a12.dm.cli.CustomSupportOptions;
import io.github.mbackschat.a12.dm.cli.DocInstance;
import io.github.mbackschat.a12.dm.cli.ParamKind;
import io.github.mbackschat.a12.dm.cli.Refusal;
import io.github.mbackschat.a12.dm.cli.ResultEnvelope;
import io.github.mbackschat.a12.dm.cli.SafetyChecks;
import io.github.mbackschat.a12.dm.cli.SafetyGate;
import io.github.mbackschat.a12.dm.cli.WorkspaceOption;
import io.github.mbackschat.a12.dm.cli.interp.InterpreterEngine;

import java.io.IOException;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import io.github.mbackschat.a12.dm.rulekit.validate.RkCode;

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
 * {@code dmtool rule eval <rulePath> --instance <document.json>} — the rule-scope RUNTIME verb: the scoped,
 * suppression-aware polarity check for ONE rule against a document INSTANCE (FEATURE-GAPS). The rule-first
 * peer of {@code model eval}: it answers <b>fired</b> / <b>passed</b> / <b>suppressed</b> — the last being a
 * rule the kernel never evaluated because a field it references is formally invalid (reporting that as a pass
 * would mislead, so a plain "did it fire" verb isn't enough). Read-only; the verdict rides {@code data}.
 *
 * <p>Runtime, single-rule scope — the runtime peer of the static candidate-check {@code rule check}; like
 * {@code model eval} it runs the form-engine flow (compute → apply → validate) by default ({@code
 * --no-computations} opts to raw). See the check/eval/compute taxonomy in CLI-SPEC.
 */
@Command(name = "eval", mixinStandardHelpOptions = true,
        description = "Evaluate ONE rule against a document INSTANCE: fired / passed / suppressed (runtime "
                + "polarity; applies computations first by default, like model eval).")
final class RuleEvalCommand extends BaseCommand {

    private static final ObjectMapper MAPPER = new ObjectMapper();

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

    @Option(names = "--kernel",
            description = "use the A12 kernel engine instead of the default interpreter (JVM only; "
                    + "not available in the native image, where the interpreter is the only engine)")
    boolean useKernel;

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
        // --kernel is removed from the native command tree (Cli.removeNativeUnavailableOptions), so under the
        // native profile useKernel can never be set — no runtime refusal needed; the JVM keeps both engines.
        String modelPath = model();
        String json = readModelJson(modelPath);   // null (after printing the failure) if no -m / unreadable
        if (json == null) {
            return 2;
        }
        String kernelConflict = customSupport.kernelConflict(useKernel);
        if (kernelConflict != null) {
            return fail(kernelConflict);
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
            ObjectNode data;
            if (useKernel) {
                Locale locale = Locale.forLanguageTag(localeTag.replace('_', '-'));
                BoundModel bound = BoundModel.ofJson(json, workspace.resolverFor(modelPath));
                KernelAdapter adapter = KernelAdapter.of(bound);
                // an unknown rule is a coded, structured refusal (AP1), consistent with the read family.
                Optional<Refusal> refusal = new SafetyGate()
                        .require(SafetyChecks.ruleExists(adapter, rule))
                        .firstFailure();
                if (refusal.isPresent()) {
                    return refuse("rule", "eval", refusal.get());
                }
                data = data(adapter.testRule(rule, fields, locale, !noComputations));
            } else {
                // same structured refusal in the DEFAULT (kernel-free) path — the interpreter otherwise throws
                // "no such rule", which would surface as a bare error.
                if (!InterpreterEngine.ruleFullNames(json).contains(rule)) {
                    return refuseNotFound("rule", "eval", RkCode.NO_SUCH_RULE,
                            "rule '" + rule + "' does not exist — refusing", rule, RkCode.NO_SUCH_RULE.fix());
                }
                data = InterpreterEngine.ruleTestData(json, fields, localeTag, rule,
                        customSupport.predefinedTypes(), customSupport.customConditions(),
                        customSupport.customFieldTypes(), !noComputations);
            }
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

    /** The human summary from the verdict + rule in {@code data} — engine-independent (same wording either way). */
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
