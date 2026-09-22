package io.github.mbackschat.a12.dm.cli;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.github.mbackschat.a12.dm.interpreter.A12PointerFormat;

/**
 * Every pointer dmtool <b>publishes as canonical A12</b> really is one, over live runtime output
 * (CLI-SPEC §5 "Runtime output addresses cells by canonical A12 pointer").
 *
 * <p><b>The key set is derived from the schemas, not authored here.</b> Each published pointer carries
 * {@code "format": "a12-pointer"}, so this walks the schemas, collects the marked properties, and holds the live
 * output to them. A key whose contract promises canonical form while its value is the interpreter's house form
 * ({@code /Order[1]/Items[2]/Count}) fails here — a divergence invisible to schema conformance, which only sees
 * {@code "type": "string"}.
 *
 * <p>The mark is a keyword rather than the prose it replaced, and that was earned: a first version matched
 * descriptions containing "canonical A12" and got the set wrong in <b>both</b> directions on its first run. It
 * pulled in {@code unsupported.subject}, whose description mentions the phrase while <em>explaining</em> the two
 * address kinds and whose value is the enum {@code element|cell}; and it missed {@code fillToFix}, which publishes
 * canonical pointers but whose description had never said so. Prose written for a human reader is not a
 * machine-readable claim, and a guard built on it silently checks the wrong set.
 *
 * <p><b>It builds its own documents rather than reusing the demo corpus</b>, and that too was earned. The first
 * version drove the demos, and the reached-every-key assertion below fired immediately: {@code model eval}'s demo
 * document made <em>no</em> rule fire and {@code model compute}'s model declares no computations, so the guard
 * walked two empty payloads and would otherwise have been green while checking nothing. The scenarios here are
 * chosen so each marked key is populated, and the eval case deliberately violates a rule in the <b>second</b> row
 * of a repeating group — {@code Order/Items[2]/Count} is where canonicalization is actually observable, since it
 * carries both an omitted {@code [1]} on the root and a real index further down.
 *
 * <p>Two things it deliberately does not do. It does not check {@code message.rule}, whose schema describes a
 * <em>full slash-path</em> and not a pointer — the leading slash is correct there, and a guard that assumed
 * otherwise would demand a wrong change. And it does not scan markdown: a doc's {@code --group /Order} is an INPUT
 * path, where the leading slash is right, so a text sweep cannot tell a published pointer from an argument without
 * re-deciding the whole distinction the schema already records.
 */
class PublishedPointerCanonicalTest {

    private static final ObjectMapper M = new ObjectMapper();

    /**
     * The schema keyword that marks a value as a published A12 pointer — always canonical, no exceptions. JSON
     * Schema's own extension point, so the mark is machine-readable rather than inferred from prose.
     */
    private static final String POINTER_FORMAT = "a12-pointer";

    /**
     * The keyword for a <b>message address</b>, which is a weaker promise and must not borrow the stronger mark.
     *
     * <p>A message address is A12's {@code PartiallyKnownDocumentMultiPointer}; A12 defines no pointer string for
     * one that does not name a single cell, so such a value is published as the interpreter's house form. Marking
     * it {@value #POINTER_FORMAT} would state a contract the value cannot keep — and would make this guard demand
     * a canonical spelling that does not exist.
     */
    private static final String MESSAGE_ADDRESS_FORMAT = "a12-message-address";

