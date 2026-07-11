package io.github.mbackschat.a12.dm.rulekit.dsl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.core.ModelResolver;

import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft;
import io.github.mbackschat.a12.dm.modelkit.FieldKind;

/**
 * The owner's "works on ANY rule" requirement (2026-06-08): rulekit must <b>author, render, and
 * validate</b> rules whose conditions reference fields that come from an <b>include</b> and from a
 * <b>type definition</b> — the two model-composition mechanisms real applications lean on. Both are
 * exercised together, end-to-end against the real kernel:
 *
 * <ul>
 *   <li>fixture (modelkit-dogfooded): a {@code storefront} that <em>includes</em> a {@code pricelist}
 *       (mounted at {@code /Storefront/Prices}) and declares a {@code Sku} <em>typedef</em> used by
 *       {@code /Storefront/ProductSku} — both base and included models authored by modelkit;</li>
 *   <li>the validate leg is {@link BoundModel#add(Rule)} (not {@link io.github.mbackschat.a12.dm.rulekit.validate.RuleValidator},
 *       which takes no resolver): {@code add} re-binds with the same resolver → expands the include →
 *       {@code checkConsistency}, so a successful {@code add} <em>is</em> the kernel accepting the rule.</li>
 * </ul>
 *
 * <p>Read-direction (read) coverage of the same fixture lives in
 * {@code adapter.IncludesTypedefsReadTest}; this is the author/render/validate half.
 */
class IncludesTypedefsRuleTest {

    private static final String TYPEDEF_FIELD = "/Storefront/ProductSku";   // resolves to STRING via the Sku typedef
    private static final String INCLUDED_FIELD = "/Storefront/Prices/ListPrice";   // from the mounted pricelist

    /** The included model — a price list (own domain), authored by modelkit. */
    private static DocumentModelDraft pricelist() {
        return DocumentModelDraft.named("pricelist").locale("en_US")
                .rootGroup("Pricelist", p -> p
                        .numberField("ListPrice", n -> n.fractionDigits(2))
                        .field("Currency", FieldKind.STRING))
                .build();
    }

    /** The base model — includes the pricelist AND uses a string typedef for the SKU. */
    private static DocumentModelDraft storefront() {
        return DocumentModelDraft.named("storefront").locale("en_US")
                .typeDef("Sku", t -> t.string(s -> s.maxLength(12)))
                .include("pricelist", "pricelist")
                .rootGroup("Storefront", s -> s
                        .field("StoreName", FieldKind.STRING)
                        .typedefField("ProductSku", "Sku")
                        .mount("Prices", "pricelist"))
                .build();
    }

    private static ModelResolver resolver() {
        String includedJson = pricelist().toJson();
        return reference -> {
            if ("pricelist".equals(reference)) {
                return includedJson;
            }
            throw new IllegalStateException("unknown reference: " + reference);
        };
    }

    private static BoundModel bind() {
        return BoundModel.ofJson(storefront().toJson(), resolver());
    }

    @Test
    void aRuleOnATypedefFieldRendersAndValidates() {
        BoundModel m = bind();
        // the typedef field behaves as its underlying kind (STRING) — a plain string condition
        Condition c = m.str(TYPEDEF_FIELD).filled().and(m.str(TYPEDEF_FIELD).violates("[A-Z]{3}.*"));
        assertEquals("FieldFilled(/Storefront/ProductSku) And [/Storefront/ProductSku] PatternViolated \"[A-Z]{3}.*\"",
                new DslRenderer(ConditionLanguage.EN).render(c));
        // German render of the same AST (rendering language is independent of the model's locales)
        assertTrue(new DslRenderer(ConditionLanguage.DE).render(c).contains("NichtWieMuster"),
                "DE render uses the German keyword");

        Rule rule = m.rule(m.group("/Storefront")).name("SkuFormatRule").errorField(m.str(TYPEDEF_FIELD))
                .when(c).code("SKU_FMT").severity(Severity.ERROR)
                .message(Dsl.en("The product SKU must start with three letters.")).build();
        // add → re-bind with resolver → expand → checkConsistency: a clean add means the kernel accepts it
        BoundModel m2 = m.add(rule);
        assertTrue(m2.toJson().contains("SkuFormatRule"), "the rule landed in the storefront model");
    }

    @Test
    void aRuleReferencingAnIncludedFieldRendersAndValidates() {
        BoundModel m = bind();
        // the field comes from the included pricelist, addressed by its mount name-path
        Condition c = m.num(INCLUDED_FIELD).filled().and(m.num(INCLUDED_FIELD).gt(100000));
        assertEquals("FieldFilled(/Storefront/Prices/ListPrice) And [/Storefront/Prices/ListPrice] > 100000",
                new DslRenderer(ConditionLanguage.EN).render(c));

        Rule rule = m.rule(m.group("/Storefront")).name("PriceCapRule").errorField(m.num(INCLUDED_FIELD).field())
                .when(c).code("PRICE_CAP").severity(Severity.ERROR)
                .message(Dsl.en("The list price exceeds the cap.")).build();
        BoundModel m2 = m.add(rule);
        assertTrue(m2.toJson().contains("PriceCapRule"), "the rule landed");
        // proof the include stayed separate (not inlined) after writing a rule that uses an included field
        assertTrue(m2.toJson().contains("\"modelAlias\" : \"pricelist\""), "the include reference is preserved");
        assertEquals(false, m2.toJson().contains("/Pricelist/ListPrice"), "included content must not leak into the base");
    }

    @Test
    void aRuleSpanningBothATypedefAndAnIncludedFieldValidates() {
        BoundModel m = bind();
        // "a priced product must carry a SKU": error when the included price is filled but the typedef SKU is not
        Condition c = m.num(INCLUDED_FIELD).filled().and(m.str(TYPEDEF_FIELD).notFilled());
        Rule rule = m.rule(m.group("/Storefront")).name("PricedNeedsSkuRule").errorField(m.str(TYPEDEF_FIELD))
                .when(c).code("PRICED_NEEDS_SKU").severity(Severity.ERROR)
                .message(Dsl.en("A priced product must carry a SKU.")).build();

        BoundModel m2 = m.add(rule);
        // round-trips: the written model re-binds with the same resolver (include + typedef both intact)
        assertTrue(BoundModel.ofJson(m2.toJson(), resolver()).toJson().contains("PricedNeedsSkuRule"));
    }
}
