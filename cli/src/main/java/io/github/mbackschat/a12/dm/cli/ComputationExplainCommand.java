package io.github.mbackschat.a12.dm.cli;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import io.github.mbackschat.a12.dm.adapter.KernelAdapter;
import io.github.mbackschat.a12.dm.adapter.ReadComputation;
import io.github.mbackschat.a12.dm.rulekit.context.ModelContext;
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Parameters;

/**
 * {@code dmtool computation explain <computationPath>} — project a computation into a structured {@code
 * ComputationExplanation} (computed field, common precondition + per-alternative precondition/operation,
 * messages, gloss). A read op: the explanation rides {@code data}; an Opaque computation comes back as
 * {@code explained:false} + reason.
 */
@Command(name = "explain", mixinStandardHelpOptions = true,
        description = "Project a computation as a structured explanation.")
final class ComputationExplainCommand extends BaseCommand {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Parameters(index = "0", paramLabel = "<computationPathInModel>",
            description = "the computation's full name — its slash-path in the model, e.g. /Order/TotalComp")
    String computation;

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
            if (!adapter.computationFullNames().contains(computation)) {
                return fail("'" + computation + "' is not a computation in this model — computations: "
                        + adapter.computationFullNames());
            }
            ReadComputation read = adapter.readComputation(computation);
            JsonNode data;
            String summary;
            if (read instanceof ReadComputation.Converted converted) {
                data = MAPPER.readTree(new ModelContext(bound).explainComputation(converted.computation()).toJson());
                summary = "explained " + computation;
            } else {
                ObjectNode d = MAPPER.createObjectNode();
                d.put("computation", computation);
                d.put("explained", false);
                d.put("reason", ((ReadComputation.Opaque) read).reason());
                data = d;
                summary = computation + " is Opaque (not modeled)";
            }
            ResultEnvelope env = ResultEnvelope.read("computation", "explain", summary, true, List.of(), data);
            out().print(env.toJsonString());
            return env.exitCode();
        } catch (IOException e) {
            return fail("explain failed: " + e.getMessage());
        } catch (RuntimeException e) {
            return fail("explain failed: " + e.getMessage());
        }
    }
}
