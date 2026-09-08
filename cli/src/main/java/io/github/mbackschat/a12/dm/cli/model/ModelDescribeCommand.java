package io.github.mbackschat.a12.dm.cli.model;

import io.github.mbackschat.a12.dm.cli.BaseCommand;
import io.github.mbackschat.a12.dm.cli.ConsistencyEvidence;
import io.github.mbackschat.a12.dm.cli.ResultEnvelope;
import io.github.mbackschat.a12.dm.cli.WorkspaceOption;

import java.io.IOException;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.github.mbackschat.a12.dm.rulekit.context.ModelContext;
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;

/**
 * {@code dmtool model describe} — emit the F9a model card (fields + kinds + enum domains + group
 * repeatability) so an agent can orient without reading raw DM-JSON. A read op: the card rides the
 * envelope's {@code data} (CLI-SPEC §3b).
 */
@Command(name = "describe", mixinStandardHelpOptions = true,
        description = "Emit the model card (fields + kinds + enum domains + group repeatability) in `data`.")
final class ModelDescribeCommand extends BaseCommand {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Mixin
    WorkspaceOption workspace = new WorkspaceOption();

    @Override
    public Integer call() {
        String model = model();
        String modelJson = readModelJson(model);
        if (modelJson == null) {
            return 2;
        }
        try {
            BoundModel m = BoundModel.ofJson(modelJson, workspace.resolverFor(model));
            JsonNode card = MAPPER.readTree(new ModelContext(m).describe().toJson());
            ResultEnvelope env = ResultEnvelope.queryResult(resultIdentity(), "model card", card,
                    ConsistencyEvidence.of(m));
            out().print(env.toJsonString());
            return env.exitCode();
        } catch (IOException e) {
            // No longer the model read — that is readModelJson's. What is left is the card's own JSON
            // round-trip, which only fails if our own renderer emitted something unparseable: a tool defect,
            // so it goes to Cli's error boundary rather than reading as something the caller can fix.
            throw new IllegalStateException("cannot serialize the model card: " + e.getMessage(), e);
        } catch (RuntimeException e) {
            throw withFailureContext("describe failed: ", e);
        }
    }
}
