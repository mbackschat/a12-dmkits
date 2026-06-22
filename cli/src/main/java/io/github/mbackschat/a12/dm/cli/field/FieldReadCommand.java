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
import io.github.mbackschat.a12.dm.rulekit.validate.RkCode;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

/**
 * {@code dmtool field read <fieldPath>} — read a field back in full. A read op: {@code data} carries the
 * {@code field} path, its {@code type}, the friendly {@code required} state, its metadata ({@code label} /
 * descriptions / annotations), AND its per-kind CONFIG in the same block shape {@code field add} accepts
 * ({@code number} min/max/unit/scale, {@code string} pattern/length/patternMessage(s), {@code enum} values,
 * date/time formats — see {@code schema field add}). So a constraint an agent set is verifiable here without
 * touching the raw model JSON. A path that names no field fails fast. The field is addressed by a positional
 * path OR a {@code --group}/{@code --name} pair (G19); omit both to list every field's path.
 */
@Command(name = "read", mixinStandardHelpOptions = true,
        description = "Read a field back in full into `data` — type, required state, metadata, and its per-kind "
                + "config (number/string/enum/date config, same shape `field add` takes); by path or --group/--name.")
final class FieldReadCommand extends BaseCommand {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Parameters(index = "0", arity = "0..1", paramLabel = "<fieldPathInModel>",
            description = "the field's full name-path, e.g. /Order/Quantity; omit to list every field's path "
                    + "(or address it with --group + --name instead)")
    String field;

    @Option(names = "--group", paramLabel = "<path>",
            description = "the field's group (with --name, as an alternative to the positional path)")
    String group;

    @Option(names = "--name", paramLabel = "<name>",
            description = "the field's name (with --group, as an alternative to the positional path)")
    String name;

    @Override
    public Integer call() {
        String model = model();
        if (model == null) {
            return fail("pass -m <model.json> (before or after the verb)");
        }
        String fieldPath;
        try {
            fieldPath = FieldLocator.resolve(field, group, name).orElse(null);
        } catch (IllegalArgumentException e) {
            return fail(e.getMessage());
        }
        String json;
        try {
            json = Files.readString(Path.of(model));
        } catch (IOException e) {
            return fail("cannot read model: " + e.getMessage());
        }
        try {
            ModelDocument doc = ModelDocument.read(json);
            if (fieldPath == null) {
                return readNameIndex("field", "fields", doc.fieldPaths());
            }
            Optional<String> type = doc.fieldType(fieldPath);
            if (type.isEmpty()) {
                return refuseNotFound("field", "read", RkCode.NO_SUCH_FIELD, "no field at '" + fieldPath + "'",
                        fieldPath, "pass an existing field's full name-path (list them with `field read` — no arg)");
            }
            ObjectNode data = MAPPER.createObjectNode();
            data.put("field", fieldPath);
            data.put("type", type.get());
            // echo the friendly requiredness (G20) — same vocabulary `required` takes on write, so an agent
            // can verify a requiredness edit without decoding the stored requirednessConfig.mode
            doc.requiredness(fieldPath).ifPresent(req -> {
                switch (req) {
                    case OPTIONAL -> data.put("required", false);
                    case REQUIRED -> data.put("required", true);
                    case REQUIRED_IF_PARENT_PRESENT -> data.put("required", "ifParentPresent");
                }
            });
            // echo the field's METADATA (label / descriptions / annotations) so a targeted read confirms a
            // metadata edit — an agent shouldn't fall back to `export` (the whole model) just to check a label
            // (probe 05-fr), let alone read the raw model JSON.
            doc.labels(fieldPath).filter(m -> !m.isEmpty()).ifPresent(m -> data.set("label", mapNode(m)));
            putLocalized(data, "internalDescription", doc.internalDescription(fieldPath));
            putLocalized(data, "externalDescription", doc.externalDescription(fieldPath));
            putLocalized(data, "annotations", doc.annotations(fieldPath));
            // echo the per-kind CONFIG (NUMBER min/max/unit/scale, STRING pattern/length/patternMessage(s),
            // enum values, date/time formats) in the SAME block shape `field add` accepts — so an agent
            // confirms a constraint it set actually persisted, through this read alone, never the raw model
            // JSON (probe 05/07 number, 02 string: the missing read-back drove agents to inspect the file).
            doc.fieldConfig(fieldPath).ifPresent(data::setAll);
            // for a TYPEDEF-typed field the config above is just the `{typedef: <id>}` pointer; ALSO resolve
            // the type definition's own config so a constraint on the reusable type (scale/unit/pattern/…)
            // is confirmable through THIS read alone (probe 10: field read showed only the typedef name, not
            // the resolved scale — forcing a cross-read of `typedef read`/`model describe`).
            if (data.hasNonNull("typedef")) {
                doc.typeDefinitionConfig(data.get("typedef").asText()).ifPresent(cfg -> data.set("resolvedType", cfg));
            }
            ResultEnvelope env = ResultEnvelope.read("field", "read", "read " + fieldPath, true, List.of(), data);
            out().print(env.toJsonString());
            return env.exitCode();
        } catch (RuntimeException e) {
            return fail("read failed: " + e.getMessage());
        }
    }

    /** A {@code name → text} map as a JSON object. */
    private static ObjectNode mapNode(java.util.Map<String, String> m) {
        ObjectNode n = MAPPER.createObjectNode();
        m.forEach(n::put);
        return n;
    }

    /** Attach {@code key: {…}} only when the map is non-empty (omit it otherwise, keeping the read terse). */
    private static void putLocalized(ObjectNode data, String key, java.util.Map<String, String> m) {
        if (m != null && !m.isEmpty()) {
            data.set(key, mapNode(m));
        }
    }
}
