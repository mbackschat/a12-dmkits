package io.github.mbackschat.a12.dm.adapter.capture.json;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.cfg.JsonNodeFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;

/**
 * The capture boundary's JSON value tree — a closed, kernel-free model with a <b>canonical byte form</b>
 * (CONFORMANCE-CORPUS-SPEC §15): every generated packet/report artifact is written through
 * {@link #canonicalBytes}, so two clean captures of the same inputs are byte-identical. The pinned rules:
 *
 * <ul>
 *   <li>UTF-8, LF line ends, a final newline, 2-space indentation.</li>
 *   <li>Object members emit in <b>builder insertion order</b> — every artifact builder adds members in its
 *       schema's declared order, so the schema (not a library default) pins the byte layout.</li>
 *   <li>Ordered semantic lists stay ordered; canonical (sorted) ordering is the <em>builder's</em> duty
 *       where the schema declares a sorted member (e.g. the receipt inventory).</li>
 *   <li>Numbers are integral ({@link Int}) or exact decimals carried as plain strings ({@link Dec}) — a
 *       value never travels through binary floating point.</li>
 *   <li>String escaping: the JSON two-character escapes plus {@code \\u00XX} for remaining control
 *       characters; non-ASCII text is emitted as raw UTF-8 (never {@code \\u}-escaped).</li>
 * </ul>
 *
 * <p>Reading ({@link #parse}) is strict: malformed JSON, duplicate object keys, and non-exact numbers fail
 * loudly — the closed decoders on top of this reject unknown members.
 */
public sealed interface Json {

    record Obj(LinkedHashMap<String, Json> members) implements Json {
        public static Obj of() {
            return new Obj(new LinkedHashMap<>());
        }

        public Obj put(String key, Json value) {
            if (members.putIfAbsent(key, value) != null) {
                throw new IllegalArgumentException("duplicate member: " + key);
            }
            return this;
        }

        public Obj put(String key, String value) {
            return put(key, new Str(value));
        }

        public Obj put(String key, long value) {
            return put(key, new Int(value));
        }

        public Obj put(String key, boolean value) {
            return put(key, new Bool(value));
        }
    }

    record Arr(List<Json> items) implements Json {
        public static Arr of() {
            return new Arr(new ArrayList<>());
        }

        public Arr add(Json item) {
            items.add(item);
            return this;
        }
    }

    record Str(String value) implements Json {
    }

    record Int(long value) implements Json {
    }

    /** An exact decimal, carried as its plain (non-exponent) string form — never a binary double. */
    record Dec(String plainValue) implements Json {
    }

    record Bool(boolean value) implements Json {
    }

    record Null() implements Json {
        public static final Null NULL = new Null();
    }

    static Arr arrOfStrings(List<String> values) {
        Arr arr = Arr.of();
        values.forEach(v -> arr.add(new Str(v)));
        return arr;
    }

    // ---- canonical writing --------------------------------------------------------------------------

    /** The canonical UTF-8 bytes of {@code value} (the packet-identity byte form — see the class doc). */
    static byte[] canonicalBytes(Json value) {
        StringBuilder out = new StringBuilder();
        write(value, out, 0);
        out.append('\n');
        return out.toString().getBytes(StandardCharsets.UTF_8);
    }

    private static void write(Json value, StringBuilder out, int indent) {
        switch (value) {
            case Obj obj -> writeObj(obj, out, indent);
            case Arr arr -> writeArr(arr, out, indent);
            case Str s -> writeString(s.value(), out);
            case Int i -> out.append(i.value());
            case Dec d -> out.append(new BigDecimal(d.plainValue()).toPlainString());
            case Bool b -> out.append(b.value());
            case Null n -> out.append("null");
        }
    }

    private static void writeObj(Obj obj, StringBuilder out, int indent) {
        if (obj.members().isEmpty()) {
            out.append("{}");
            return;
        }
        out.append("{\n");
        Iterator<Map.Entry<String, Json>> it = obj.members().entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String, Json> e = it.next();
            pad(out, indent + 1);
            writeString(e.getKey(), out);
            out.append(": ");
            write(e.getValue(), out, indent + 1);
            out.append(it.hasNext() ? ",\n" : "\n");
        }
        pad(out, indent);
        out.append('}');
    }

    private static void writeArr(Arr arr, StringBuilder out, int indent) {
        if (arr.items().isEmpty()) {
            out.append("[]");
            return;
        }
        out.append("[\n");
        Iterator<Json> it = arr.items().iterator();
        while (it.hasNext()) {
            pad(out, indent + 1);
            write(it.next(), out, indent + 1);
            out.append(it.hasNext() ? ",\n" : "\n");
        }
        pad(out, indent);
        out.append(']');
    }

    private static void writeString(String s, StringBuilder out) {
        out.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\b' -> out.append("\\b");
                case '\f' -> out.append("\\f");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        out.append('"');
    }

    private static void pad(StringBuilder out, int indent) {
        out.append("  ".repeat(indent));
    }

    // ---- strict reading -----------------------------------------------------------------------------

    ObjectMapper STRICT = JsonMapper.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            .disable(JsonNodeFeature.STRIP_TRAILING_BIGDECIMAL_ZEROES)
            .build();

    /** Parse strict JSON (duplicate keys rejected, numbers exact) into the closed tree. */
    static Json parse(byte[] utf8) {
        try {
            return of(STRICT.readTree(new String(utf8, StandardCharsets.UTF_8)));
        } catch (IOException e) {
            throw new IllegalArgumentException("not valid JSON: " + e.getMessage(), e);
        }
    }

    private static Json of(JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            return Null.NULL;
        }
        if (node.isObject()) {
            Obj obj = Obj.of();
            node.properties().forEach(e -> obj.put(e.getKey(), of(e.getValue())));
            return obj;
        }
        if (node.isArray()) {
            Arr arr = Arr.of();
            node.forEach(item -> arr.add(of(item)));
            return arr;
        }
        if (node.isTextual()) {
            return new Str(node.asText());
        }
        if (node.isBoolean()) {
            return new Bool(node.asBoolean());
        }
        if (node.isNumber()) {
            BigDecimal dec = node.decimalValue();
            try {
                BigInteger integral = dec.toBigIntegerExact();
                return new Int(integral.longValueExact());
            } catch (ArithmeticException notIntegral) {
                return new Dec(dec.toPlainString());
            }
        }
        throw new IllegalArgumentException("unsupported JSON node type: " + node.getNodeType());
    }
}
