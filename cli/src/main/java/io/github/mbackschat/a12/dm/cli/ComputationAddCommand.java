package io.github.mbackschat.a12.dm.cli;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.mgmtp.a12.kernel.md.model.api.services.IDocumentModelReferenceResolver;

import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel;
import io.github.mbackschat.a12.dm.rulekit.validate.RuleValidator;
import io.github.mbackschat.a12.dm.rulekit.validate.ValidationResult;
import io.github.mbackschat.a12.dm.rulekit.write.ModelWriter;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

/**
 * {@code dmtool computation add <computation-spec.json>} — persist a computation (computed field +
 * alternatives), kernel-validated before + after, written back (in place; {@code -o}/{@code --dry-run}).
 * A rejected computation yields a {@code rejected} envelope (nothing written). See `schema computation add`.
 */
@Command(name = "add", mixinStandardHelpOptions = true,
        description = "Persist a computation from a JSON computation-spec (kernel-validated before + after).")
final class ComputationAddCommand extends BaseCommand {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Parameters(index = "0", paramLabel = "<computation-spec.json>",
            description = "the computation envelope as JSON (computedField, alternatives, messages, …)")
    String specPath;

    @Option(names = {"--output", "-o"}, paramLabel = "<file>",
            description = "write the result here instead of in place (overwriting the model)")
    String outputPath;

    @Option(names = "--dry-run", description = "preview only — write NOTHING")
    boolean dryRun;

    @Mixin
    IncludeDirOption includes = new IncludeDirOption();

    @Override
    public Integer call() {
        String model = model();
        if (model == null) {
            return fail("pass -m <model.json> (before or after the verb)");
        }
        String modelJson;
        JsonNode spec;
        try {
            modelJson = Files.readString(Path.of(model));
            spec = MAPPER.readTree(Files.readString(Path.of(specPath)));
        } catch (IOException e) {
            return fail("cannot read input: " + e.getMessage());
        }
        ComputationSpec.Parsed parsed = ComputationSpec.parse(spec);
        if (!parsed.ok()) {
            return fail(parsed.error());
        }
        IDocumentModelReferenceResolver resolver = includes.resolverFor(model);
        ValidationResult check = new RuleValidator().check(modelJson, parsed.draft(), resolver);
        if (!check.ok()) {
            ResultEnvelope env = ResultEnvelope.rejected("computation", "add",
                    "computation rejected by the kernel (" + check.diagnostics().size() + " finding(s))",
                    check.diagnostics());
            out().print(env.toJsonString());
            return env.exitCode();
        }
        String persisted = ModelWriter.addComputation(modelJson, parsed.draft(), parsed.messages());
        try {
            BoundModel.ofJson(persisted, resolver);
        } catch (RuntimeException e) {
            return fail("persisted computation is invalid (e.g. missing a message for a declared locale): "
                    + e.getMessage());
        }
        ObjectNode changed = MAPPER.createObjectNode();
        changed.put("computation", parsed.fullName());
        if (dryRun) {
            ResultEnvelope env = ResultEnvelope.preview("computation", "add",
                    "would add computation " + parsed.fullName(), changed);
            out().print(env.toJsonString());
            return env.exitCode();
        }
        String dest = outputPath != null ? outputPath : model;
        try {
            Files.writeString(Path.of(dest), persisted);
        } catch (IOException e) {
            return fail("cannot write output: " + e.getMessage());
        }
        ResultEnvelope env = ResultEnvelope.applied("computation", "add",
                "added computation " + parsed.fullName(), changed, dest);
        out().print(env.toJsonString());
        return env.exitCode();
    }
}
