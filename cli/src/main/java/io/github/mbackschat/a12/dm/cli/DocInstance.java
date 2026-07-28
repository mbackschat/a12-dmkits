package io.github.mbackschat.a12.dm.cli;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.cfg.JsonNodeFeature;

/**
 * Reads a document <b>instance</b> file — the {@code --instance} argument shared by {@code model eval},
 * {@code model compute}, and {@code rule eval} — into the nested field tree the kernel runtime consumes. The instance is a
 * JSON object under a top-level {@code "fields"} key (or the root object itself): a repeatable group is an
 * array of row objects, a non-repeatable group a nested object, a field its text value. One source so the
 * runtime verbs can't drift on the document shape.
 */
public final class DocInstance {

    /**
     * Configured EXACTLY like A12's own document mapper ({@code ObjectMapperProvider
     * .createObjectMapperForDocSerializer()}), because this boundary must adopt kernel JSON I/O behaviour
     * exactly. Two of the three settings are load-bearing rather than cosmetic:
     * <ul>
     *   <li>{@code USE_BIG_DECIMAL_FOR_FLOATS} + {@code STRIP_TRAILING_BIGDECIMAL_ZEROES=false} — the Kernel's
     *       own comment is "we want to preserve trailing zeroes and the scale of the value". On a DEFAULT
     *       mapper a native {@code 250.00} becomes a {@code DoubleNode} and reaches the document as
     *       {@code "250.0"}, and a decimal beyond double precision is mangled to scientific notation. The
     *       scale is not cosmetic: the formal check and every comparison read it.</li>
     *   <li>{@code ALLOW_COMMENTS} — A12 accepts {@code //} and slash-star comments in a document, so refusing
     *       them here would reject input the Kernel reads.</li>
     * </ul>
     */
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .enable(JsonParser.Feature.ALLOW_COMMENTS)
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            .configure(JsonNodeFeature.STRIP_TRAILING_BIGDECIMAL_ZEROES, false);

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
