package io.github.mbackschat.a12.dm.adapter;
import io.github.mbackschat.a12.dm.rulekit.read.ReadResult;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft;
import io.github.mbackschat.a12.dm.rulekit.catalog.OperatorCatalog;
import io.github.mbackschat.a12.dm.rulekit.catalog.OperatorEntry;
import io.github.mbackschat.a12.dm.rulekit.dsl.ConditionLanguage;
import io.github.mbackschat.a12.dm.rulekit.dsl.DslRenderer;

/**
 * Renderer-vs-kernel parity (task #9): every SUPPORTED catalog {@code validExample} is read, our
 * {@link DslRenderer} re-renders it, and the kernel's canonical formatter (its {@code FormatterVisitor},
 * reached via {@code KernelAdapter.canonicalConditionText}) must format <em>our render</em> identically
 * to the original example. Both sides pass through the kernel formatter, so whitespace formatting
 * cancels out; path form and explicit grouping remain part of the canonical spelling, and each fixture
 * therefore uses the catalog example's path policy. This is the strong form of the round-trip oracle:
 * not just "our render re-parses to our AST" but "the kernel sees our render as the same condition as
 * the curated example".
 *
 * <p>Examples that read {@code Opaque} (a deliberately-unmodeled construct) are preserved verbatim, so
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

    private static final String MODEL = read("models/order.dm.json");
    private static final Set<String> MULTI_STAR_IDS = Set.of("Having", "CurrentRepetition");
    private static final String MULTI_STAR_MODEL = DocumentModelDraft.named("warehouse").locale("en_US")
            .rootGroup("Warehouse", warehouse -> warehouse
                    .group("Aisles", aisles -> aisles.repeatable(20)
                            .group("Bins", bins -> bins.repeatable(20)
                                    .numberField("Quantity", field -> { }))))
            .build().toJson();

    @Test
    void ourRenderCanonicalizesIdenticallyToEachCatalogExample() {
        List<String> mismatches = new ArrayList<>();
        List<String> deMismatches = new ArrayList<>();
        List<String> opaque = new ArrayList<>();
        int checked = 0;
        for (OperatorEntry e : OperatorCatalog.load().entries()) {
            if (!e.supported() || e.validExample() == null) {
                continue;
            }
            String dsl = e.validExample().dsl();
            Fixture fixture = fixture(e, dsl, "en_US");
            DslRenderer renderer = new DslRenderer(ConditionLanguage.EN, fixture.renderBase());
            String ourRender = null;
            try {
                // K1: the kernel's canonical form of the curated example
                KernelAdapter a1 = KernelAdapter.ofJson(inject(fixture, "Ex", dsl, "en_US"));
                String k1 = norm(a1.canonicalConditionText(fixture.rulePath("Ex")));
                ReadResult ing = a1.read(fixture.rulePath("Ex"));
                if (!(ing instanceof ReadResult.Converted converted)) {
                    opaque.add(e.id());
                    continue;
                }
                // K2: the kernel's canonical form of OUR re-render of the same condition
                ourRender = renderer.render(converted.condition());
                KernelAdapter a2 = KernelAdapter.ofJson(inject(fixture, "Re", ourRender, "en_US"));
                String k2 = norm(a2.canonicalConditionText(fixture.rulePath("Re")));
                checked++;
                if (!k1.equals(k2)) {
                    mismatches.add(String.format("%n  [%s] example canonical: %s%n        our-render canonical: %s%n        our raw render: %s",
                            e.id(), k1, k2, ourRender));
                }
                // DE parity: render the SAME AST in German, parse it back through the kernel's DE model, and
                // assert it re-reads to the same condition — its EN render equals our EN render. Catches a
                // bad/unparseable German keyword or structure that the EN check can't see.
                Fixture deFixture = fixture(e, dsl, "de_DE");
                String deRender = new DslRenderer(ConditionLanguage.DE, deFixture.renderBase()).render(converted.condition());
                KernelAdapter aDe = KernelAdapter.ofJson(inject(deFixture, "De", deRender, "de_DE"));
                ReadResult deIng = aDe.read(deFixture.rulePath("De"));
                String reEn = deIng instanceof ReadResult.Converted c2 ? renderer.render(c2.condition()) : "(opaque)";
                if (!reEn.equals(ourRender)) {
                    deMismatches.add(String.format("%n  [%s] our DE render: %s%n        DE→kernel→EN: %s%n        expected EN: %s",
                            e.id(), deRender, reEn, ourRender));
                }
            } catch (RuntimeException ex) {
                mismatches.add(String.format("%n  [%s] ERROR (our render rejected/failed): %s%n        our raw render: %s%n        example: %s",
                        e.id(), ex.getMessage(), ourRender, dsl));
            }
        }
        System.out.printf("%n=== renderer↔kernel parity: %d checked, %d EN-mismatched, %d DE-mismatched, %d opaque (%s) ===%n",
                checked, mismatches.size(), deMismatches.size(), opaque.size(), opaque);
        assertTrue(mismatches.isEmpty(), () -> "EN render does not canonicalize like the catalog example:" + mismatches);
        assertTrue(deMismatches.isEmpty(), () -> "DE render does not round-trip through the kernel:" + deMismatches);
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

    private static Fixture fixture(OperatorEntry entry, String dsl, String locale) {
        if (MULTI_STAR_IDS.contains(entry.id())) {
            return new Fixture(localize(MULTI_STAR_MODEL, locale), "/Warehouse", "Aisles/Bins/Quantity", null);
        }
        return new Fixture(localize(MODEL, locale), "/Order", firstReferencedField(dsl), "/Order");
    }

    private static String localize(String modelJson, String locale) {
        return locale.equals("en_US") ? modelJson : modelJson.replace("en_US", locale);
    }

    private static String inject(Fixture fixture, String name, String cond, String locale) {
        String escaped = cond.replace("\\", "\\\\").replace("\"", "\\\"");
        String block = "{ \"type\": \"Rule\", \"id\": \"" + fixture.rulePath(name) + "\", \"name\": \"" + name
                + "\", \"Rule\": { \"errorEntityRelPath\": \"../" + fixture.errorField() + "\", \"errorCode\": \"C\","
                + " \"errorCondition\": \"" + escaped + "\", \"severity\": \"ERROR\","
                + " \"errorMessage\": [ { \"locale\": \"" + locale + "\", \"text\": \"x\" } ] } },\n";
        String modelJson = fixture.modelJson();
        int br = modelJson.indexOf('[', modelJson.indexOf("\"elements\""));
        return modelJson.substring(0, br + 1) + "\n" + block + modelJson.substring(br + 1);
    }

    private record Fixture(String modelJson, String group, String errorField, String renderBase) {
        String rulePath(String name) {
            return group + "/" + name;
        }
    }

    private static String read(String path) {
        try (InputStream in = CatalogRenderCanonicalParityTest.class.getClassLoader().getResourceAsStream(path)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception ex) {
            throw new RuntimeException(ex);
        }
    }
}
