package io.github.mbackschat.a12.dm.cli.model;

import io.github.mbackschat.a12.dm.cli.BaseCommand;
import io.github.mbackschat.a12.dm.cli.ConsistencyEvidence;
import io.github.mbackschat.a12.dm.cli.ResultEnvelope;
import io.github.mbackschat.a12.dm.cli.WorkspaceOption;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
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
        if (model == null) {
            return fail("pass -m <model.json> (before or after the verb)");
        }
        try {
            BoundModel m = BoundModel.ofJson(Files.readString(Path.of(model)), workspace.resolverFor(model));
            JsonNode card = MAPPER.readTree(new ModelContext(m).describe().toJson());
            ResultEnvelope env = ResultEnvelope.queryResult(resultIdentity(), "model card", card,
                    ConsistencyEvidence.of(m));
            out().print(env.toJsonString());
            return env.exitCode();
        } catch (IOException e) {
            return fail("cannot read model: " + e.getMessage());
        } catch (RuntimeException e) {
            return failOrRethrowDomainRejection("describe failed: ", e);
        }
    }
}
