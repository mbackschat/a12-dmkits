package io.github.mbackschat.a12.dm.cli;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Reads a document <b>instance</b> file — the {@code --instance} argument shared by {@code model eval},
 * {@code model compute}, and {@code rule test} — into the nested field tree the kernel runtime consumes. The instance is a
 * JSON object under a top-level {@code "fields"} key (or the root object itself): a repeatable group is an
 * array of row objects, a non-repeatable group a nested object, a field its text value. One source so the
 * runtime verbs can't drift on the document shape.
 */
public final class DocInstance {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private DocInstance() {
    }

    /**
     * Parse the instance at {@code docPath} into its nested field tree.
     *
     * @throws IOException if the file can't be read or isn't valid JSON
     * @throws IllegalArgumentException if there is no top-level {@code "fields"} object (nor a root object)
     */
    public static Map<String, Object> readFields(String docPath) throws IOException {
        JsonNode root = MAPPER.readTree(Files.readString(Path.of(docPath)));
        JsonNode fieldsNode = root.has("fields") ? root.get("fields") : root;
        if (!fieldsNode.isObject()) {
            throw new IllegalArgumentException("the document needs a top-level \"fields\" object (a nested field tree)");
        }
        return MAPPER.convertValue(fieldsNode, new TypeReference<Map<String, Object>>() { });
    }
}
