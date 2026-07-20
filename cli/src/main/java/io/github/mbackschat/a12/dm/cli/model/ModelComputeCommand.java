package io.github.mbackschat.a12.dm.cli.model;

import io.github.mbackschat.a12.dm.cli.BaseCommand;
import io.github.mbackschat.a12.dm.cli.DocInstance;
import io.github.mbackschat.a12.dm.cli.ResultEnvelope;
import io.github.mbackschat.a12.dm.cli.WorkspaceOption;
import io.github.mbackschat.a12.dm.cli.interp.IncludeRelativizationException;
import io.github.mbackschat.a12.dm.cli.interp.InterpreterEngine;
import io.github.mbackschat.a12.dm.core.UnresolvedReferenceException;
import io.github.mbackschat.a12.dm.interpreter.UnsupportedModelTimeZoneException;
import io.github.mbackschat.a12.dm.rulekit.validate.RkCode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.mbackschat.a12.dm.core.ModelResolver;

import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Option;

/**
 * {@code dmtool model compute --instance <document.json>} — run the model's {@code compute} over a document
 * INSTANCE and report what each computed field becomes (value / cleared / error). Read-only: the {@code
 * computed[]} payload rides {@code data}; the document is never mutated.
 *
 * <p>Runs on the native-safe {@code dm.interpreter} — the sole runtime eval engine on every target (the
 * user-facing {@code --kernel} eval flag was retired, INTERPRETER-DROPIN-CERTIFICATE).
 */
@Command(name = "compute", mixinStandardHelpOptions = true,
        description = "Run computations over a document INSTANCE; report each computed field's value in `data` (read-only).")
final class ModelComputeCommand extends BaseCommand {

    @Option(names = "--instance", required = true, paramLabel = "<document.json>",
            description = "the document INSTANCE (field data) — a nested JSON tree under \"fields\". See `schema model compute`.")
    String instance;

    @Option(names = "--locale", defaultValue = "en_US", paramLabel = "<locale>",
            description = "the computation locale (default: ${DEFAULT-VALUE})")
    String localeTag;

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
            fields = DocInstance.readFields(instance);
        } catch (IllegalArgumentException badShape) {
            return fail(badShape.getMessage());
        } catch (IOException e) {
            return fail("cannot read document: " + e.getMessage());
        }
        ModelResolver resolver = workspace.resolverFor(model);

        // Pre-expand kernel-free so an includes-bearing model computes with its mounted content present
        // (decoupling Stage 2 / A4).
        String interpJson;
        try {
            interpJson = InterpreterEngine.expandForEval(modelJson, resolver);
        } catch (IncludeRelativizationException e) {
            return refusePrecheck("model", "compute", RkCode.INTERPRETER_INCLUDE_RULES_UNSUPPORTED,
                    e.getMessage(), model, RkCode.INTERPRETER_INCLUDE_RULES_UNSUPPORTED.fix());
        } catch (UnresolvedReferenceException e) {
            return refusePrecheck("model", "compute", RkCode.UNRESOLVED_REFERENCE, e.getMessage(), model,
                    RkCode.UNRESOLVED_REFERENCE.fix());
        } catch (RuntimeException e) {
            return fail("cannot expand model: " + e.getMessage());
        }

        ObjectNode data;
        try {
            data = InterpreterEngine.computeData(interpJson, fields, localeTag);
        } catch (UnsupportedModelTimeZoneException e) {
            return refusePrecheck("model", "compute", RkCode.INTERPRETER_TIMEZONE_UNSUPPORTED, e.getMessage(),
                    model, RkCode.INTERPRETER_TIMEZONE_UNSUPPORTED.fix());
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
