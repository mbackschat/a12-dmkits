package io.github.mbackschat.a12.dm.cli.interp;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.github.mbackschat.a12.dm.interpreter.model.ModelDef;
import io.github.mbackschat.a12.dm.interpreter.model.RelevantEntity;

/**
 * The CLI wire vocabulary for partial validation. A selection is a JSON array of model instance pointers:
 * {@code ["/Order/Name", "/Order/Lines[2]/Quantity", "/Order/Lines[*]/Price"]}. An omitted index or
 * {@code [*]} is the kernel's {@link RelevantEntity#ALL ALL_SEMANTIC}; a positive {@code [n]} selects one
 * repetition. The model path is validated before evaluation, so a typo never becomes an empty/clean result.
 */
public final class RelevantSelection {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Pattern SEGMENT = Pattern.compile("([^/\\[\\]]+)(?:\\[([^\\]]+)])?");

    private RelevantSelection() {
    }

    /** Parse and validate {@code json} against the prepared interpreter model. */
    public static Set<RelevantEntity> parse(String json, ModelDef model) {
        JsonNode root;
        try {
            root = MAPPER.readTree(json);
        } catch (Exception e) {
            throw new IllegalArgumentException("--relevant must be a JSON array of instance paths: "
                    + "[\"/Order/Name\", \"/Order/Lines[*]/Quantity\"]", e);
        }
        if (!root.isArray()) {
            throw new IllegalArgumentException("--relevant must be a JSON array of instance-path strings");
        }
        Set<RelevantEntity> out = new LinkedHashSet<>();
        for (JsonNode item : root) {
            if (!item.isTextual()) {
                throw new IllegalArgumentException("--relevant entries must be strings, got: " + item);
            }
            out.add(parsePointer(item.asText(), model));
        }
        return Set.copyOf(out);
    }

    private static RelevantEntity parsePointer(String pointer, ModelDef model) {
        if (pointer == null || !pointer.startsWith("/") || pointer.length() == 1 || pointer.endsWith("/")) {
            throw malformed(pointer);
        }
        String[] rawSegments = pointer.substring(1).split("/", -1);
        StringBuilder path = new StringBuilder();
        java.util.ArrayList<Integer> indices = new java.util.ArrayList<>(rawSegments.length);
        for (String raw : rawSegments) {
            Matcher match = SEGMENT.matcher(raw);
            if (!match.matches()) {
                throw malformed(pointer);
            }
            path.append('/').append(match.group(1));
            String index = match.group(2);
            if (index == null || "*".equals(index)) {
                indices.add(RelevantEntity.ALL);
            } else {
                int value;
                try {
                    value = Integer.parseInt(index);
                } catch (NumberFormatException e) {
                    throw new IllegalArgumentException("invalid repetition index '[" + index + "]' in --relevant "
                            + pointer + " — use [*] or a positive 1-based index", e);
                }
                if (value < 1) {
                    throw new IllegalArgumentException("invalid repetition index '[" + index + "]' in --relevant "
                            + pointer + " — use [*] or a positive 1-based index");
                }
                String prefix = path.toString();
                if (!model.getRepeatable().contains(prefix)) {
                    throw new IllegalArgumentException("repetition index '[" + value + "]' in --relevant " + pointer
                            + " is attached to non-repeatable path " + prefix);
                }
                indices.add(value);
            }
        }
        String entityPath = path.toString();
        if (!model.getEntityPaths().contains(entityPath)) {
            throw new IllegalArgumentException("no field or group at --relevant path " + entityPath
                    + io.github.mbackschat.a12.dm.core.Suggest.hint(entityPath,
                            model.getEntityPaths().stream().sorted().toList()));
        }
        return new RelevantEntity(entityPath, List.copyOf(indices));
    }

    private static IllegalArgumentException malformed(String pointer) {
        return new IllegalArgumentException("invalid --relevant instance path '" + pointer
                + "' — use /Group/Field, /Group/Rows[*]/Field, or /Group/Rows[2]/Field");
    }
}
