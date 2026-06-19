package io.github.mbackschat.a12.dm.cli.field;

import io.github.mbackschat.a12.dm.cli.BaseCommand;
import io.github.mbackschat.a12.dm.cli.ResultEnvelope;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import io.github.mbackschat.a12.dm.modelkit.ModelDocument;
import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;

/**
 * {@code dmtool field read <fieldPath>} — read a field's declared type. A read op: the type rides {@code
 * data} ({@code field}, {@code type}); a path that names no field fails fast.
 */
@Command(name = "read", mixinStandardHelpOptions = true,
        description = "Read a field's declared type into `data`.")
final class FieldReadCommand extends BaseCommand {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Parameters(index = "0", arity = "0..1", paramLabel = "<fieldPathInModel>",
            description = "the field's full name-path, e.g. /Order/Quantity; omit to list every field's path")
    String field;

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
            ModelDocument doc = ModelDocument.read(json);
            if (field == null) {
                return readNameIndex("field", "fields", doc.fieldPaths());
            }
            Optional<String> type = doc.fieldType(field);
            if (type.isEmpty()) {
                return fail("no field '" + field + "' in the model");
            }
            ObjectNode data = MAPPER.createObjectNode();
            data.put("field", field);
            data.put("type", type.get());
            ResultEnvelope env = ResultEnvelope.read("field", "read", "read " + field, true, List.of(), data);
            out().print(env.toJsonString());
            return env.exitCode();
        } catch (RuntimeException e) {
            return fail("read failed: " + e.getMessage());
        }
    }
}
