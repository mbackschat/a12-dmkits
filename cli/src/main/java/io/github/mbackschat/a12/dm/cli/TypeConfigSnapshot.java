package io.github.mbackschat.a12.dm.cli;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.TreeSet;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.github.mbackschat.a12.dm.modelkit.ModelDocument;
import io.github.mbackschat.a12.dm.rulekit.validate.Diagnostic;
import io.github.mbackschat.a12.dm.rulekit.validate.RkCode;

/**
 * The per-kind config a type-carrying {@code field modify} or {@code typedef modify} replaces, captured before
 * and after the edit so the keys it removed can be reported. The modify spec is the add spec, and like
 * {@code computation modify} it replaces rather than merges. Without a report, that replacement removes
 * constraints under an {@code applied} result and nothing else, so every route that runs these edits,
 * standalone and {@code apply}, reports the removal through this one comparison.
 *
 * <p>Only a same-kind edit is compared: a kind change discards the old kind's block by definition and has its
 * own advisory. A side typed by reference ({@code typedef}) has no config of its own, so it is not compared.
 *
 * @param kind the clean value-kind name, empty when the element does not exist
 * @param config the read-back config block ({@code field read}/{@code typedef read} shape), empty when bare
 */
public record TypeConfigSnapshot(Optional<String> kind, Optional<String> config) {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** The field's kind and config as {@code field read} reports them. */
    public static TypeConfigSnapshot ofField(ModelDocument doc, String fieldPath) {
        return new TypeConfigSnapshot(doc.fieldKind(fieldPath), doc.fieldConfigJson(fieldPath));
    }

    /** The type definition's kind and config as {@code typedef read} reports them. */
    public static TypeConfigSnapshot ofTypedef(ModelDocument doc, String id) {
        return new TypeConfigSnapshot(doc.typeDefinitionKind(id), doc.typeDefinitionConfigJson(id));
    }

    /**
     * One {@link RkCode#MODIFY_REMOVES_CONFIG} warning per config key this snapshot carried whose value
     * {@code after} no longer holds (removed, or reset to its default as a NUMBER's scale is), unless
     * {@code spec} names the key and so changed it on purpose. Keys use the read-back spelling, located at
     * {@code subject} with {@code block.key} as the arg; {@code spec} is the modify's spec object, or a missing
     * node for a bare {@code --kind}.
     */
    public List<Diagnostic> removedKeys(String subject, TypeConfigSnapshot after, JsonNode spec) {
        if (kind.isEmpty() || !kind.equals(after.kind)) {
            return List.of();
        }
        JsonNode old = tree(config);
        JsonNode now = tree(after.config);
        if (old.has("typedef") || now.has("typedef")) {
            return List.of();
        }
        List<Diagnostic> warnings = new ArrayList<>();
        new TreeSet<>(fieldNames(old)).forEach(block -> new TreeSet<>(fieldNames(old.path(block))).forEach(key -> {
            JsonNode was = old.path(block).path(key);
            JsonNode is = now.path(block).path(key);
            if (!was.equals(is) && !carries(spec.path(block), key)) {
                String spelled = block + "." + key;
                warnings.add(Diagnostic.precheckWarning(
                        Optional.of(RkCode.MODIFY_REMOVES_CONFIG.code()),
                        "the modify replaced " + subject + "'s whole " + kind.get() + " config, and the spec left out "
                                + spelled + ": it was " + was + " and is now "
                                + (is.isMissingNode() ? "removed" : is.toString()),
                        Diagnostic.Where.arg(subject, spelled),
                        Optional.of(RkCode.MODIFY_REMOVES_CONFIG.fix()),
                        Optional.empty()));
            }
        }));
        return warnings;
    }

    /**
     * Whether the spec block sets the read-back key. The one spelling that differs is the pattern text:
     * {@code patternMessage} and {@code patternMessages} both write the one localized message that
     * {@code TypeConfigReader} reads back as {@code patternMessages}.
     */
    private static boolean carries(JsonNode specBlock, String readBackKey) {
        return specBlock.has(readBackKey)
                || "patternMessages".equals(readBackKey) && specBlock.has("patternMessage");
    }

    private static List<String> fieldNames(JsonNode node) {
        List<String> names = new ArrayList<>();
        node.fieldNames().forEachRemaining(names::add);
        return names;
    }

    private static JsonNode tree(Optional<String> config) {
        try {
            return config.isEmpty() ? MAPPER.createObjectNode() : MAPPER.readTree(config.get());
        } catch (JsonProcessingException e) {
            // the text is ModelDocument's own serialization of an ObjectNode, so it always parses
            throw new IllegalStateException("unparseable config read-back: " + config.get(), e);
        }
    }
}
