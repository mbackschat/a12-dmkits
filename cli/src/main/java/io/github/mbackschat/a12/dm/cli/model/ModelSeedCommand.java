package io.github.mbackschat.a12.dm.cli.model;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.cfg.JsonNodeFeature;

import io.github.mbackschat.a12.dm.cli.BaseCommand;
import io.github.mbackschat.a12.dm.cli.WorkspaceOption;
import io.github.mbackschat.a12.dm.cli.interp.InterpreterEngine;
import io.github.mbackschat.a12.dm.cli.interp.InterpreterModelLoader;
import io.github.mbackschat.a12.dm.core.ModelResolver;
import io.github.mbackschat.a12.dm.interpreter.eval.Document;
import io.github.mbackschat.a12.dm.interpreter.eval.DocumentJsonKt;
import io.github.mbackschat.a12.dm.interpreter.eval.DocumentKt;
import io.github.mbackschat.a12.dm.interpreter.gen.DocumentSinkKt;
import io.github.mbackschat.a12.dm.interpreter.gen.SeedGenerator;
import io.github.mbackschat.a12.dm.interpreter.model.ModelDef;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Option;

/**
 * {@code dmtool model seed} — generate a best-effort, model-derived sample candidate document INSTANCE (JSON)
 * from the model (structure-shaped, not guaranteed model-accepted), using the
 * kernel-free interpreter generator ({@code SeedGenerator} + {@code ValidValues}). The output is
 * A12's canonical Document JSON (see {@code schema model eval}) that round-trips straight into
 * {@code model eval}/{@code compute}/{@code rule eval} as {@code --instance}. Native-safe (no kernel); deterministic
 * for a fixed {@code --seed}. Emits the document JSON directly (an artifact, not the result envelope).
 *
 * <p>The generator fills an interpreter {@code Document} and the interpreter's own codec writes it, so this verb
 * <b>cannot</b> drift from the shape the {@code --instance}-consuming verbs read — array-versus-object comes
 * from DECLARED repeatability, a number/boolean is native JSON, an unfilled cell is {@code null}, and the key
 * order is the Kernel writer's. The CLI used to carry a second writer for that shape; it is gone.
 *
 * <p>The model is prepared through the SAME kernel-free boundary the runtime verbs use
 * ({@link InterpreterEngine#prepareForEval}) — includes mounted, imported typedefs inlined, opaque ids
 * normalized to name-paths — so a multi-file model is seeded from its real, self-contained structure (IF145).
 * A refusal (a surviving unmapped included reference or an unresolved model reference) is the structured
 * {@code refused} envelope;
 * the document artifact is emitted only on success.
 */
@Command(name = "seed", mixinStandardHelpOptions = true,
        description = "Generate a best-effort, model-derived sample candidate document INSTANCE (JSON) from the model "
                + "(structure-shaped, not guaranteed model-accepted) — kernel-free, "
                + "native-safe, deterministic per --seed; feed it to `model eval`/`compute`/`rule eval` as --instance.")
final class ModelSeedCommand extends BaseCommand {

    /** Re-indentation only, and configured so it cannot re-normalize a number — see {@link #pretty}. */
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            .configure(JsonNodeFeature.STRIP_TRAILING_BIGDECIMAL_ZEROES, false)
            .enable(JsonGenerator.Feature.WRITE_BIGDECIMAL_AS_PLAIN);

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
        // silently under-read (a dropped mount, a typedef flattened to STRING). A surviving unmapped included
        // reference refuses loudly here — seed never consumes rule conditions, but one boundary prevents drift.
        ModelResolver resolver = workspace.resolverFor(model);
        String interpJson =
                InterpreterEngine.prepareForEval(modelJson, Path.of(model).getFileName().toString(), resolver);

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

        Document document = DocumentKt.newDocument(def);
        String documentJson;
        try {
            new SeedGenerator(def).populate(DocumentSinkKt.asSink(document), seed, rowCounts, rowsDefault);
            documentJson = DocumentJsonKt.toDocumentJson(document, def);
        } catch (RuntimeException e) {
            return fail("seed generation failed: " + e.getMessage());
        }

        out().print(pretty(documentJson) + "\n");
        return 0;
    }

    /**
     * Re-indent the canonical text. The interpreter's writer emits compact JSON because A12's own serializer
     * takes the pretty-printer as caller configuration ({@code DocumentSerializationConfig.prettyJsonPrinter}) —
     * presentation belongs here, not to the format. The round-trip is lossless for what that writer emits:
     * {@code MAPPER} parses numbers as {@code BigDecimal} with the scale intact and writes them back plain, so a
     * seeded {@code 250.00} stays {@code 250.00} rather than becoming {@code 250.0} or {@code 2.5E+2}, and
     * Jackson preserves the writer's canonical key order.
     */
    private static String pretty(String documentJson) {
        try {
            return MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(MAPPER.readTree(documentJson));
        } catch (JsonProcessingException impossible) {
            throw new IllegalStateException("the canonical Document JSON writer emitted unparseable JSON", impossible);
        }
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
