package io.github.mbackschat.a12.dm.cli.model;

import io.github.mbackschat.a12.dm.cli.BaseCommand;
import io.github.mbackschat.a12.dm.cli.CustomSupportOptions;
import io.github.mbackschat.a12.dm.cli.EvalClockOption;
import io.github.mbackschat.a12.dm.cli.ResultEnvelope;
import io.github.mbackschat.a12.dm.cli.RuntimeEvidence;
import io.github.mbackschat.a12.dm.cli.WorkspaceOption;
import io.github.mbackschat.a12.dm.cli.interp.InterpreterEngine;
import io.github.mbackschat.a12.dm.interpreter.DocumentInputError;
import io.github.mbackschat.a12.dm.interpreter.UnsupportedModelTimeZoneException;
import io.github.mbackschat.a12.dm.interpreter.InvalidModelTimeZoneException;
import io.github.mbackschat.a12.dm.interpreter.model.UndeclaredReference;
import io.github.mbackschat.a12.dm.rulekit.validate.RkCode;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

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

    @Mixin
    CustomSupportOptions customSupport = new CustomSupportOptions();

    @Mixin
    EvalClockOption evalClock = new EvalClockOption();

    @Override
    public Integer call() {
        Optional<Instant> clock = evalClock.instant();
        if (clock.isEmpty()) {
            return refuseOptionValue(RkCode.INVALID_VALUE, evalClock.refusalSummary(), "--clock", null,
                    EvalClockOption.FIX);
        }
        String model = model();
        String modelJson = readModelJson(model);
        if (modelJson == null) {
            return 2;
        }
        String documentJson = readInstanceJson(instance);
        if (documentJson == null) {
            return 2;
        }
        ModelResolver resolver = workspace.resolverFor(model);

        // Use the public portable workspace so an includes-bearing model computes over its mounted content.
        String interpJson =
                InterpreterEngine.prepareForEval(modelJson, Path.of(model).getFileName().toString(), resolver);

        // An operation naming an entity the model does not declare has no empty reading to fall back on, so it
        // used to die mid-computation as an untyped failure. Same gate, same code as the eval verbs (G46) — but
        // this verb validates nothing, so only the computations are in its scope.
        List<UndeclaredReference> undeclared = InterpreterEngine.undeclaredComputationReferences(interpJson);
        if (!undeclared.isEmpty()) {
            return refuseUndeclaredReferences(undeclared);
        }

        ObjectNode data;
        try {
            data = InterpreterEngine.computeData(InterpreterEngine.Evaluation.builder().model(interpJson)
                    .document(documentJson).locale(localeTag).inputs(customSupport.runtimeInputs(clock.get()))
                    .build());
        } catch (UnsupportedModelTimeZoneException e) {
            return refuseExplainedPrecheck(RkCode.INTERPRETER_TIMEZONE_UNSUPPORTED, e.getMessage(),
                    model, RkCode.INTERPRETER_TIMEZONE_UNSUPPORTED.fix());
        } catch (InvalidModelTimeZoneException e) {
            return refuseExplainedPrecheck(RkCode.INVALID_CONFIG_VALUE, e.getMessage(),
                    model, RkCode.INVALID_CONFIG_VALUE.fix());
        } catch (DocumentInputError badDoc) {
            return failInvalidInstance(badDoc);
        } catch (IllegalArgumentException badDoc) {
            return fail(badDoc.getMessage());
        } catch (RuntimeException e) {
            return fail("computation failed: " + e.getMessage());
        }

        // Two counts, because one cannot stand for the other: 2 declarations over 5 rows is neither "2 field(s)"
        // nor "10 field(s)". The old summary counted INSTANCES and called them fields, so it was wrong on both
        // axes at once — and the list it summarized mixes declarations, so position was ambiguous too.
        RuntimeEvidence evidence = RuntimeEvidence.interpreter();
        ResultEnvelope refusal = customSupport.strictRefusal(resultIdentity(), data, evidence);
        if (refusal != null) {
            out().print(refusal.toJsonString());
            return refusal.exitCode();
        }
        int declarations = data.path("declarations").asInt();
        int instances = data.path("instances").asInt();
        ResultEnvelope env = ResultEnvelope.runtimeObservation(resultIdentity(),
                "computed " + declarations + " computation(s) over " + instances + " field instance(s)",
                data, evidence);
        out().print(env.toJsonString());
        return env.exitCode();
    }
}
