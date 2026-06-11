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
 * {@code dmtool include read} — list the model's declared includes. A read op: the payload rides {@code data}
 * ({@code includes}, an array of {@code {alias, reference}}). Includes are a flat header-level list, so the
 * read takes no path argument; resolving the referenced models is not needed to list them.
 */
@Command(name = "read", mixinStandardHelpOptions = true,
        description = "List the declared model includes into `data`.")
final class IncludeReadCommand extends BaseCommand {

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
            List<ModelDocument.Include> incs = ModelDocument.read(json).includes();
            ObjectNode data = MAPPER.createObjectNode();
            ArrayNode arr = data.putArray("includes");
            for (ModelDocument.Include inc : incs) {
                arr.addObject().put("alias", inc.alias()).put("reference", inc.reference());
            }
            ResultEnvelope env = ResultEnvelope.read("include", "read",
                    "read " + incs.size() + " include(s)", true, List.of(), data);
            out().print(env.toJsonString());
            return env.exitCode();
        } catch (RuntimeException e) {
            return fail("read failed: " + e.getMessage());
        }
    }
}
