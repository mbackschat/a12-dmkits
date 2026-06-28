package io.github.mbackschat.a12.dm.cli.interp;

import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import io.github.mbackschat.a12.dm.interpreter.gen.PlacementSink;

/**
 * A {@link PlacementSink} that materializes the generator's placements into the {@code {"fields": {…}}}
 * document-instance JSON the runtime verbs consume ({@code model eval}/{@code compute}/{@code rule eval} —
 * see {@code DocInstance} and {@code document-instance.schema.json}): a non-repeatable group is a nested
 * object keyed by child name, a repeatable group an ARRAY of row objects, a field its string value. Each
 * placement is addressed by one 1-based repetition index per path part, so the array index for a repeatable
 * segment is {@code reps[i] - 1}. It drives {@code dmtool model seed} → a valid sample instance.
 */
public final class JsonInstanceSink implements PlacementSink {

    private final ObjectMapper mapper;
    private final ObjectNode fields;
    private final Set<String> repeatableGroups;

    public JsonInstanceSink(ObjectMapper mapper, Set<String> repeatableGroups) {
        this.mapper = mapper;
        this.repeatableGroups = repeatableGroups;
        this.fields = mapper.createObjectNode();
    }

    /** The complete document instance: {@code { "fields": { … } }}. */
    public ObjectNode result() {
        ObjectNode root = mapper.createObjectNode();
        root.set("fields", fields);
        return root;
    }

    @Override
    public void group(String path, int... reps) {
        descend(segments(path), reps, segments(path).length);   // materialize the row/object container itself
    }

    @Override
    public void field(String path, String value, int... reps) {
        String[] segs = segments(path);
        descend(segs, reps, segs.length - 1).put(segs[segs.length - 1], value);
    }

    @Override
    public void emptyField(String path, int... reps) {
        // present-but-unfilled — omit the key (an absent key = no instance for that field, per the schema).
    }

    /** Navigate (creating as needed) the first {@code count} segments of {@code segs}, returning the container
     *  object at that depth — an array row for a repeatable group, a nested object otherwise. */
    private ObjectNode descend(String[] segs, int[] reps, int count) {
        ObjectNode cur = fields;
        StringBuilder groupPath = new StringBuilder();
        for (int i = 0; i < count; i++) {
            groupPath.append('/').append(segs[i]);
            if (repeatableGroups.contains(groupPath.toString())) {
                cur = row(arrayChild(cur, segs[i]), reps[i]);
            } else {
                cur = objectChild(cur, segs[i]);
            }
        }
        return cur;
    }

    private ArrayNode arrayChild(ObjectNode parent, String name) {
        JsonNode existing = parent.get(name);
        if (existing instanceof ArrayNode array) {
            return array;
        }
        ArrayNode array = mapper.createArrayNode();
        parent.set(name, array);
        return array;
    }

    private ObjectNode objectChild(ObjectNode parent, String name) {
        JsonNode existing = parent.get(name);
        if (existing instanceof ObjectNode object) {
            return object;
        }
        ObjectNode object = mapper.createObjectNode();
        parent.set(name, object);
        return object;
    }

    /** The row object at 1-based [rep] in [array], growing the array with empty rows if it is short. */
    private ObjectNode row(ArrayNode array, int rep) {
        int index = rep - 1;
        while (array.size() <= index) {
            array.addObject();
        }
        return (ObjectNode) array.get(index);
    }

    private static String[] segments(String path) {
        return path.substring(1).split("/");
    }
}
