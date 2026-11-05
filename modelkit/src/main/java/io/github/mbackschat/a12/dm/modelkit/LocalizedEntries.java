package io.github.mbackschat.a12.dm.modelkit;

import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import io.github.mbackschat.a12.dm.core.A12Locales;
import io.github.mbackschat.a12.dm.core.DmKeys;

/**
 * The one writer of a model's {@code [{locale, text}]} entries: each locale is written as A12 writes it,
 * {@link A12Locales#key}, and an entry is found by that key, so an edit naming {@code en_US} meets the
 * {@code en-US} a model already stores instead of adding a second entry for one locale.
 */
final class LocalizedEntries {

    private LocalizedEntries() {
    }

    static void append(ArrayNode array, String locale, String text) {
        array.addObject().put(DmKeys.LOCALE, A12Locales.key(locale)).put(DmKeys.TEXT, text);
    }

    static void appendAll(ArrayNode array, Map<String, String> textByLocale) {
        textByLocale.forEach((locale, text) -> append(array, locale, text));
    }

    /**
     * The model's declared locales first, in declared order, then the rest in the order given, which keeps a fully
     * answered element's bytes identical whichever way its texts were collected.
     */
    static void appendDeclaredFirst(ArrayNode array, List<String> declared, Map<String, String> textByLocale) {
        declared.forEach(code -> textByLocale.forEach((locale, text) -> {
            if (A12Locales.same(locale, code)) {
                append(array, locale, text);
            }
        }));
        textByLocale.forEach((locale, text) -> {
            if (declared.stream().noneMatch(code -> A12Locales.same(locale, code))) {
                append(array, locale, text);
            }
        });
    }

    /** Replace the text of {@code element[key]}'s entry for {@code locale}, or append one. */
    static void upsert(ObjectNode element, String key, String locale, String text) {
        ArrayNode array = element.has(key) ? (ArrayNode) element.get(key) : element.putArray(key);
        for (JsonNode entry : array) {
            if (A12Locales.same(locale, entry.path(DmKeys.LOCALE).asText())) {
                ((ObjectNode) entry).put(DmKeys.LOCALE, A12Locales.key(locale)).put(DmKeys.TEXT, text);
                return;
            }
        }
        append(array, locale, text);
    }

    /** The key of an entry's locale, for matching a removal by locale. */
    static String keyOf(JsonNode entry) {
        return A12Locales.key(entry.path(DmKeys.LOCALE).asText());
    }
}
