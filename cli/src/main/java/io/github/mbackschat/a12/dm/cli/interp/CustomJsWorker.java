package io.github.mbackschat.a12.dm.cli.interp;

import static java.nio.charset.StandardCharsets.UTF_8;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import io.github.mbackschat.a12.dm.interpreter.UnsupportedConstructException;
import io.github.mbackschat.a12.dm.interpreter.model.CustomCondition;
import io.github.mbackschat.a12.dm.interpreter.model.CustomConditionData;
import io.github.mbackschat.a12.dm.interpreter.model.CustomFieldType;
import io.github.mbackschat.a12.dm.interpreter.model.CustomFieldValidationContext;
import io.github.mbackschat.a12.dm.interpreter.model.CustomTypeRejection;
import io.github.mbackschat.a12.dm.interpreter.model.CustomConditionFieldPointer;

/**
 * The "escape to JS" bridge (FEATURE-GAPS G31): a native, kernel-free {@code dmtool} can't run a project's
 * imperative custom conditions (checksums, lookups — logic the DSL can't express), so it delegates to the
 * project's own JS, run by the system {@code node}, over a <b>single, lazily-spawned, persistent worker</b>
 * ({@link #WORKER_RESOURCE}, bundled + extracted to a temp file). One NDJSON request/response per check on a
 * pipe — startup is paid once, each check is a cheap round-trip (a process-per-check variant is ~50× slower).
 *
 * <p><b>Lifecycle (the worker never outlives dmtool).</b> The worker self-exits on stdin EOF — the OS closes the
 * pipe the instant dmtool dies, even on SIGKILL — backstopped by an idle timeout in the worker and a JVM
 * shutdown hook here ({@link Process#destroyForcibly()}) for the graceful path.
 *
 * <p><b>Graceful degrade.</b> If {@code node} is absent, the worker fails to start, or a check errors, the bridge
 * throws {@link UnsupportedConstructException} — so the interpreter reports that rule {@code unsupported}
 * (the G29 visible-ignore surfacing), never a crash. Node is an optional dependency.
 */
public final class CustomJsWorker implements AutoCloseable {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String WORKER_RESOURCE = "/io/github/mbackschat/a12/dm/cli/interp/custom-js-worker.mjs";
    private static final long IDLE_MS = 30_000;

    private static Path extractedScript;   // the bundled worker, extracted once per process

    private final Path userDir;
    private Process process;
    private BufferedWriter toWorker;
    private BufferedReader fromWorker;
    private boolean unavailable;
    private int nextId = 1;

    private CustomJsWorker(Path userDir) {
        this.userDir = userDir;
    }

    /**
     * Worker-backed {@link CustomCondition}s, one per {@code <Name>.js} in {@code dir} — all sharing ONE worker
     * process (a singleton per call). A condition with no {@code <Name>.js} stays unregistered (so a model
     * using it is reported {@code unsupported}, as before).
     *
     * @throws IOException if {@code dir} can't be listed
     */
    public static Map<String, CustomCondition> customConditions(Path dir) throws IOException {
        Map<String, CustomCondition> conditions = new LinkedHashMap<>();
        CustomJsWorker worker = new CustomJsWorker(dir);
        try (Stream<Path> files = Files.list(dir)) {
            files.filter(p -> p.getFileName().toString().endsWith(".js")).forEach(p -> {
                String file = p.getFileName().toString();
                String name = file.substring(0, file.length() - ".js".length());
                conditions.put(name, (data, relevant, formallyIncorrect, errorField) ->
                        worker.checkCondition(name, data, relevant, formallyIncorrect, errorField));
            });
        }
        if (!conditions.isEmpty()) {
            Runtime.getRuntime().addShutdownHook(new Thread(worker::close, "custom-js-worker-cleanup"));
        }
        return conditions;
    }

    /**
     * Worker-backed {@link CustomFieldType}s, one per {@code <Name>.js} in {@code dir} (each exporting a
     * {@code validate(value, context)} / {@code check(value, context)}), sharing ONE worker. A type with no {@code <Name>.js}
     * stays unregistered (its cells are reported {@code unsupported}, as before).
     *
     * @throws IOException if {@code dir} can't be listed
     */
    public static Map<String, CustomFieldType> customFieldTypes(Path dir) throws IOException {
        Map<String, CustomFieldType> types = new LinkedHashMap<>();
        CustomJsWorker worker = new CustomJsWorker(dir);
        try (Stream<Path> files = Files.list(dir)) {
            files.filter(p -> p.getFileName().toString().endsWith(".js")).forEach(p -> {
                String file = p.getFileName().toString();
                String name = file.substring(0, file.length() - ".js".length());
                types.put(name, (value, context) -> worker.checkFieldType(name, value, context));
            });
        }
        if (!types.isEmpty()) {
            Runtime.getRuntime().addShutdownHook(new Thread(worker::close, "custom-js-fieldtype-cleanup"));
        }
        return types;
    }

