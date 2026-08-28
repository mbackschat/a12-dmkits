package io.github.mbackschat.a12.dm.cli.rule;

import io.github.mbackschat.a12.dm.cli.BaseCommand;
import io.github.mbackschat.a12.dm.cli.ConsistencyEvidence;
import io.github.mbackschat.a12.dm.cli.ModelPaths;
import io.github.mbackschat.a12.dm.cli.ReadPayloads;
import io.github.mbackschat.a12.dm.cli.ResultEnvelope;
import io.github.mbackschat.a12.dm.cli.WorkspaceOption;


import com.fasterxml.jackson.databind.node.ObjectNode;

import io.github.mbackschat.a12.dm.adapter.KernelAdapter;
import io.github.mbackschat.a12.dm.modelkit.ModelDocument;
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
        if (rule == null) {   // locator contract: --rule is the named alias for the positional
            rule = ruleFlag;
        }
        String json = readModelJson(model);
        if (json == null) {
            return 2;
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
            // the SAME builder the apply-embedded RULE_READ op uses, so the two shapes cannot drift. The
            // metadata projection (G45) needs the ModelDocument: it sits on the element node, not in the
            // adapter's condition/message view.
            ObjectNode data = ReadPayloads.rule(adapter, ModelDocument.read(json), rule);
            ResultEnvelope env = ResultEnvelope.queryResult(resultIdentity(),
                    data.path("converted").asBoolean() ? "read " + rule : "read " + rule + " (Opaque)",
                    data, ConsistencyEvidence.of(adapter));
            out().print(env.toJsonString());
            return env.exitCode();
        } catch (RuntimeException e) {
            return failOrRethrowDomainRejection("read failed: ", e);
        }
    }
}
