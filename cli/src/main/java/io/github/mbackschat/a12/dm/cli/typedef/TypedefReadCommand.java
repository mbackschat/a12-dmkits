package io.github.mbackschat.a12.dm.cli.typedef;

import io.github.mbackschat.a12.dm.cli.BaseCommand;
import io.github.mbackschat.a12.dm.cli.ResultEnvelope;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import io.github.mbackschat.a12.dm.modelkit.ModelDocument;
import io.github.mbackschat.a12.dm.rulekit.validate.RkCode;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

/**
 * {@code dmtool typedef read [<typeDefId>]} — read the model's reusable type definitions. Unified read
 * contract: no argument lists every LOCAL id ({@code data.typedefs}, this model's {@code content.typeDefinitions})
 * plus the model ids it IMPORTS type-defs from ({@code data.importedFrom} — the source-provenance dimension,
 * resolvable to files via {@code model info}); a {@code <typeDefId>} reads just that one local def
 * ({@code data.typedef}+{@code type}) <b>plus its per-kind CONFIG in the same block shape {@code add} accepts</b>
 * ({@code number} min/max/unit/scale, {@code string} pattern/length, {@code enum} values, date/time formats —
 * read-gap fix), so a constraint on a reusable type is verifiable through {@code read} alone. Typedefs are a
 * flat model-level list (addressed by id, not a path).
 */
@Command(name = "read", mixinStandardHelpOptions = true,
        description = "Read the declared type definitions into `data` — all ids, or one by <typeDefId> "
                + "(with its per-kind config: number/string/enum/date config in the `add` block shape).")
final class TypedefReadCommand extends BaseCommand {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Parameters(index = "0", arity = "0..1", paramLabel = "<typeDefId>",
            description = "a type-definition id to read just that one; omit to list every declared id "
                    + "(or address it with --typedef / --id)")
    String id;

    @Option(names = {"--typedef", "--id"}, paramLabel = "<typeDefId>",
            description = "the type-definition id — the named-flag alias for the positional (the locator "
                    + "contract: the target-noun flag always works). The positional wins if both are given.")
    String idFlag;

    @Override
    public Integer call() {
        String model = model();
        if (model == null) {
            return fail("pass -m <model.json> (before or after the verb)");
        }
        if (id == null) {   // locator contract: --typedef/--id is the named alias for the positional
            id = idFlag;
        }
        String json;
        try {
            json = Files.readString(Path.of(model));
        } catch (IOException e) {
            return fail("cannot read model: " + e.getMessage());
        }
        try {
            ModelDocument doc = ModelDocument.read(json);
            List<String> ids = doc.typeDefinitionIds();
            if (id != null) {
                Optional<String> kind = doc.typeDefinitionKind(id);
                if (kind.isEmpty()) {
                    return refuseNotFound("typedef", "read", RkCode.NO_SUCH_TYPEDEF,
                            "no type definition '" + id + "'", id,
                            "pass a declared type-def id (list them with `typedef read` — no arg); declared: " + ids);
                }
                ObjectNode data = MAPPER.createObjectNode();
                data.put("typedef", id);
                // the clean ValueKind name — same vocabulary `typedef add --kind`/`field read`/`model
                // describe` use, so the reusable type's kind round-trips and never leaks the kernel string
                data.put("kind", kind.get());
                // mirror `field read`: echo the per-kind CONFIG (number min/max/unit/scale, string
                // pattern/length, enum values, date/time formats) in the same block shape `typedef add`
                // accepts — so a constraint on a reusable type is verifiable through `read` alone (read-gap
                // fix B, the typedef sibling), never the raw model JSON
                doc.typeDefinitionConfig(id).ifPresent(data::setAll);
                ResultEnvelope env = ResultEnvelope.read("typedef", "read",
                        "read type definition " + id, true, List.of(), data);
                out().print(env.toJsonString());
                return env.exitCode();
            }
            ObjectNode data = MAPPER.createObjectNode();
            ArrayNode arr = data.putArray("typedefs");
            ids.forEach(arr::add);
            // source provenance: `typedefs` are LOCAL (this model's content.typeDefinitions); a model can
            // ALSO use type-defs IMPORTED from other models (merged by id at expand) — surface those import
            // sources so an agent sees the full picture (resolve them to files via `model info`)
            List<String> imports = doc.typeDefImports();
            if (!imports.isEmpty()) {
                ArrayNode importsArr = data.putArray("importedFrom");
                imports.forEach(importsArr::add);
            }
            ResultEnvelope env = ResultEnvelope.read("typedef", "read",
                    "read " + ids.size() + " local type definition(s)", true, List.of(), data);
            out().print(env.toJsonString());
            return env.exitCode();
        } catch (RuntimeException e) {
            return fail("read failed: " + e.getMessage());
        }
    }
}
