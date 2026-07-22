package io.github.mbackschat.a12.dm.adapter.review;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.github.mbackschat.a12.dm.adapter.review.ModelDiff.BooleanValue;
import io.github.mbackschat.a12.dm.adapter.review.ModelDiff.DecimalValue;
import io.github.mbackschat.a12.dm.adapter.review.ModelDiff.DeltaValue;
import io.github.mbackschat.a12.dm.adapter.review.ModelDiff.IntegerValue;
import io.github.mbackschat.a12.dm.adapter.review.ModelDiff.StringListValue;
import io.github.mbackschat.a12.dm.adapter.review.ModelDiff.TextValue;

/** Converts deterministic delta renderings to the additive typed view. */
final class DeltaValueParser {

    private static final ObjectMapper JSON = new ObjectMapper();

    private DeltaValueParser() {}

    static Optional<DeltaValue> parse(String aspect, String rendered) {
        if (rendered == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(switch (Kind.of(aspect)) {
                case TEXT -> new TextValue(rendered);
                case BOOLEAN -> new BooleanValue(booleanValue(rendered));
                case INTEGER -> new IntegerValue(Integer.parseInt(rendered));
                case DECIMAL -> new DecimalValue(new BigDecimal(rendered));
                case STRING_LIST -> new StringListValue(stringList(aspect, rendered));
            });
        } catch (IllegalArgumentException exception) {
            return Optional.of(new TextValue(rendered));
        }
    }

    private static boolean booleanValue(String rendered) {
        return switch (rendered) {
            case "true" -> true;
            case "false" -> false;
            default -> throw new IllegalArgumentException("not a boolean: " + rendered);
        };
    }

    private static List<String> stringList(String aspect, String rendered) {
        if (aspect.equals("errorCodesToSuppress")) {
            return Arrays.stream(rendered.split(","))
                    .map(String::trim).filter(value -> !value.isEmpty()).toList();
        }
        try {
            return List.of(JSON.readValue(rendered, String[].class));
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("not a string list: " + rendered, exception);
        }
    }

    private enum Kind {
        TEXT,
        BOOLEAN,
        INTEGER,
        DECIMAL,
        STRING_LIST;

        static Kind of(String aspect) {
            return switch (aspect) {
                case "global", "transient", "alphabeticalSorting", "lineBreaks", "noValueValidation",
                        "zeroNotAllowed", "leadingZerosAllowed", "mount.excludeRules",
                        "mount.excludeComputations", "fieldRefByShortNameAllowed" -> BOOLEAN;
                case "repeatability", "position", "scale", "minFractionalDigits", "minLength", "maxLength",
                        "baseYear" -> INTEGER;
                case "min", "max" -> DECIMAL;
                case "locales", "supportedCharacters", "roles", "errorCodesToSuppress" -> STRING_LIST;
                default -> aspect.startsWith("enum.position.") ? INTEGER : TEXT;
            };
        }
    }
}
