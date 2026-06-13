package io.github.mbackschat.a12.dm.cli;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import io.github.mbackschat.a12.dm.adapter.KernelAdapter;
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel;
import io.github.mbackschat.a12.dm.rulekit.dsl.ConditionLanguage;
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

    @Parameters(index = "0", paramLabel = "<rulePathInModel>",
            description = "the rule's full name — its slash-path in the model, e.g. /Order/SomeRule")
    String rule;

    @Option(names = "--lang", paramLabel = "<EN|DE>",
            description = "re-emit in this keyword language (kernel-authoritative EN↔DE); omitted = the model's own language")
    ConditionLanguage lang;

    @Mixin
    IncludeDirOption includes = new IncludeDirOption();

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
            BoundModel bound = BoundModel.ofJson(json, includes.resolverFor(model));
            KernelAdapter adapter = KernelAdapter.of(bound);
            String problem = ModelPaths.ruleProblem(adapter.ruleFullNames(), adapter.computationFullNames(), rule);
            if (problem != null) {
                return fail(problem);
            }
            ResultEnvelope env = FormatVerbs.envelope("rule", adapter, rule, lang);
            out().print(env.toJsonString());
            return env.exitCode();
        } catch (RuntimeException e) {
            return fail("format failed: " + e.getMessage());
        }
    }
}
