package io.github.mbackschat.a12.dm.cli.interp;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.github.mbackschat.a12.dm.interpreter.model.PredefinedType;
import io.github.mbackschat.a12.dm.interpreter.model.PredefinedTypeRegistry;

/**
 * Reads a {@code --predefined-types} file — a consumer's declarative custom-field-type registry,
 * {@code { "<TypeName>": { "minLength"?, "maxLength"?, "pattern"? } }} — into the interpreter's
 * {@link PredefinedType} map, MERGED over the built-in defaults ({@link PredefinedTypeRegistry#DEFAULT},
 * e.g. {@code EMail}); a consumer entry overrides a built-in of the same name. This is the CLI surface for the
 * kernel's {@code IPredefinedType} client SPI: with it, a custom-typed field whose type is a length/pattern
 * <em>format</em> constraint is actually validated (G30) instead of reported {@code unsupported}. Genuinely
 * imperative custom types (checksums — IBAN mod-97, ISIN/Luhn) are out of this declarative model and stay
 * unsupported (FEATURE-GAPS G31). Same shape as a {@code field add} {@code string} config (length/pattern).
 */
public final class PredefinedTypesFile {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private PredefinedTypesFile() {
    }

    /**
     * Parse the registry file at {@code path} into {@code DEFAULT + project types}.
     *
     * @throws IOException if the file can't be read or isn't valid JSON
     * @throws IllegalArgumentException on a malformed entry (not an object, an unknown key, or a bad pattern)
     */
    public static Map<String, PredefinedType> read(String path) throws IOException {
        JsonNode root = MAPPER.readTree(Files.readString(Path.of(path)));
        if (!root.isObject()) {
            throw new IllegalArgumentException(
                    "--predefined-types must be a JSON object { \"<TypeName>\": { minLength?, maxLength?, pattern? } }");
        }
        Map<String, PredefinedType> registry = new LinkedHashMap<>(PredefinedTypeRegistry.INSTANCE.getDEFAULT());
        root.fields().forEachRemaining(e -> registry.put(e.getKey(), parseType(e.getKey(), e.getValue())));
        return registry;
    }

    private static PredefinedType parseType(String name, JsonNode spec) {
        if (!spec.isObject()) {
            throw new IllegalArgumentException(
                    "predefined type \"" + name + "\" must be an object with minLength / maxLength / pattern");
        }
        spec.fieldNames().forEachRemaining(key -> {
            if (!key.equals("minLength") && !key.equals("maxLength") && !key.equals("pattern")) {
                throw new IllegalArgumentException("predefined type \"" + name + "\": unknown key \"" + key
                        + "\" (allowed: minLength, maxLength, pattern)");
            }
        });
        Integer minLength = spec.has("minLength") ? spec.get("minLength").asInt() : null;
        Integer maxLength = spec.has("maxLength") ? spec.get("maxLength").asInt() : null;
        String pattern = spec.has("pattern") ? spec.get("pattern").asText() : null;
        try {
            return new PredefinedType(minLength, maxLength, pattern);   // compiles the regex eagerly
        } catch (RuntimeException badPattern) {
            throw new IllegalArgumentException(
                    "predefined type \"" + name + "\": invalid pattern — " + badPattern.getMessage());
        }
    }
}
