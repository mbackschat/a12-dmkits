package io.github.mbackschat.a12.dm.cli;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import io.github.mbackschat.a12.dm.modelkit.ModelDocument;
import picocli.CommandLine.Command;

/**
 * {@code dmtool typedef read} — list the model's reusable type-definition ids. A read op: the payload rides
 * {@code data} ({@code typedefs}, an array of ids). Typedefs are a flat model-level list, so the read takes
 * no path argument (unlike {@code field read}/{@code group read}).
 */
@Command(name = "read", mixinStandardHelpOptions = true,
        description = "List the declared type-definition ids into `data`.")
final class TypedefReadCommand extends BaseCommand {

    private static final ObjectMapper MAPPER = new ObjectMapper();

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
            List<String> ids = ModelDocument.read(json).typeDefinitionIds();
            ObjectNode data = MAPPER.createObjectNode();
            ArrayNode arr = data.putArray("typedefs");
            ids.forEach(arr::add);
            ResultEnvelope env = ResultEnvelope.read("typedef", "read",
                    "read " + ids.size() + " type definition(s)", true, List.of(), data);
            out().print(env.toJsonString());
            return env.exitCode();
        } catch (RuntimeException e) {
            return fail("read failed: " + e.getMessage());
        }
    }
}
