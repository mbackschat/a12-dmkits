package io.github.mbackschat.a12.dm.cli.rule;

import io.github.mbackschat.a12.dm.cli.BaseCommand;
import io.github.mbackschat.a12.dm.cli.ConsistencyEvidence;
import io.github.mbackschat.a12.dm.cli.FormatVerbs;
import io.github.mbackschat.a12.dm.cli.ParamKind;
import io.github.mbackschat.a12.dm.cli.Refusal;
import io.github.mbackschat.a12.dm.cli.ResultEnvelope;
import io.github.mbackschat.a12.dm.cli.SafetyChecks;
import io.github.mbackschat.a12.dm.cli.SafetyGate;
import io.github.mbackschat.a12.dm.cli.WorkspaceOption;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import io.github.mbackschat.a12.dm.adapter.KernelAdapter;
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel;
import io.github.mbackschat.a12.dm.rulekit.dsl.ConditionLanguage;
import io.github.mbackschat.a12.dm.rulekit.validate.RkCode;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

/**
 * {@code dmtool rule format <rulePath>} — the kernel's canonical text for a rule's condition (a parse +
 * format round-trip through the engine's <em>own</em> formatter), optionally re-emitted in the other keyword
 * language with {@code --lang} (kernel-authoritative EN↔DE). A read op: the text rides {@code data.canonical}.
 * The canonical form is the engine's, not ours — its use is diff-stable normalization (re-express → format →
 * compare). Lossy for a {@code $} correlation (KERNEL-FINDINGS), so it is a display read, never a write-back.
 */
@Command(name = "format", mixinStandardHelpOptions = true,
        description = "Emit the kernel's canonical text for a rule's condition (optionally translated EN↔DE).")
final class RuleFormatCommand extends BaseCommand {

    @ParamKind(ParamKind.Kind.RULE_PATH)
    @Parameters(index = "0", paramLabel = "<rulePathInModel>",
            description = "the rule's full name — its slash-path in the model, e.g. /Order/SomeRule")
    String rule;

    @Option(names = "--lang", paramLabel = "<EN|DE>",
            description = "re-emit in this keyword language (kernel-authoritative EN↔DE); omitted = the model's own "
                    + "language. This is an EPHEMERAL render/keyword toggle for this call — NOT the model's stored "
                    + "condition-language setting, which the config modify verb owns.")
    ConditionLanguage lang;

    @Mixin
    WorkspaceOption workspace = new WorkspaceOption();

    @Override
    public Integer call() {
        String model = model();
        if (model == null) {
            return fail("pass -m <model.json> (before or after the verb)");
        }
        String json;
        try {
            json = Files.readString(Path.of(model));
        } catch (IOException e) {
            return fail("cannot read model: " + e.getMessage());
        }
        try {
            BoundModel bound = BoundModel.ofJson(json, workspace.resolverFor(model));
            KernelAdapter adapter = KernelAdapter.of(bound);
            // a computation named where a rule is expected gets a structured refusal that redirects to the
            // computation-aware verb, instead of the bare "not a rule".
            if (adapter.computationFullNames().contains(rule)) {
                return refuseNotFound("rule", "format", RkCode.NO_SUCH_RULE,
                        "'" + rule + "' is a computation, not a validation rule — refusing", rule,
                        "use `computation format " + rule + "` — it is a computation (a calculated field), not a rule");
            }
            // an unknown rule is a coded, structured refusal (AP1), consistent with the rest of the read family.
            Optional<Refusal> refusal = new SafetyGate()
                    .require(SafetyChecks.ruleExists(adapter, rule))
                    .firstFailure();
            if (refusal.isPresent()) {
                return refuse("rule", "format", refusal.get());
            }
            ResultEnvelope env = FormatVerbs.envelope("rule", adapter, rule, lang,
                    ConsistencyEvidence.of(bound));
            out().print(env.toJsonString());
            return env.exitCode();
        } catch (RuntimeException e) {
            return failOrRethrowDomainRejection("format failed: ", e);
        }
    }
}
