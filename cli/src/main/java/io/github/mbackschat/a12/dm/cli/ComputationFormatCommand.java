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
 * {@code dmtool computation format <computationPath>} — the kernel's canonical text for a computation's
 * condition (a parse + format round-trip through the engine's own formatter), optionally re-emitted in the
 * other keyword language with {@code --lang} (kernel-authoritative EN↔DE). A read op: the text rides
 * {@code data.canonical}. The {@code rule format} peer for the model's computations.
 */
@Command(name = "format", mixinStandardHelpOptions = true,
        description = "Emit the kernel's canonical text for a computation (optionally translated EN↔DE).")
final class ComputationFormatCommand extends BaseCommand {

    @Parameters(index = "0", paramLabel = "<computationPathInModel>",
            description = "the computation's full name — its slash-path in the model, e.g. /Order/TotalComp")
    String computation;

    @Option(names = "--lang", paramLabel = "<EN|DE>",
            description = "re-emit in this keyword language (kernel-authoritative EN↔DE); omitted = the model's own language")
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
            if (!adapter.computationFullNames().contains(computation)) {
                return fail("'" + computation + "' is not a computation in this model — computations: "
                        + adapter.computationFullNames());
            }
            ResultEnvelope env = FormatVerbs.envelope("computation", adapter, computation, lang);
            out().print(env.toJsonString());
            return env.exitCode();
        } catch (RuntimeException e) {
            return fail("format failed: " + e.getMessage());
        }
    }
}
