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
 * {@code ["/Order/Name", "/Order/Lines[2]/Quantity", "/Order/Lines[*]/Price"]}. {@code [*]} and {@code [0]} are
 * the kernel's {@link RelevantEntity#ALL ALL_SEMANTIC}; a positive {@code [n]} selects one repetition. The model
 * path is validated before evaluation, so a typo never becomes an empty/clean result.
 *
 * <h2>An omitted index on a repeatable segment is REFUSED (breaking, 2026-08-01)</h2>
 *
 * <p>This type used to read an omitted index as <b>all repetitions</b>. A12 reads an omitted index as
 * <b>repetition 1</b> — on the exact {@code DocumentPointer} and on the multipointer alike — so the same string
 * meant opposite things in the two systems, and the difference is not cosmetic: it decides <em>which rows get
 * validated</em>. Silently adopting A12's meaning would have narrowed every existing caller's scope from
 * all-rows to row-1, with a clean pass as the symptom; keeping ours would have meant knowingly publishing a
 * competing meaning for A12's own spelling, which the interop rule forbids.
 *
 * <p>So neither: an omitted index on a repeatable segment is now a <b>corrective refusal</b> that names the
 * segment and both explicit forms. An existing selection that depended on omission fails loudly and is migrated
 * deliberately — the one outcome where nobody's validation scope changes without them deciding it.
 *
 * <p>Only <em>repeatable</em> segments are affected. A non-repeatable segment has exactly one instance, so an
 * omitted index there is unambiguous under both readings and stays legal — refusing it would be noise with no
 * question behind it.
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
                    + "[\"/Order/Name\", \"/Order/Lines[0]/Quantity\"]", e);
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
            String prefix = path.toString();
            if (index == null) {
                // The break. On a repeatable segment the omission is ambiguous ACROSS SYSTEMS — we used to read
                // it as all rows, A12 reads it as row 1 — and guessing either way silently changes what got
                // validated. On a non-repeatable segment there is only one instance, so nothing is ambiguous.
                if (model.getRepeatable().contains(prefix)) {
                    throw omittedIndexOnRepeatable(pointer, prefix);
                }
                indices.add(RelevantEntity.ALL);
                continue;
            }
            if ("*".equals(index) || "0".equals(index)) {
                indices.add(RelevantEntity.ALL);
                continue;
            }
            int value;
            try {
                value = Integer.parseInt(index);
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("invalid repetition index '[" + index + "]' in --relevant "
                        + pointer + " — use [0] or [*] for all repetitions, or a positive 1-based index", e);
            }
            if (value < 1) {
                throw new IllegalArgumentException("invalid repetition index '[" + index + "]' in --relevant "
                        + pointer + " — use [0] or [*] for all repetitions, or a positive 1-based index");
            }
            if (!model.getRepeatable().contains(prefix)) {
                throw new IllegalArgumentException("repetition index '[" + value + "]' in --relevant " + pointer
                        + " is attached to non-repeatable path " + prefix);
            }
            indices.add(value);
        }
        String entityPath = path.toString();
        if (!model.getEntityPaths().contains(entityPath)) {
            throw new IllegalArgumentException("no field or group at --relevant path " + entityPath
                    + io.github.mbackschat.a12.dm.core.Suggest.hint(entityPath,
                            model.getEntityPaths().stream().sorted().toList()));
        }
        return new RelevantEntity(entityPath, List.copyOf(indices));
    }

    /**
     * The corrective the break exists for: it names the repeatable segment and <b>both</b> explicit forms, so the
     * migrating caller states the intent rather than inheriting whichever reading the tool happened to pick.
     */
    private static IllegalArgumentException omittedIndexOnRepeatable(String pointer, String repeatablePath) {
        return new IllegalArgumentException("--relevant " + pointer + " omits the repetition index on repeatable "
                + repeatablePath + " — say which rows you mean: '" + repeatablePath + "[0]' (or '[*]') for ALL "
                + "repetitions, '" + repeatablePath + "[1]' for the first. An omitted index used to mean ALL here "
                + "and means repetition 1 in A12, so it is no longer accepted rather than silently reinterpreted "
                + "— which would have changed which rows are validated with a clean pass as the only symptom.");
    }

    private static IllegalArgumentException malformed(String pointer) {
        return new IllegalArgumentException("invalid --relevant instance path '" + pointer
                + "' — use /Group/Field, /Group/Rows[*]/Field, or /Group/Rows[2]/Field");
    }
}
