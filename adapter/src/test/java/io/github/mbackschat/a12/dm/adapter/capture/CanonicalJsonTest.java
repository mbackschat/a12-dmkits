package io.github.mbackschat.a12.dm.adapter.capture;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.adapter.capture.json.Json;

/**
 * The canonical JSON byte form every packet/report artifact is written in (CONFORMANCE-CORPUS-SPEC §15):
 * UTF-8, LF, final newline, 2-space indent, builder-order members, exact numbers — plus the strict reader
 * (duplicate keys rejected, decimals never through binary floating point).
 */
class CanonicalJsonTest {

    @Test
    void writesPinnedCanonicalBytes() {
        Json value = Json.Obj.of()
                .put("schema", "example-v1")
                .put("count", 2)
                .put("flag", true)
                .put("items", Json.Arr.of().add(new Json.Str("a")).add(new Json.Str("b")))
                .put("empty", Json.Obj.of())
                .put("none", Json.Null.NULL);
        String expected = """
                {
                  "schema": "example-v1",
                  "count": 2,
                  "flag": true,
                  "items": [
                    "a",
                    "b"
                  ],
                  "empty": {},
                  "none": null
                }
                """;
        assertEquals(expected, new String(Json.canonicalBytes(value), StandardCharsets.UTF_8));
    }

    @Test
    void escapesControlCharactersButEmitsRawUtf8() {
        String textWithControlChar = "Tab\there " + (char) 1 + " \"quoted\" — Grüße";
        Json value = Json.Obj.of().put("text", textWithControlChar);
        String written = new String(Json.canonicalBytes(value), StandardCharsets.UTF_8);
        assertTrue(written.contains("\\t"), "tab escaped");
        assertTrue(written.contains("\\\"quoted\\\""), "quotes escaped");
        assertTrue(written.contains("Grüße"), "non-ASCII stays raw UTF-8");
        assertTrue(written.contains("\\u0001"), "control chars escaped as backslash-u00XX");
    }

    @Test
    void roundTripsThroughTheStrictReader() {
        Json value = Json.Obj.of()
                .put("s", "x")
                .put("n", 42)
                .put("b", false)
                .put("arr", Json.Arr.of().add(new Json.Int(1)).add(Json.Null.NULL));
        byte[] bytes = Json.canonicalBytes(value);
        assertArrayEquals(bytes, Json.canonicalBytes(Json.parse(bytes)), "write → parse → write is identity");
    }

    @Test
    void decimalsAreExactAndNeverBinaryFloat() {
        Json parsed = Json.parse("{\"d\": 10.250}".getBytes(StandardCharsets.UTF_8));
        Json.Dec dec = assertInstanceOf(Json.Dec.class, ((Json.Obj) parsed).members().get("d"));
        assertEquals("10.250", dec.plainValue(), "trailing zero preserved — the exact authored literal");
    }

    @Test
    void duplicateKeysAreRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> Json.parse("{\"a\": 1, \"a\": 2}".getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void malformedJsonIsRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> Json.parse("{\"a\": ".getBytes(StandardCharsets.UTF_8)));
    }
}
