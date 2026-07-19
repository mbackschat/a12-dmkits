package io.github.mbackschat.a12.dm.cli.model;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.github.mbackschat.a12.dm.cli.BaseCommand;
import io.github.mbackschat.a12.dm.cli.WorkspaceOption;
import io.github.mbackschat.a12.dm.cli.interp.IncludeRelativizationException;
import io.github.mbackschat.a12.dm.cli.interp.InterpreterEngine;
import io.github.mbackschat.a12.dm.cli.interp.InterpreterModelLoader;
import io.github.mbackschat.a12.dm.cli.interp.JsonInstanceSink;
import io.github.mbackschat.a12.dm.core.ModelResolver;
import io.github.mbackschat.a12.dm.core.UnresolvedReferenceException;
import io.github.mbackschat.a12.dm.interpreter.gen.SeedGenerator;
import io.github.mbackschat.a12.dm.interpreter.model.ModelDef;
import io.github.mbackschat.a12.dm.rulekit.validate.RkCode;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Option;

/**
 * {@code dmtool model seed} — generate a best-effort, model-derived sample candidate document INSTANCE (JSON)
 * from the model (structure-shaped, not guaranteed model-accepted), using the
 * kernel-free interpreter generator ({@code SeedGenerator} + {@code ValidValues}). The output is a
 * {@code {"fields": {…}}} tree (see {@code schema model eval}) that round-trips straight into
 * {@code model eval}/{@code compute}/{@code rule eval} as {@code --instance}. Native-safe (no kernel); deterministic
 * for a fixed {@code --seed}. Emits the document JSON directly (an artifact, not the result envelope).
 *
 * <p>The model is prepared through the SAME kernel-free boundary the runtime verbs use
 * ({@link InterpreterEngine#expandForEval}) — includes mounted, imported typedefs inlined, opaque ids
 * normalized to name-paths — so a multi-file model is seeded from its real, self-contained structure (IF145).
 * A refusal (a relativization residual or an unresolved reference) is the structured {@code refused} envelope;
 * the document artifact is emitted only on success.
 */
@Command(name = "seed", mixinStandardHelpOptions = true,
        description = "Generate a best-effort, model-derived sample candidate document INSTANCE (JSON) from the model "
                + "(structure-shaped, not guaranteed model-accepted) — kernel-free, "
                + "native-safe, deterministic per --seed; feed it to `model eval`/`compute`/`rule eval` as --instance.")
final class ModelSeedCommand extends BaseCommand {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Option(names = {"-s", "--seed"}, defaultValue = "0", paramLabel = "<n>",
            description = "RNG seed for deterministic generation (default: ${DEFAULT-VALUE})")
    long seed;

    @Option(names = "--rows", paramLabel = "<group>:<count>",
            description = "rows to instantiate for a repeatable group (repeatable option; e.g. "
                    + "`--rows /Order/Items:3`). Groups not named use --rows-default.")
    List<String> rows = new ArrayList<>();

    @Option(names = "--rows-default", defaultValue = "1", paramLabel = "<n>",
            description = "rows for any repeatable group not named by --rows (default: ${DEFAULT-VALUE})")
    int rowsDefault;

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

        // Prepare through the SAME kernel-free boundary the runtime verbs use (IF145): mount includes + inline
        // imported typedefs + normalize opaque ids to name-paths, so an includes/typedef/opaque-id model is
        // seeded from its real, self-contained structure rather than the raw stub the loader would otherwise
        // silently under-read (a dropped mount, a typedef flattened to STRING). A rare relativization residual
        // refuses loudly here — seed never consumes rule conditions, but one boundary beats a divergent seed path.
        ModelResolver resolver = workspace.resolverFor(model);
        String interpJson;
        try {
            interpJson = InterpreterEngine.expandForEval(modelJson, resolver);
        } catch (IncludeRelativizationException e) {
            return refusePrecheck("model", "seed", RkCode.INTERPRETER_INCLUDE_RULES_UNSUPPORTED,
                    e.getMessage(), model, RkCode.INTERPRETER_INCLUDE_RULES_UNSUPPORTED.fix());
        } catch (UnresolvedReferenceException e) {
            return refusePrecheck("model", "seed", RkCode.UNRESOLVED_REFERENCE, e.getMessage(), model,
                    RkCode.UNRESOLVED_REFERENCE.fix());
        } catch (RuntimeException e) {
            return fail("cannot expand model: " + e.getMessage());
        }

        ModelDef def;
        try {
            def = InterpreterModelLoader.load(interpJson);
        } catch (RuntimeException badModel) {
            return fail("cannot load model: " + badModel.getMessage());
        }

        Map<String, Integer> rowCounts;
        try {
            rowCounts = parseRows();
        } catch (IllegalArgumentException badRows) {
            return fail(badRows.getMessage());
        }

        JsonInstanceSink sink = new JsonInstanceSink(MAPPER, def.getRepeatable());
        try {
            new SeedGenerator(def).populate(sink, seed, rowCounts, rowsDefault);
        } catch (RuntimeException e) {
            return fail("seed generation failed: " + e.getMessage());
        }

        out().print(sink.result().toPrettyString() + "\n");
        return 0;
    }

    /** Parse the repeatable `--rows <group>:<count>` options into a group → count map. */
    private Map<String, Integer> parseRows() {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (String row : rows) {
            int colon = row.lastIndexOf(':');
            if (colon <= 0 || colon == row.length() - 1) {
                throw new IllegalArgumentException("--rows expects <group>:<count> (e.g. /Order/Items:3), got: " + row);
            }
            int count;
            try {
                count = Integer.parseInt(row.substring(colon + 1).trim());
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("--rows count must be an integer, got: " + row);
            }
            if (count < 0) {
                throw new IllegalArgumentException("--rows count must be >= 0, got: " + row);
            }
            counts.put(row.substring(0, colon).trim(), count);
        }
        return counts;
    }
}
