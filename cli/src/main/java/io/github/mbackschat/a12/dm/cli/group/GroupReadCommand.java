package io.github.mbackschat.a12.dm.cli.group;

import io.github.mbackschat.a12.dm.cli.BaseCommand;
import io.github.mbackschat.a12.dm.cli.ResultEnvelope;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import io.github.mbackschat.a12.dm.modelkit.ModelDocument;
import io.github.mbackschat.a12.dm.rulekit.validate.RkCode;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

/**
 * {@code dmtool group read <groupPath>} — read a group's repeatability + its direct child fields. A read op:
 * the payload rides {@code data} ({@code group}, {@code repeatable}, {@code maxRepetitions} when repeatable,
 * {@code indexField} when set, and {@code fields} = the group's direct child field paths); a path that names
 * no group fails fast.
 */
@Command(name = "read", mixinStandardHelpOptions = true,
        description = "Read a group's repeatability + its direct child fields into `data`.")
final class GroupReadCommand extends BaseCommand {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Parameters(index = "0", arity = "0..1", paramLabel = "<groupPathInModel>",
            description = "the group's full name-path, e.g. /Order/BillingAddress; omit to list every group's "
                    + "path (or address it with --group)")
    String group;

    @Option(names = "--group", paramLabel = "<groupPathInModel>",
            description = "the group's full name-path — the named-flag alias for the positional (the locator "
                    + "contract: the target-noun flag always works). The positional wins if both are given.")
    String groupFlag;

    @Override
    public Integer call() {
        String model = model();
        if (model == null) {
            return fail("pass -m <model.json> (before or after the verb)");
        }
        if (group == null) {   // locator contract: --group is the named alias for the positional
            group = groupFlag;
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
                return refuseNotFound("group", "read", RkCode.NO_SUCH_GROUP, "no group at '" + group + "'",
                        group, "pass an existing group's full name-path (see `model describe` or `group read` — no arg)");
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
            // echo the declared row sort fields (display/USB ordering) so a `group modify --sort-field` round-trips
            List<io.github.mbackschat.a12.dm.modelkit.ElementDraft.SortField> sorts = doc.sortFields(group);
            if (!sorts.isEmpty()) {
                ArrayNode sortFields = data.putArray("sortFields");
                sorts.forEach(sf -> {
                    ObjectNode n = sortFields.addObject();
                    n.put("name", sf.fieldName());
                    n.put("order", sf.order().name());
                });
            }
            // echo the group's DIRECT child fields, so a just-built composite group (attachment / multiselect /
            // bulk add) is confirmable through `group read` alone — without falling back to `model describe`
            // for "what's in here" (probe 10). Direct children only (a deeper field belongs to a sub-group).
            String prefix = group + "/";
            ArrayNode fields = data.putArray("fields");
            doc.fieldPaths().stream()
                    .filter(p -> p.startsWith(prefix) && p.indexOf('/', prefix.length()) < 0)
                    .forEach(fields::add);
            ResultEnvelope env = ResultEnvelope.read("group", "read", "read " + group, true, List.of(), data);
            out().print(env.toJsonString());
            return env.exitCode();
        } catch (RuntimeException e) {
            return fail("read failed: " + e.getMessage());
        }
    }
}
