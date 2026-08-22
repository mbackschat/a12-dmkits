package io.github.mbackschat.a12.dm.cli.typedef;

import io.github.mbackschat.a12.dm.cli.BaseCommand;
import io.github.mbackschat.a12.dm.cli.ConsistencyEvidence;
import io.github.mbackschat.a12.dm.cli.ReadPayloads;
import io.github.mbackschat.a12.dm.cli.ResultEnvelope;

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
        if (id == null) {   // locator contract: --typedef/--id is the named alias for the positional
            id = idFlag;
        }
        String json = readModelJson(model);
        if (json == null) {
            return 2;
        }
        try {
            ModelDocument doc = ModelDocument.read(json);
            List<String> ids = doc.typeDefinitionIds();
            if (id != null) {
                Optional<String> kind = doc.typeDefinitionKind(id);
                if (kind.isEmpty()) {
                    return refuseNotFound(RkCode.NO_SUCH_TYPEDEF,
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
                doc.typeDefinitionConfigJson(id).ifPresent(cfg -> data.setAll(ReadPayloads.configNode(cfg)));
                ResultEnvelope env = ResultEnvelope.queryResult(resultIdentity(), "read type definition " + id,
                        data, ConsistencyEvidence.none());
                out().print(env.toJsonString());
                return env.exitCode();
            }
            // T3: the shared no-arg list shape (typedefs + importedFrom), same as `apply` TYPEDEF_READ
            ObjectNode data = ReadPayloads.typedefList(doc);
            ResultEnvelope env = ResultEnvelope.queryResult(resultIdentity(),
                    "read " + ids.size() + " local type definition(s)", data, ConsistencyEvidence.none());
            out().print(env.toJsonString());
            return env.exitCode();
        } catch (RuntimeException e) {
            return fail("read failed: " + e.getMessage());
        }
    }
}
