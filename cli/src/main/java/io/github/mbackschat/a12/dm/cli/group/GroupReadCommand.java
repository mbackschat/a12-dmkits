package io.github.mbackschat.a12.dm.cli.group;

import io.github.mbackschat.a12.dm.cli.BaseCommand;
import io.github.mbackschat.a12.dm.cli.ResultEnvelope;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import io.github.mbackschat.a12.dm.modelkit.ModelDocument;
import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;

/**
 * {@code dmtool group read <groupPath>} — read a group's repeatability. A read op: the payload rides {@code
 * data} ({@code group}, {@code repeatable}, {@code maxRepetitions} when repeatable, {@code indexField} when
 * set); a path that names no group fails fast.
 */
@Command(name = "read", mixinStandardHelpOptions = true,
        description = "Read a group's repeatability into `data`.")
final class GroupReadCommand extends BaseCommand {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Parameters(index = "0", arity = "0..1", paramLabel = "<groupPathInModel>",
            description = "the group's full name-path, e.g. /Order/BillingAddress; omit to list every group's path")
    String group;

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
            if (group == null) {
                return readNameIndex("group", "groups", doc.groupPaths());
            }
            if (!doc.groupPaths().contains(group)) {
                return fail("no group '" + group + "' in the model");
            }
            ObjectNode data = MAPPER.createObjectNode();
            data.put("group", group);
            boolean repeatable = doc.isRepeating(group);
            data.put("repeatable", repeatable);
            if (repeatable) {
                data.put("maxRepetitions", doc.maxRepetitions(group));  // the numeric max rows, not just the boolean
            }
            // echo the index field (repetition row-key) so a `group modify --index-field` edit round-trips
            doc.indexField(group).ifPresent(idx -> data.put("indexField", idx));
            ResultEnvelope env = ResultEnvelope.read("group", "read", "read " + group, true, List.of(), data);
            out().print(env.toJsonString());
            return env.exitCode();
        } catch (RuntimeException e) {
            return fail("read failed: " + e.getMessage());
        }
    }
}
