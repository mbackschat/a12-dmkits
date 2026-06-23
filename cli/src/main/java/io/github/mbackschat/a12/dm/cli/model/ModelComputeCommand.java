package io.github.mbackschat.a12.dm.cli.model;

import io.github.mbackschat.a12.dm.cli.BaseCommand;
import io.github.mbackschat.a12.dm.cli.Cli;
import io.github.mbackschat.a12.dm.cli.DocInstance;
import io.github.mbackschat.a12.dm.cli.ResultEnvelope;
import io.github.mbackschat.a12.dm.cli.WorkspaceOption;
import io.github.mbackschat.a12.dm.cli.interp.InterpreterEngine;

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
 * {@code dmtool model compute --doc <document.json>} — run the kernel's {@code compute} over a document
 * INSTANCE and report what each computed field becomes (value / cleared / error). Read-only: the {@code
 * computed[]} payload rides {@code data}; the document is never mutated.
 */
@Command(name = "compute", mixinStandardHelpOptions = true,
        description = "Run computations over a document INSTANCE; report each computed field's value in `data` (read-only).")
final class ModelComputeCommand extends BaseCommand {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Option(names = "--doc", required = true, paramLabel = "<document.json>",
            description = "the document INSTANCE (field data) — a nested JSON tree under \"fields\". See `schema model compute`.")
    String doc;

    @Option(names = "--locale", defaultValue = "en_US", paramLabel = "<locale>",
            description = "the computation locale (default: ${DEFAULT-VALUE})")
    String localeTag;

    @Option(names = "--kernel",
            description = "use the A12 kernel engine instead of the default interpreter (JVM only; "
                    + "not available in the native image, where the interpreter is the only engine)")
    boolean useKernel;

    @Mixin
    WorkspaceOption workspace = new WorkspaceOption();

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
        IDocumentModelReferenceResolver resolver = workspace.resolverFor(model);

        ObjectNode data;
        try {
            if (useKernel) {
                if (Cli.isNativeImage()) {
                    return fail("--kernel is not available in the native image — the interpreter is the only engine here; drop --kernel");
                }
                Locale locale = Locale.forLanguageTag(localeTag.replace('_', '-'));
                ComputeResult result = KernelAdapter.ofJson(modelJson, resolver).compute(fields, locale);
                data = MAPPER.createObjectNode();
                ArrayNode computed = data.putArray("computed");
                for (ComputedField c : result.computed()) {
                    ObjectNode cn = computed.addObject();
                    cn.put("field", c.path());
                    cn.put("outcome", c.outcome());
                    c.value().ifPresent(v -> cn.put("value", v));
                }
            } else {
                data = InterpreterEngine.computeData(modelJson, fields, localeTag);
            }
        } catch (IllegalArgumentException badDoc) {
            return fail(badDoc.getMessage());
        } catch (RuntimeException e) {
            return fail("computation failed: " + e.getMessage());
        }

        ResultEnvelope env = ResultEnvelope.read("model", "compute",
                "computed " + data.get("computed").size() + " field(s)", true, List.of(), data);
        out().print(env.toJsonString());
        return env.exitCode();
    }
}
