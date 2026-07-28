package io.github.mbackschat.a12.dm.cli.model;

import io.github.mbackschat.a12.dm.cli.BaseCommand;
import io.github.mbackschat.a12.dm.cli.ResultEnvelope;
import io.github.mbackschat.a12.dm.cli.WorkspaceOption;
import io.github.mbackschat.a12.dm.cli.interp.InterpreterEngine;
import io.github.mbackschat.a12.dm.interpreter.DocumentInputError;
import io.github.mbackschat.a12.dm.interpreter.UnsupportedModelTimeZoneException;
import io.github.mbackschat.a12.dm.interpreter.InvalidModelTimeZoneException;
import io.github.mbackschat.a12.dm.rulekit.validate.RkCode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

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
            description = "the document INSTANCE (field data) in A12's canonical Document JSON — a nested tree "
                    + "mirroring the model, keyed by root group name. See `schema model compute`.")
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
        try {
            modelJson = Files.readString(Path.of(model));
        } catch (IOException e) {
            return fail("cannot read model: " + e.getMessage());
        }
        String documentJson = readInstanceJson(instance);
        if (documentJson == null) {
            return 2;
        }
        ModelResolver resolver = workspace.resolverFor(model);

        // Use the public portable workspace so an includes-bearing model computes over its mounted content.
        String interpJson =
                InterpreterEngine.prepareForEval(modelJson, Path.of(model).getFileName().toString(), resolver);

        ObjectNode data;
        try {
            data = InterpreterEngine.computeData(interpJson, documentJson, localeTag);
        } catch (UnsupportedModelTimeZoneException e) {
            return refuseExplainedPrecheck("model", "compute", RkCode.INTERPRETER_TIMEZONE_UNSUPPORTED, e.getMessage(),
                    model, RkCode.INTERPRETER_TIMEZONE_UNSUPPORTED.fix());
        } catch (InvalidModelTimeZoneException e) {
            return refuseExplainedPrecheck("model", "compute", RkCode.INVALID_CONFIG_VALUE, e.getMessage(),
                    model, RkCode.INVALID_CONFIG_VALUE.fix());
        } catch (DocumentInputError badDoc) {
            return failInvalidInstance(badDoc);
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
