package io.github.mbackschat.a12.dm.adapter;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.rulekit.catalog.OperatorCatalog;
import io.github.mbackschat.a12.dm.rulekit.catalog.OperatorEntry;
import io.github.mbackschat.a12.dm.rulekit.dsl.ConditionLanguage;
import io.github.mbackschat.a12.dm.rulekit.dsl.DslRenderer;

/**
 * Renderer-vs-kernel parity (task #9): every SUPPORTED catalog {@code validExample} is ingested, our
 * {@link DslRenderer} re-renders it, and the kernel's canonical formatter (its {@code FormatterVisitor},
 * reached via {@code KernelAdapter.canonicalConditionText}) must format <em>our render</em> identically
 * to the original example. Both sides pass through the kernel formatter, so kernel-side formatting
 * (line breaks, spacing, path normalization) cancels out — only a genuine token/structure difference in
 * our render survives. This is the strong form of the round-trip oracle: not just "our render re-parses
 * to our AST" but "the kernel sees our render as the same condition as the curated example".
 *
 * <p>Examples that ingest {@code Opaque} (a deliberately-unmodeled construct) are preserved verbatim, so
 * their render is the example text — trivially equal; they are reported, not asserted.
 */
class CatalogRenderCanonicalParityTest {

    /** order.dm fields/paths, in the order to pick a referenced error field (mirrors the N7 harness). */
    private static final List<String> ORDER_FIELDS = List.of(
            "CustomerName", "ProductCode", "Quantity", "UnitPrice", "BackorderQuantity",
            "ExpressShipping", "TermsConfirmed", "Priority", "ShippingAddress/Street", "BillingAddress/PostalCode",
            "Items/Sku", "Items/Count", "OrderDate", "DeliveryDate", "PickupTime", "ScheduledAt",
            "CompletedAt", "RushLevel", "ApproxDate", "BirthDay", "BirthMonth", "BirthYear");

    private static final Map<String, String> ORDER_GROUP_FIELDS = Map.of(
            "ShippingAddress", "ShippingAddress/Street", "BillingAddress", "BillingAddress/PostalCode");

    // render relative to the rule's group (as Rule.render does) — the kernel's canonical form is relative
    // too, so this neutralizes the path-form policy difference and the audit compares real structure.
    private static final DslRenderer RENDER = new DslRenderer(ConditionLanguage.EN, "/Order");
    private static final String MODEL = read("models/order.dm.json");

    @Test
    void ourRenderCanonicalizesIdenticallyToEachCatalogExample() {
        List<String> mismatches = new ArrayList<>();
        List<String> opaque = new ArrayList<>();
        int checked = 0;
        for (OperatorEntry e : OperatorCatalog.load().entries()) {
            if (!e.supported() || e.validExample() == null) {
                continue;
            }
            String dsl = e.validExample().dsl();
            String field = firstReferencedField(dsl);
            String ourRender = null;
            try {
                // K1: the kernel's canonical form of the curated example
                KernelAdapter a1 = KernelAdapter.ofJson(inject(MODEL, "Ex", field, dsl));
                String k1 = norm(a1.canonicalConditionText("/Order/Ex"));
                Ingest ing = a1.ingest("/Order/Ex");
                if (!(ing instanceof Ingest.Converted converted)) {
                    opaque.add(e.id());
                    continue;
                }
                // K2: the kernel's canonical form of OUR re-render of the same condition
                ourRender = RENDER.render(converted.condition());
                KernelAdapter a2 = KernelAdapter.ofJson(inject(MODEL, "Re", field, ourRender));
                String k2 = norm(a2.canonicalConditionText("/Order/Re"));
                checked++;
                if (!k1.equals(k2)) {
                    mismatches.add(String.format("%n  [%s] example canonical: %s%n        our-render canonical: %s%n        our raw render: %s",
                            e.id(), k1, k2, ourRender));
                }
            } catch (RuntimeException ex) {
                mismatches.add(String.format("%n  [%s] ERROR (our render rejected/failed): %s%n        our raw render: %s%n        example: %s",
                        e.id(), ex.getMessage(), ourRender, dsl));
            }
        }
        System.out.printf("%n=== renderer↔kernel parity: %d checked, %d mismatched, %d opaque (%s) ===%n",
                checked, mismatches.size(), opaque.size(), opaque);
        assertTrue(mismatches.isEmpty(), () -> "render does not canonicalize like the catalog example:" + mismatches);
    }

    /** Collapse whitespace (the kernel formatter may pretty-print with line breaks) for a token-level compare. */
    private static String norm(String s) {
        return s.replaceAll("\\s+", " ").trim();
    }

    private static String firstReferencedField(String dsl) {
        return ORDER_FIELDS.stream()
                .filter(name -> Pattern.compile("\\b" + Pattern.quote(name) + "\\b").matcher(dsl).find())
                .findFirst()
                .or(() -> ORDER_GROUP_FIELDS.entrySet().stream()
                        .filter(en -> Pattern.compile("\\b" + en.getKey() + "\\b").matcher(dsl).find())
                        .map(Map.Entry::getValue).findFirst())
                .orElseThrow(() -> new IllegalStateException("no Order field referenced in: " + dsl));
    }

    private static String inject(String modelJson, String name, String errorField, String cond) {
        String escaped = cond.replace("\\", "\\\\").replace("\"", "\\\"");
        String block = "{ \"type\": \"Rule\", \"id\": \"/Order/" + name + "\", \"name\": \"" + name
                + "\", \"Rule\": { \"errorEntityRelPath\": \"../" + errorField + "\", \"errorCode\": \"C\","
                + " \"errorCondition\": \"" + escaped + "\", \"severity\": \"ERROR\","
                + " \"errorMessage\": [ { \"locale\": \"en_US\", \"text\": \"x\" } ] } },\n";
        int br = modelJson.indexOf('[', modelJson.indexOf("\"elements\""));
        return modelJson.substring(0, br + 1) + "\n" + block + modelJson.substring(br + 1);
    }

    private static String read(String path) {
        try (InputStream in = CatalogRenderCanonicalParityTest.class.getClassLoader().getResourceAsStream(path)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception ex) {
            throw new RuntimeException(ex);
        }
    }
}
