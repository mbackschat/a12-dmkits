package io.github.mbackschat.a12.dm.edit;

import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.core.ModelResolver;

import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft;
import io.github.mbackschat.a12.dm.modelkit.FieldKind;
import io.github.mbackschat.a12.dm.modelkit.ModelDocument;
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl;
import io.github.mbackschat.a12.dm.rulekit.dsl.NumberRef;
import io.github.mbackschat.a12.dm.rulekit.dsl.Severity;
import io.github.mbackschat.a12.dm.rulekit.dsl.StringRef;

/**
 * Working across <b>multiple models</b>: a shared model that carries its <em>own</em> rule, included into
 * a base model at <b>two</b> locations, with the base then authoring rules over the mounted fields and a
 * type-def field — all through {@link EditSession} / the editors, validated against the real kernel via a
 * resolver. Backs the API-study §5d example. (Single-mount include + type-def is also locked by
 * {@code rulekit.dsl.IncludesTypedefsRuleTest}; this adds the two-location + included-rules story.)
 */
class MultiModelTest {

    /** The shared model — a price list that carries its OWN rule (list price not negative). */
    private static String pricelist() {
        String structure = DocumentModelDraft.named("pricelist").locale("en_US")
                .rootGroup("Pricelist", p -> p
                        .numberField("ListPrice", n -> n.fractionDigits(2))
                        .field("Currency", FieldKind.STRING))
                .build().toJson();

        EditSession s = EditSession.read(structure);
        GroupEditor pl = s.group("/Pricelist");
        NumberRef listPrice = pl.num("ListPrice");
        pl.addRule(r -> r.name("ListPriceNonNeg").errorField(listPrice)
                .when(listPrice.filled().and(listPrice.lt(0)))
                .code("LIST_PRICE_NEG").severity(Severity.ERROR)
                .message(Dsl.en("List price must not be negative.")).build());
        return s.validate().toJson();
    }

    /** The base model — a Sku type-def + a ProductSku field; the includes/mounts are added via the session. */
    private static String catalogStructure() {
        return DocumentModelDraft.named("catalog").locale("en_US")
                .typeDef("Sku", t -> t.string(s -> s.maxLength(12)))
                .rootGroup("Catalog", c -> c
                        .field("StoreName", FieldKind.STRING)
                        .typedefField("ProductSku", "Sku"))
                .build().toJson();
    }

    private static ModelResolver resolver(String pricelistJson) {
        return reference -> {
            if ("pricelist".equals(reference)) {
                return pricelistJson;
            }
            throw new IllegalStateException("unknown reference: " + reference);
        };
    }

    @Test
    void includesARuledModelAtTwoLocationsAndRulesOverItsFields() {
        ModelResolver resolver = resolver(pricelist());
        EditSession s = EditSession.read(catalogStructure(), resolver);

        // include the shared price list at TWO locations
        s.addInclude("pricelist", "pricelist", "/Catalog", "Retail");   // declare + mount #1
        s.include("pricelist").mount("/Catalog", "Wholesale");          // mount #2 — same include, second place

        // author rules over the mounted fields (each mount's own copy) + the type-def field
        GroupEditor catalog = s.group("/Catalog");
        NumberRef retail = catalog.num("Retail/ListPrice");             // relative nav across the mount
        NumberRef wholesale = catalog.num("Wholesale/ListPrice");
        StringRef sku = catalog.str("ProductSku");                      // resolves to STRING via the Sku type-def
        catalog.addRule(r -> r.name("RetailCap").errorField(retail)
                .when(retail.filled().and(retail.gt(1000000)))
                .code("RETAIL_CAP").severity(Severity.ERROR)
                .message(Dsl.en("Retail price exceeds the cap.")).build());
        catalog.addRule(r -> r.name("WholesaleCap").errorField(wholesale)
                .when(wholesale.filled().and(wholesale.gt(1000000)))
                .code("WHOLESALE_CAP").severity(Severity.ERROR)
                .message(Dsl.en("Wholesale price exceeds the cap.")).build());
        catalog.addRule(r -> r.name("SkuFormat").errorField(sku)
                .when(sku.filled().and(sku.violates("[A-Z]{3}.*")))
                .code("SKU_FMT").severity(Severity.ERROR)
                .message(Dsl.en("SKU must start with three letters.")).build());

        s.validate();

        ModelDocument result = ModelDocument.read(s.toJson());
        assertTrue(result.groupPaths().contains("/Catalog/Retail"));
        assertTrue(result.groupPaths().contains("/Catalog/Wholesale"));
        String json = s.toJson();
        assertTrue(json.contains("RetailCap") && json.contains("WholesaleCap") && json.contains("SkuFormat"),
                "the base model's rules over the included + type-def fields all landed");
        assertTrue(json.contains("\"modelAlias\" : \"pricelist\""), "the include stays separate (not inlined)");
    }
}
