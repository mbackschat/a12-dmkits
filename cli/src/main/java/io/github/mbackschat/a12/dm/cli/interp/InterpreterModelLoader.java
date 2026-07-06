package io.github.mbackschat.a12.dm.cli.interp;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import io.github.mbackschat.a12.dm.interpreter.eval.Document;
import io.github.mbackschat.a12.dm.interpreter.load.ModelLoader;
import io.github.mbackschat.a12.dm.interpreter.model.ModelDef;

/**
 * The JVM bridge onto the kernel-free interpreter for {@code dmtool}: a DM-JSON model → {@link ModelDef} and a
 * {@code {"fields":{…}}} instance tree → {@link Document}, so the tool can drive the interpreter on a
 * REAL model (the engine behind the runtime verbs' default path; docs/INTERPRETER-ARCHITECTURE.md). Verified
 * by {@code InterpreterRealModelDiffTest} (the load→interpret path agrees with the live kernel).
 *
 * <p><b>Model loading is delegated</b> to the multiplatform {@link ModelLoader} in {@code :interpreter}
 * ({@code commonMain}) — the single, kernel-free, JVM+JS loader (WI-2 / proposal §6.2). This class keeps only
 * the JVM-side <b>instance</b> loader ({@link #document}), which builds a {@link Document} from a Java map of
 * an already-parsed instance tree. Parity of {@link #load} with the engine loader is guarded by
 * {@code ModelLoaderParityTest}.
 */
public final class InterpreterModelLoader {

    private InterpreterModelLoader() {
    }

    /** Load an expanded, self-contained DM-JSON model into a {@link ModelDef} (delegates to {@link ModelLoader}). */
    public static ModelDef load(String modelJson) {
        return ModelLoader.load(modelJson);
    }

    /** Build a {@link Document} from a nested fields map (the {@code DocInstance.readFields} output). */
    public static Document document(Map<String, Object> fields, ModelDef model) {
        Document doc = new Document(model.getKinds(), model.getDeclaredReps(), model.getUnsignedFields(),
                model.getRangeConfig(), model.getDateFormats(), model.getScales(), model.getDateTimeFormats(),
                model.getDatePrecisions(), model.getEnumValues(), model.getEnumCategories(), model.getTimeFormats());
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
