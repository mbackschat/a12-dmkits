package io.github.mbackschat.a12.dm.cli.interp;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.github.mbackschat.a12.dm.interpreter.InvalidModelTimeZoneException;
import io.github.mbackschat.a12.dm.interpreter.UnsupportedModelTimeZoneException;
import io.github.mbackschat.a12.dm.interpreter.eval.Document;
import io.github.mbackschat.a12.dm.interpreter.eval.DocumentKt;
import io.github.mbackschat.a12.dm.interpreter.load.ModelLoader;
import io.github.mbackschat.a12.dm.interpreter.model.ModelDef;
import io.github.mbackschat.a12.dm.modelkit.ModelTimeZoneContract;

/**
 * The JVM bridge onto the kernel-free interpreter for {@code dmtool}: a DM-JSON model → {@link ModelDef} and a
 * {@code {"fields":{…}}} instance tree → {@link Document}, so the tool can drive the interpreter on a
 * REAL model (the engine behind the runtime verbs' default path; docs/INTERPRETER-ARCHITECTURE.md). Verified
 * by {@code InterpreterRealModelDiffTest} (the load→interpret path agrees with the live kernel).
 *
 * <p><b>Model loading is delegated</b> to the multiplatform {@link ModelLoader} in {@code :interpreter}
 * ({@code commonMain}) — the single, kernel-free, JVM+JS loader (proposal §6.2). This class keeps only
 * the JVM-side <b>instance</b> loader ({@link #document}), which builds a {@link Document} from a Java map of
 * an already-parsed instance tree. Parity of {@link #load} with the engine loader is guarded by
 * {@code ModelLoaderParityTest}.
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
                    "invalid modelConfig.timeZone '" + (id == null ? "null" : id)
                            + "' — pass a valid Java time-zone id, or omit the slot to use UTC");
            case KERNEL_LEGAL_INTERPRETER_UNSUPPORTED -> throw new UnsupportedModelTimeZoneException(
                    "unsupported model timeZone '" + id + "' — this Java time-zone id is kernel-valid, but "
                            + "the interpreter-backed evaluator supports only UTC, GMT, and Europe/Berlin");
        }
    }

    /** Build a {@link Document} from a nested fields map (the {@code DocInstance.readFields} output).
     *  Configured via {@code ModelDef.newDocument()} — the one place that threads EVERY config map (hand-listing
     *  the positional constructor is how this method silently dropped {@code baseYear} and
     *  {@code fieldConstraints}, and would have dropped {@code timeZone} too). */
    public static Document document(Map<String, Object> fields, ModelDef model) {
        Document doc = DocumentKt.newDocument(model);
        walkInstance(doc, fields, new ArrayList<>(), new ArrayList<>());
        return doc;
    }

    @SuppressWarnings("unchecked")
    private static void walkInstance(Document doc, Object node, List<String> segs, List<Integer> reps) {
        if (!(node instanceof Map<?, ?> map)) {
            return;
        }
        for (Map.Entry<?, ?> e : map.entrySet()) {
            List<String> childSegs = append(segs, (String) e.getKey());
            String childPath = "/" + String.join("/", childSegs);
            Object child = e.getValue();
            if (child instanceof List<?> rows) {                       // a repeatable group: one row per element
                for (int i = 0; i < rows.size(); i++) {
                    doc.group(childPath, toIntArray(append(reps, i + 1)));   // instantiate the row (even if empty)
                    walkInstance(doc, rows.get(i), childSegs, append(reps, i + 1));
                }
            } else if (child instanceof Map) {                         // a non-repeatable nested group
                doc.group(childPath, toIntArray(append(reps, 1)));           // instantiate it (even if {} — empty-operand compute)
                walkInstance(doc, child, childSegs, append(reps, 1));
            } else {                                                   // a field value
                String path = "/" + String.join("/", childSegs);
                int[] r = toIntArray(append(reps, 1));                 // + the field's own index
                if (child == null || child.toString().isEmpty()) {
                    doc.emptyField(path, r);
                } else {
                    doc.field(path, child.toString(), r);
                }
            }
        }
    }

    private static List<String> append(List<String> base, String s) {
        List<String> out = new ArrayList<>(base);
        out.add(s);
        return out;
    }

    private static List<Integer> append(List<Integer> base, int n) {
        List<Integer> out = new ArrayList<>(base);
        out.add(n);
        return out;
    }

    private static int[] toIntArray(List<Integer> list) {
        int[] a = new int[list.size()];
        for (int i = 0; i < a.length; i++) {
            a[i] = list.get(i);
        }
        return a;
    }
}
