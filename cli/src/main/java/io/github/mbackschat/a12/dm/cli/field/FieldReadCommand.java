package io.github.mbackschat.a12.dm.cli.field;

import io.github.mbackschat.a12.dm.cli.BaseCommand;
import io.github.mbackschat.a12.dm.cli.ConsistencyEvidence;
import io.github.mbackschat.a12.dm.cli.ReadPayloads;
import io.github.mbackschat.a12.dm.cli.ResultEnvelope;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

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

    @Parameters(index = "0", arity = "0..1", paramLabel = "<fieldPathInModel>",
            description = "the field's full name-path, e.g. /Order/Quantity; omit to list every field's path "
                    + "(or address it with --field, or --group + --name)")
    String field;

    @Option(names = "--field", paramLabel = "<fieldPathInModel>",
            description = "the field's full name-path — the named-flag alias for the positional (the locator "
                    + "contract: the target-noun flag always works). The positional wins if both are given.")
    String fieldFlag;

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
            fieldPath = FieldLocator.resolve(field != null ? field : fieldFlag, group, name).orElse(null);
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
                return readNameIndex("fields", doc.fieldPaths(), ConsistencyEvidence.none());
            }
            Optional<String> kind = doc.fieldKind(fieldPath);
            if (kind.isEmpty()) {
                // `field read` is tree-only (no include expansion), so it sees this model's OWN fields, not the
                // fields an include mounts. `model describe` DOES expand includes and lists them — so an agent
                // can learn an included path from `describe` and then be refused here. When the model has
                // includes, say so, so the agent isn't bounced between two surfaces that disagree (probe 04).
                String fix = "pass an existing field's full name-path (list them with `field read` — no arg)";
                if (!doc.includes().isEmpty()) {
                    fix += "; an INCLUDED field (one `model describe` lists under an include mount) is read on its"
                            + " OWN model — this listing has only this model's own fields";
                }
                return refuseNotFound(RkCode.NO_SUCH_FIELD, "no field at '" + fieldPath + "'",
                        fieldPath, fix);
            }
            ObjectNode data = ReadPayloads.field(doc, fieldPath);
            // Parse-and-index only — no consistency gate on this path, so no verdict and no provenance.
            ResultEnvelope env = ResultEnvelope.queryResult(resultIdentity(), "read " + fieldPath, data,
                    ConsistencyEvidence.none());
            out().print(env.toJsonString());
            return env.exitCode();
        } catch (RuntimeException e) {
            return fail("read failed: " + e.getMessage());
        }
    }

}