    @Test
    void everyKeyMarkedAsAnA12PointerPublishesOne(@TempDir Path dir) throws Exception {
        Map<String, String> evalMarks = marks("eval-doc.schema.json");
        Map<String, String> computeMarks = marks("compute-doc.schema.json");
        Map<String, String> ruleEvalMarks = marks("rule-eval.schema.json");
        assertEquals(
                Map.of("field", MESSAGE_ADDRESS_FORMAT,
                        "referenced", MESSAGE_ADDRESS_FORMAT,
                        "fillToFix", MESSAGE_ADDRESS_FORMAT),
                evalMarks,
                "eval publishes the three IMessage channels, and they are message ADDRESSES — pinned so removing "
                        + "a mark (silently shrinking this guard) fails, and so does quietly promoting one to "
                        + POINTER_FORMAT);
        assertEquals(Map.of("field", POINTER_FORMAT, "declaration", POINTER_FORMAT), computeMarks,
                "a computed cell is an exact pointer at every level, so both of its keys keep the strong mark");
        assertEquals(evalMarks, ruleEvalMarks,
                "a focused rule firing publishes the same three IMessage channels as a whole-document message, so "
                        + "it carries the same marks — a divergence here would mean one firing is described two ways");

        Map<String, Integer> seen = new LinkedHashMap<>();
        for (Scenario scenario : scenarios(dir, evalMarks, computeMarks, ruleEvalMarks)) {
            scenario.marks().keySet().forEach(key -> seen.putIfAbsent(scenario.reach(key), 0));
            JsonNode env = M.readTree(scenario.run().out());
            walk(scenario, env.path("data"), seen);
        }

        seen.forEach((reach, count) -> assertTrue(count > 0,
                () -> "no scenario produced a value for '" + reach + "', so this guard proves nothing about it — "
                        + "extend the scenarios until it does, rather than leaving the key unchecked"));
    }

    /** Every marked property of one schema, as key → the format keyword it carries. */
    private static Map<String, String> marks(String schema) throws Exception {
        Map<String, String> found = new LinkedHashMap<>();
        collectPointerKeys(readSchema(schema), found);
        return found;
    }

    /** One runtime invocation, together with the marks of the schema its payload conforms to. */
    private record Scenario(String label, List<String> command, Map<String, String> marks) {
        CliTestSupport.Result run() {
            CliTestSupport.Result res = CliTestSupport.run(command.toArray(new String[0]));
            assertEquals(0, res.code(), () -> label + " failed: " + res.err() + res.out());
            return res;
        }

        /** A key's coverage identity. {@code field} is marked differently by the two schemas, so both must be hit. */
        String reach(String key) {
            return marks.get(key) + ":" + key;
        }
    }

    /**
     * Four invocations that between them populate all four marked keys:
     * a VALUE error in a repeated row ({@code field}, {@code referenced}), an OMISSION error
     * ({@code fillToFix}), a computation ({@code field}, {@code declaration}), and the same row violation read
     * through the FOCUSED single-rule verb, which publishes the message channels on its own payload.
     *
     * <p>The rules the eval scenarios need are authored <b>through the tool</b> rather than added to a committed
     * fixture — {@code order-ruled} is shared by many tests, and a guard should not reshape everyone else's model
     * to arrange its own inputs.
     */
    private static List<Scenario> scenarios(Path dir, Map<String, String> evalMarks,
            Map<String, String> computeMarks, Map<String, String> ruleEvalMarks) throws Exception {
        Path order = fixture("order-ruled.dm.json", dir);
        addRule(order, dir.resolve("row-rule.json"), """
                {"field": "/Order/Items/Count",
                 "condition": "FieldFilled(/Order/Items/Count) And [/Order/Items/Count] > 10",
                 "code": "COUNT_TOO_HIGH",
                 "messages": [{"locale": "en_US", "text": "At most ten per line."}]}""");
        // No FieldFilled guard on purpose: the comparison then fires on the empty field's 0-substitution, which
        // the kernel types OMISSION_ERROR — the only shape that produces `fillToFix` (adapter ErrorTypeLawsTest).
        addRule(order, dir.resolve("omission-rule.json"), """
                {"field": "/Order/BackorderQuantity",
                 "condition": "[/Order/BackorderQuantity] >= 0",
                 "code": "BACKORDER_NONNEG",
                 "messages": [{"locale": "en_US", "text": "Backorder quantity must be stated."}]}""");

        Path violating = doc(dir, "violating.json", """
                {"Order": {"CustomerName": "Acme",
                           "Items": [{"Sku": "A", "Count": 1}, {"Sku": "B", "Count": 99}]}}""");
        Path computed = fixture("subscription-computed.dm.json", dir);
        Path billing = doc(dir, "billing.json", """
                {"Subscription": {"Billing": {"BaseFee": "250.00"}}}""");

        return List.of(
                new Scenario("model eval (row violation → field/referenced, empty number → fillToFix)",
                        List.of("-m", order.toString(), "model", "eval", "--instance", violating.toString()),
                        evalMarks),
                new Scenario("rule eval (the same row violation, focused → field/referenced on the firing)",
                        List.of("-m", order.toString(), "rule", "eval", "/Order/Items/COUNT_TOO_HIGH",
                                "--instance", violating.toString()),
                        ruleEvalMarks),
                new Scenario("model compute (field/declaration)",
                        List.of("-m", computed.toString(), "model", "compute", "--instance", billing.toString()),
                        computeMarks));
    }

