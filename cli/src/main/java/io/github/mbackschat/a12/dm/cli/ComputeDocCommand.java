package io.github.mbackschat.a12.dm.cli;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.mgmtp.a12.kernel.md.model.api.services.IDocumentModelReferenceResolver;

import io.github.mbackschat.a12.dm.adapter.ComputedField;
import io.github.mbackschat.a12.dm.adapter.ComputeResult;
import io.github.mbackschat.a12.dm.adapter.KernelAdapter;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

/**
 * The {@code compute-doc} verb (CLI-SPEC §4) — the computation peer of {@code eval-doc}: run the real
 * kernel's {@code compute} over a <em>document instance</em> and report what each computed field becomes
 * (a value, cleared, or an error). The kernel keeps {@code validateFull} and {@code compute} as separate
 * operations, so this is the way to see computed values; it is <b>read-only</b> — {@code compute} returns
 * a result (the input is never mutated), and this verb only PRINTS the outcomes (never writes {@code --doc}).
 *
 * <p>The document is the same NESTED JSON tree {@code eval-doc} takes (under {@code "fields"}): a repeatable
 * group is an array of row objects, a field its text value. See {@code schema compute-doc}.
 */
@Command(name = "compute-doc", mixinStandardHelpOptions = true,
        description = "Run the kernel's computations over a document INSTANCE and report each computed field's value (read-only).")
final class ComputeDocCommand extends BaseCommand {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Parameters(index = "0", paramLabel = "<model.json>",
            description = "the DM-JSON model FILE — distinct from --doc (the document data)")
    String model;

    @Option(names = "--doc", required = true, paramLabel = "<document.json>",
            description = "the document INSTANCE (field data) — a nested JSON tree under \"fields\". See `schema compute-doc`.")
    String doc;

    @Option(names = "--locale", defaultValue = "en_US", paramLabel = "<locale>",
            description = "the computation locale (default: ${DEFAULT-VALUE})")
    String localeTag;

    @Mixin
    IncludeDirOption includes = new IncludeDirOption();

    @Override
    public Integer call() {
        String modelJson;
        Map<String, Object> fields;
        try {
            modelJson = Files.readString(Path.of(model));
        } catch (IOException e) {
            return fail("cannot read model: " + e.getMessage());
        }
        try {
            JsonNode root = MAPPER.readTree(Files.readString(Path.of(doc)));
            JsonNode fieldsNode = root.has("fields") ? root.get("fields") : root;
            if (!fieldsNode.isObject()) {
                return fail("the document needs a top-level \"fields\" object (a nested field tree); see `schema compute-doc`");
            }
            fields = MAPPER.convertValue(fieldsNode, new TypeReference<Map<String, Object>>() { });
        } catch (IOException e) {
            return fail("cannot read document: " + e.getMessage());
        }

        IDocumentModelReferenceResolver resolver = includes.resolverFor(model);
        Locale locale = Locale.forLanguageTag(localeTag.replace('_', '-'));

        ComputeResult result;
        try {
            result = KernelAdapter.ofJson(modelJson, resolver).compute(fields, locale);
        } catch (IllegalArgumentException badDoc) {
            return fail(badDoc.getMessage());
        } catch (RuntimeException e) {
            return fail("computation failed: " + e.getMessage());
        }

        ObjectNode root = MAPPER.createObjectNode();
        ArrayNode computed = root.putArray("computed");
        for (ComputedField c : result.computed()) {
            ObjectNode cn = computed.addObject();
            cn.put("field", c.path());
            cn.put("outcome", c.outcome());
            c.value().ifPresent(v -> cn.put("value", v));
        }
        try {
            out().print(MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(root) + "\n");
            return 0;
        } catch (JsonProcessingException e) {
            return fail("cannot serialize compute-doc result: " + e.getMessage());
        }
    }
}
