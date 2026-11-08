package io.github.mbackschat.a12.dm.cli.model;

import io.github.mbackschat.a12.dm.cli.BaseCommand;
import io.github.mbackschat.a12.dm.cli.ConsistencyEvidence;
import io.github.mbackschat.a12.dm.cli.ReadPayloads;
import io.github.mbackschat.a12.dm.cli.ResultEnvelope;
import io.github.mbackschat.a12.dm.cli.WorkspaceOption;

import java.io.IOException;
import java.util.Optional;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import io.github.mbackschat.a12.dm.modelkit.ModelDocument;
import io.github.mbackschat.a12.dm.modelkit.Requiredness;
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
        description = "Emit the model card (fields + kinds + requiredness + typedefs + enum domains + group "
                + "repeatability) in `data`; `model report` lists the rules and computations.")
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
            withAuthoredFacts(card, ModelDocument.read(modelJson));
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

    /**
     * Adds each field's {@code required} flag and {@code typedef} link, both only when declared, in
     * {@code field read}'s spelling. The card cannot carry them itself: it reads the bound model, where a type
     * definition is already resolved into its field, so the link survives only in the authored document. A
     * field an include contributes has no authored state here and keeps the card's facts alone.
     */
    private static void withAuthoredFacts(JsonNode card, ModelDocument doc) {
        for (JsonNode node : card.path("fields")) {
            ObjectNode field = (ObjectNode) node;
            String path = field.path("path").asText();
            doc.requiredness(path).filter(r -> r != Requiredness.OPTIONAL)
                    .ifPresent(r -> field.set("required", ReadPayloads.requiredValue(r)));
            doc.fieldConfigJson(path).flatMap(ModelDescribeCommand::typedefOf)
                    .ifPresent(id -> field.put("typedef", id));
        }
    }

    private static Optional<String> typedefOf(String configJson) {
        try {
            JsonNode config = MAPPER.readTree(configJson);
            return config.hasNonNull("typedef") ? Optional.of(config.get("typedef").asText()) : Optional.empty();
        } catch (IOException e) {
            throw new IllegalStateException("unparseable field config read-back: " + configJson, e);
        }
    }
}