    /**
     * The three properties the schema promises, asserted <b>independently of the boundary converter {@code A12Pointers}</b>: no leading
     * slash, no explicit {@code [1]}, and a string the pointer type can actually parse.
     *
     * <p>The independence is the point, and it is a correction. The first version asserted
     * {@code value.equals(A12Pointers.canonical(value))} — comparing the output against the very function that
     * produced it. Neutering {@code canonical} to return its input would then have made every assertion
     * <em>trivially true</em> while the house form flowed straight through to the user: a guard whose oracle is
     * its own implementation cannot fail for the reason it exists. So the first two checks restate the published
     * contract in the contract's own terms, and only the third consults the type — for representability, which is
     * a different question from canonicality.
     *
     * <p>Representability is checked against the <b>exact</b> pointer type, and the scenarios are chosen so every
     * marked value is one. A message address that carries a wildcard above the last part, or the {@code -5} of an
     * unresolved parallel-iteration level, is A12's {@code PartiallyKnownDocumentMultiPointer} — for which A12
     * defines no pointer string at all — so it is published as the interpreter's house form and is deliberately
     * outside this guard's reach rather than silently renamed "canonical". The two domains are pinned against
     * the real kernel factories by {@code A12PointerKernelDomainDiffTest}, which is where that belongs.
     */
    private static void assertCanonical(String label, String key, String value) {
        assertFalse(value.startsWith("/"),
                () -> label + ": '" + value + "' under '" + key + "' has a leading slash. The schema marks it "
                        + POINTER_FORMAT + ", whose canonical form has none — this is the interpreter's house "
                        + "form escaping without going through A12Pointers");
        assertFalse(EXPLICIT_FIRST_REPETITION.matcher(value).find(),
                () -> label + ": '" + value + "' under '" + key + "' spells [1], which A12's canonical form omits");
        assertDoesNotThrow(() -> A12PointerFormat.INSTANCE.parse(value),
                () -> label + ": '" + value + "' under '" + key + "' is not a representable A12 pointer at all");
    }

    /**
     * A <b>message address</b> keeps the canonical contract exactly when it names one cell, and is otherwise the
     * house form — which A12 can neither parse nor render, so demanding canonicality of it would be demanding a
     * spelling that does not exist.
     *
     * <p>The leading slash is the published discriminator (it is what the schema tells a consumer to test), so
     * this checks the two branches by it: no slash ⇒ the full canonical contract; slash ⇒ a well-formed house
     * form. A value that is neither — a bare name that will not parse, say — fails both branches.
     */
    private static void assertMessageAddress(String label, String key, String value) {
        if (!value.startsWith("/")) {
            assertCanonical(label, key, value);
            return;
        }
        assertTrue(HOUSE_FORM.matcher(value).matches(),
                () -> label + ": '" + value + "' under '" + key + "' is neither a canonical A12 pointer nor the "
                        + "interpreter house form the schema admits for a partially-known address");
    }