    /**
     * The interpreter's {@link CustomFieldType} contract: {@code null} when the value is accepted, else a
     * {@link CustomTypeRejection} carrying the validator's project-specific error key (IF150). A JS validator may
     * return a plain boolean (rejection → the fixed {@code customFieldTypeInvalid}) or an object
     * {@code {valid, errorKey, errorMessage}} (rejection → its project {@code errorKey}).
     */
    private synchronized CustomTypeRejection checkFieldType(
            String name, String value, CustomFieldValidationContext context) {
        ObjectNode req = MAPPER.createObjectNode();
        req.put("kind", "fieldType");
        req.put("name", name);
        req.put("value", value);
        ObjectNode contextNode = req.putObject("context");
        contextNode.put("locale", context.getLocale());
        if (context.getMinLength() == null) {
            contextNode.putNull("minLength");
        } else {
            contextNode.put("minLength", context.getMinLength());
        }
        if (context.getMaxLength() == null) {
            contextNode.putNull("maxLength");
        } else {
            contextNode.put("maxLength", context.getMaxLength());
        }
        contextNode.put("isDisplayValue", context.isDisplayValue());
        JsonNode resp = rpc(req, "custom field type", name);
        if (resp.path("result").asBoolean()) {
            return null;   // accepted
        }
        String code = resp.hasNonNull("errorKey") ? resp.get("errorKey").asText() : "customFieldTypeInvalid";
        String message = resp.hasNonNull("errorMessage") ? resp.get("errorMessage").asText() : null;
        return new CustomTypeRejection(code, message);
    }

    private synchronized boolean checkCondition(String name, CustomConditionData data, Set<CustomConditionFieldPointer> relevant,
            Set<CustomConditionFieldPointer> formallyIncorrect, CustomConditionFieldPointer errorField) {
        ObjectNode req = MAPPER.createObjectNode();
        req.put("kind", "condition");
        req.put("name", name);
        ArrayNode cells = req.putArray("data");
        for (CustomConditionFieldPointer fp : data.fields()) {
            ObjectNode cell = putPointer(cells.addObject(), fp);
            String value = data.getValue(fp);
            if (value != null) {
                cell.put("value", value);
            } else {
                cell.putNull("value");
            }
        }
        if (relevant == null) {
            req.putNull("relevant");
        } else {
            putPointers(req.putArray("relevant"), relevant);
        }
        putPointers(req.putArray("formallyIncorrect"), formallyIncorrect);
        putPointer(req.putObject("errorField"), errorField);
        return rpc(req, "custom condition", name).path("result").asBoolean();
    }

    /** One request/response round-trip; returns the response node (with {@code result} and, for a field-type
     *  rejection, an optional {@code errorKey}/{@code errorMessage}), or throws {@link UnsupportedConstructException}. */
    private JsonNode rpc(ObjectNode req, String kind, String name) {
        if (unavailable) {
            throw unsupported(kind, name, "the JS worker is unavailable");
        }
        try {
            ensureStarted();
            req.put("id", nextId++);
            toWorker.write(MAPPER.writeValueAsString(req));
            toWorker.write("\n");
            toWorker.flush();
            String line = fromWorker.readLine();
            if (line == null) {
                unavailable = true;
                throw unsupported(kind, name, "the JS worker exited unexpectedly");
            }
            JsonNode resp = MAPPER.readTree(line);
            if (resp.hasNonNull("error")) {
                throw unsupported(kind, name, resp.get("error").asText());
            }
            return resp;
        } catch (IOException e) {
            unavailable = true;
            throw unsupported(kind, name, e.getMessage());
        }
    }

    private void ensureStarted() throws IOException {
        if (process != null && process.isAlive()) {
            return;
        }
        ProcessBuilder pb = new ProcessBuilder("node", extractWorker().toString(), userDir.toString(),
                Long.toString(IDLE_MS));
        pb.redirectError(ProcessBuilder.Redirect.DISCARD);   // keep the worker's stderr off the RPC stdout channel
        process = pb.start();
        toWorker = new BufferedWriter(new OutputStreamWriter(process.getOutputStream(), UTF_8));
        fromWorker = new BufferedReader(new InputStreamReader(process.getInputStream(), UTF_8));
    }

    /** Extract the bundled worker script to a temp file (once per JVM); {@code node} runs a real file, not a stream. */
    private static synchronized Path extractWorker() throws IOException {
        if (extractedScript != null) {
            return extractedScript;
        }
        try (InputStream in = CustomJsWorker.class.getResourceAsStream(WORKER_RESOURCE)) {
            if (in == null) {
                throw new IOException("bundled worker script not found: " + WORKER_RESOURCE);
            }
            Path tmp = Files.createTempFile("dmtool-custom-js-worker", ".mjs");
            tmp.toFile().deleteOnExit();
            Files.write(tmp, in.readAllBytes());
            extractedScript = tmp;
            return tmp;
        }
    }

    private static ObjectNode putPointer(ObjectNode node, CustomConditionFieldPointer fp) {
        node.put("path", fp.getPath());
        ArrayNode indices = node.putArray("indices");
        for (Integer i : fp.getIndices()) {
            indices.add(i);
        }
        return node;
    }

    private static void putPointers(ArrayNode arr, Set<CustomConditionFieldPointer> pointers) {
        for (CustomConditionFieldPointer fp : pointers) {
            putPointer(arr.addObject(), fp);
        }
    }

    private static UnsupportedConstructException unsupported(String kind, String name, String why) {
        return new UnsupportedConstructException(kind + " \"" + name + "\" (JS): " + why);
    }

    @Override
    public synchronized void close() {
        if (process != null) {
            process.destroyForcibly();
            process = null;
        }
    }
}
