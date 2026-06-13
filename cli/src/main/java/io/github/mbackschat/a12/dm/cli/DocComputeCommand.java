package io.github.mbackschat.a12.dm.cli;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.mgmtp.a12.kernel.md.model.api.services.IDocumentModelReferenceResolver;

import io.github.mbackschat.a12.dm.adapter.ComputeResult;
import io.github.mbackschat.a12.dm.adapter.ComputedField;
import io.github.mbackschat.a12.dm.adapter.KernelAdapter;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Option;

/**
 * {@code dmtool doc compute --doc <document.json>} — run the kernel's {@code compute} over a document
 * INSTANCE and report what each computed field becomes (value / cleared / error). Read-only: the {@code
 * computed[]} payload rides {@code data}; the document is never mutated.
 */
@Command(name = "compute", mixinStandardHelpOptions = true,
        description = "Run computations over a document INSTANCE; report each computed field's value in `data` (read-only).")
final class DocComputeCommand extends BaseCommand {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Option(names = "--doc", required = true, paramLabel = "<document.json>",
            description = "the document INSTANCE (field data) — a nested JSON tree under \"fields\". See `schema doc compute`.")
    String doc;

    @Option(names = "--locale", defaultValue = "en_US", paramLabel = "<locale>",
            description = "the computation locale (default: ${DEFAULT-VALUE})")
    String localeTag;

    @Mixin
    IncludeDirOption includes = new IncludeDirOption();

    @Override
    public Integer call() {
        String model = model();
        if (model == null) {
            return fail("pass -m <model.json> (before or after the verb)");
        }
        String modelJson;
        Map<String, Object> fields;
        try {
            modelJson = Files.readString(Path.of(model));
        } catch (IOException e) {
            return fail("cannot read model: " + e.getMessage());
        }
        try {
            fields = DocInstance.readFields(doc);
        } catch (IllegalArgumentException badShape) {
            return fail(badShape.getMessage());
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

        ObjectNode data = MAPPER.createObjectNode();
        ArrayNode computed = data.putArray("computed");
        for (ComputedField c : result.computed()) {
            ObjectNode cn = computed.addObject();
            cn.put("field", c.path());
            cn.put("outcome", c.outcome());
            c.value().ifPresent(v -> cn.put("value", v));
        }
        ResultEnvelope env = ResultEnvelope.read("doc", "compute",
                "computed " + result.computed().size() + " field(s)", true, List.of(), data);
        out().print(env.toJsonString());
        return env.exitCode();
    }
}
