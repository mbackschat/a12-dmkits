package io.github.mbackschat.a12.dm.cli.rule;

import io.github.mbackschat.a12.dm.cli.BaseCommand;
import io.github.mbackschat.a12.dm.cli.ConsistencyEvidence;
import io.github.mbackschat.a12.dm.cli.ParamKind;
import io.github.mbackschat.a12.dm.cli.Refusal;
import io.github.mbackschat.a12.dm.cli.ResultEnvelope;
import io.github.mbackschat.a12.dm.cli.SafetyChecks;
import io.github.mbackschat.a12.dm.cli.SafetyGate;
import io.github.mbackschat.a12.dm.cli.WorkspaceOption;

import java.util.List;
import java.util.Optional;

import com.fasterxml.jackson.databind.node.ObjectNode;

import io.github.mbackschat.a12.dm.adapter.KernelAdapter;
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel;
import io.github.mbackschat.a12.dm.rulekit.validate.RkCode;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Parameters;

/**
 * {@code dmtool rule explain <rulePath>} — project a rule's condition into a structured {@code
 * RuleExplanation} (operator glossary + a backlinked structural tree + referenced fields + iteration scope
 * + an NL gloss). A read op: the explanation rides {@code data}; an Opaque construct the converter doesn't
 * model comes back as {@code explained:false} + reason (tolerant, never an error). The whole-computation
 * explanation is {@code computation explain}.
 */
@Command(name = "explain", mixinStandardHelpOptions = true,
        description = "Project a rule's condition as a structured explanation (operators, tree, refs, gloss).")
final class RuleExplainCommand extends BaseCommand {

    @ParamKind(ParamKind.Kind.RULE_PATH)
    @Parameters(index = "0", paramLabel = "<rulePathInModel>",
            description = "the rule's full name — its slash-path in the model, e.g. /Order/SomeRule")
    String rule;

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
            BoundModel bound = BoundModel.ofJson(json, workspace.resolverFor(model));
            KernelAdapter adapter = KernelAdapter.of(bound);
            // a computation named where a rule is expected gets a structured refusal that redirects to the
            // computation-aware verb (a calculated field is not a validation rule), instead of the bare "not a rule".
            if (adapter.computationFullNames().contains(rule)) {
                return refuseNotFound(RkCode.NO_SUCH_RULE,
                        "'" + rule + "' is a computation, not a validation rule — refusing", rule,
                        "use `computation explain " + rule + "` — it is a computation (a calculated field), not a rule");
            }
            // an unknown rule is a coded, structured refusal (AP1 — never a bare stderr error), exactly as
            // `rule deps`/`rule remove` gate it; the read family is held to this by ReadMissingTargetEnvelopeTest.
            Optional<Refusal> refusal = new SafetyGate()
                    .require(SafetyChecks.ruleExists(adapter, rule))
                    .firstFailure();
            if (refusal.isPresent()) {
                return refuse(refusal.get());
            }
            ObjectNode data = RuleExplainData.build(adapter, bound, rule);
            String summary = RuleExplainData.isOpaque(data) ? rule + " is Opaque (not modeled)" : "explained " + rule;
            ResultEnvelope env = ResultEnvelope.queryResult(resultIdentity(), summary, data,
                    ConsistencyEvidence.of(bound));
            out().print(env.toJsonString());
            return env.exitCode();
        } catch (RuntimeException e) {
            throw withFailureContext("explain failed: ", e);
        }
    }
}
