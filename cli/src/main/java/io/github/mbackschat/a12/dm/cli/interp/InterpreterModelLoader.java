package io.github.mbackschat.a12.dm.cli.interp;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.github.mbackschat.a12.dm.interpreter.InvalidModelTimeZoneException;
import io.github.mbackschat.a12.dm.interpreter.UnsupportedModelTimeZoneException;
import io.github.mbackschat.a12.dm.interpreter.eval.Document;
import io.github.mbackschat.a12.dm.interpreter.eval.DocumentJsonKt;
import io.github.mbackschat.a12.dm.interpreter.load.ModelLoader;
import io.github.mbackschat.a12.dm.interpreter.model.ModelDef;
import io.github.mbackschat.a12.dm.modelkit.ModelTimeZoneContract;

/**
 * The JVM bridge onto the kernel-free interpreter for {@code dmtool}: a DM-JSON model → {@link ModelDef} and
 * A12's canonical Document JSON → {@link Document}, so the tool can drive the interpreter on a REAL model (the
 * engine behind the runtime verbs' default path; docs/INTERPRETER-ARCHITECTURE.md). Verified by
 * {@code InterpreterRealModelDiffTest} (the load→interpret path agrees with the live kernel).
 *
 * <p><b>Both loaders are delegated</b> to {@code :interpreter} ({@code commonMain}): the model to the
 * multiplatform {@link ModelLoader} (the single kernel-free JVM+JS loader, proposal §6.2), and the document to
 * the canonical {@code ModelDef.readDocumentJson} codec. Neither shape is re-implemented here — this bridge
 * once carried its own instance walker, which had the A12 tree shape but not A12's value-normalizing ingress
 * BEHAVIOUR, so it reported formal errors on documents A12 itself accepts silently (CLAUDE.md "…and adopt
 * A12's JSON I/O BEHAVIOUR exactly, not just its shape"). Parity of {@link #load} with the engine loader is
 * guarded by {@code ModelLoaderParityTest}.
 */
public final class InterpreterModelLoader {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private InterpreterModelLoader() {
    }

    /** Load an expanded, self-contained DM-JSON model into a {@link ModelDef} (delegates to {@link ModelLoader}). */
    public static ModelDef load(String modelJson) {
        requireSupportedTimeZone(modelJson);
        return ModelLoader.load(modelJson);
    }

    /**
     * JVM preparation owns the Java {@code TimeZone} id-domain decision that common JVM/JS code cannot
     * honestly make. This is the single four-domain boundary used by every interpreter-backed CLI verb.
     */
    private static void requireSupportedTimeZone(String modelJson) {
        JsonNode root;
        try {
            root = MAPPER.readTree(modelJson);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("not valid DM-JSON", e);
        }
        JsonNode config = root.path("content").path("modelConfig");
        boolean present = config.has("timeZone");
        JsonNode node = present ? config.get("timeZone") : null;
        String id = node != null && node.isTextual() ? node.asText() : null;
        switch (ModelTimeZoneContract.classify(present, id)) {
            case ABSENT_DEFAULT, INTERPRETER_SUPPORTED -> {
                return;
            }
            case KERNEL_INVALID -> throw new InvalidModelTimeZoneException(
                    id,
                    "invalid modelConfig.timeZone '" + (id == null ? "null" : id)
                            + "' — pass a valid Java time-zone id, or omit the slot to use UTC");
            case KERNEL_LEGAL_INTERPRETER_UNSUPPORTED -> throw new UnsupportedModelTimeZoneException(
                    id,
                    "unsupported model timeZone '" + id + "' — this Java time-zone id is kernel-valid, but "
                            + "the interpreter-backed evaluator supports only UTC, GMT, and Europe/Berlin");
        }
    }

    /**
     * Build a {@link Document} from A12's canonical Document JSON — delegated to the interpreter's own codec, so
     * the tree shape, the value normalization every typed kind gets at ingestion, and the decode ceilings are
     * the ones {@code adapter … A12JsonIngressDiffTest} locks against the real Kernel.
     *
     * @throws io.github.mbackschat.a12.dm.interpreter.DocumentInputError with every model-level defect
     *         aggregated (the caller renders it — {@code BaseCommand.failInvalidInstance})
     */
    public static Document document(String documentJson, ModelDef model) {
        return DocumentJsonKt.readDocumentJson(model, documentJson);
    }
}