    /** An explicitly spelled first repetition — legal input, never canonical output. */
    private static final Pattern EXPLICIT_FIRST_REPETITION = Pattern.compile("\\[1]");

    /** The house form: a leading slash, then {@code /Name} segments each optionally carrying {@code [<index>]}. */
    private static final Pattern HOUSE_FORM =
            Pattern.compile("(/[\\w_-]+(\\[-?\\d+])?)+");

    private static void walk(Scenario scenario, JsonNode node, Map<String, Integer> seen) {
        if (node.isArray()) {
            node.forEach(child -> walk(scenario, child, seen));
            return;
        }
        if (!node.isObject()) {
            return;
        }
        node.properties().forEach(e -> {
            JsonNode value = e.getValue();
            String format = scenario.marks().get(e.getKey());
            if (format != null) {
                for (String pointer : strings(value)) {
                    switch (format) {
                        case POINTER_FORMAT -> assertCanonical(scenario.label(), e.getKey(), pointer);
                        case MESSAGE_ADDRESS_FORMAT -> assertMessageAddress(scenario.label(), e.getKey(), pointer);
                        default -> throw new AssertionError("unhandled pointer mark '" + format + "' on '"
                                + e.getKey() + "' — every mark needs an assertion, or the guard silently skips it");
                    }
                    seen.merge(scenario.reach(e.getKey()), 1, Integer::sum);
                }
            }
            walk(scenario, value, seen);
        });
    }

    private static List<String> strings(JsonNode node) {
        List<String> out = new ArrayList<>();
        if (node.isTextual()) {
            out.add(node.asText());
        } else if (node.isArray()) {
            node.forEach(child -> {
                if (child.isTextual()) {
                    out.add(child.asText());
                }
            });
        }
        return out;
    }

    /** Every property (including under {@code $defs}) carrying an address mark, on itself or its items. */
    private static void collectPointerKeys(JsonNode node, Map<String, String> into) {
        if (node.isArray()) {
            node.forEach(child -> collectPointerKeys(child, into));
            return;
        }
        if (!node.isObject()) {
            return;
        }
        JsonNode props = node.get("properties");
        if (props != null && props.isObject()) {
            props.properties().forEach(e -> {
                JsonNode p = e.getValue();
                String format = p.has("format") ? p.path("format").asText() : p.path("items").path("format").asText();
                if (POINTER_FORMAT.equals(format) || MESSAGE_ADDRESS_FORMAT.equals(format)) {
                    into.put(e.getKey(), format);
                }
            });
        }
        node.forEach(child -> collectPointerKeys(child, into));
    }

    private static void addRule(Path model, Path spec, String json) throws Exception {
        Files.writeString(spec, json);
        CliTestSupport.Result res = CliTestSupport.run("-m", model.toString(), "rule", "add", spec.toString());
        assertEquals(0, res.code(), () -> "the scenario's rule was rejected: " + res.err() + res.out());
    }

    private static Path doc(Path dir, String name, String json) throws Exception {
        Path file = dir.resolve(name);
        Files.writeString(file, json);
        return file;
    }

    private static Path fixture(String name, Path dir) throws Exception {
        Path file = dir.resolve(name);
        try (InputStream in = PublishedPointerCanonicalTest.class.getClassLoader()
                .getResourceAsStream("models/" + name)) {
            assertTrue(in != null, () -> "fixture model on the classpath: " + name);
            Files.writeString(file, new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
        }
        return file;
    }

    private static JsonNode readSchema(String name) throws Exception {
        try (InputStream in = PublishedPointerCanonicalTest.class.getResourceAsStream("/schema/" + name)) {
            assertTrue(in != null, () -> "schema on the classpath: " + name);
            return M.readTree(in);
        }
    }
}
