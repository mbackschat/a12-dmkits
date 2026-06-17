package io.github.mbackschat.a12.dm.cli.computation;

import io.github.mbackschat.a12.dm.cli.BaseCommand;
import io.github.mbackschat.a12.dm.cli.ResultEnvelope;
import io.github.mbackschat.a12.dm.cli.WorkspaceOption;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import io.github.mbackschat.a12.dm.adapter.KernelAdapter;
import io.github.mbackschat.a12.dm.adapter.ReadComputation;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Parameters;

/**
 * {@code dmtool computation read <computationPath>} — read a computation back into rulekit's typed form.
 * A read op: the read-back rides {@code data} ({@code converted}+{@code calculatedField}, or {@code
 * converted:false}+{@code reason} for an Opaque construct — tolerant, never an error).
 */
@Command(name = "read", mixinStandardHelpOptions = true,
        description = "Read a computation back into rulekit's typed form (tolerant of Opaque constructs).")
final class ComputationReadCommand extends BaseCommand {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Parameters(index = "0", paramLabel = "<computationPathInModel>",
            description = "the computation's full name — its slash-path in the model, e.g. /Order/TotalComp")
    String computation;

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
            KernelAdapter adapter = KernelAdapter.ofJson(json, workspace.resolverFor(model));
            if (!adapter.computationFullNames().contains(computation)) {
                return fail("'" + computation + "' is not a computation in this model — computations: "
                        + adapter.computationFullNames());
            }
            ReadComputation read = adapter.readComputation(computation);
            ObjectNode data = MAPPER.createObjectNode();
            data.put("computation", computation);
            if (read instanceof ReadComputation.Converted converted) {
                data.put("converted", true);
                data.put("calculatedField", converted.computation().computedField().path());
            } else {
                data.put("converted", false);
                data.put("reason", ((ReadComputation.Opaque) read).reason());
            }
            ResultEnvelope env = ResultEnvelope.read("computation", "read", "read " + computation,
                    true, List.of(), data);
            out().print(env.toJsonString());
            return env.exitCode();
        } catch (RuntimeException e) {
            return fail("read failed: " + e.getMessage());
        }
    }
}
