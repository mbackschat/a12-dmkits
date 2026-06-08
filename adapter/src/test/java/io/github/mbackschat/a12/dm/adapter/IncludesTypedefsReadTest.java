package io.github.mbackschat.a12.dm.adapter;

import static io.github.mbackschat.a12.dm.rulekit.dsl.Dsl.en;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.StringReader;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.mgmtp.a12.kernel.md.facade.DocumentModelServiceFactory;
import com.mgmtp.a12.kernel.md.model.api.services.IDocumentModelReferenceResolver;
import com.mgmtp.a12.kernel.md.model.api.services.IDocumentModelSerializer;

import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft;
import io.github.mbackschat.a12.dm.modelkit.FieldKind;
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel;
import io.github.mbackschat.a12.dm.rulekit.dsl.Condition;
import io.github.mbackschat.a12.dm.rulekit.dsl.ConditionLanguage;
import io.github.mbackschat.a12.dm.rulekit.dsl.DslRenderer;
import io.github.mbackschat.a12.dm.rulekit.dsl.Rule;
import io.github.mbackschat.a12.dm.rulekit.dsl.Severity;

/**
 * The READ direction of the "works on ANY rule" requirement (2026-06-08): rulekit must <b>read</b>
 * rules whose conditions reference an <b>included</b> field and a <b>typedef</b> field — the read
 * counterpart to {@code dsl.IncludesTypedefsRuleTest}. Before this, no committed (always-run) read
 * test threaded a reference resolver at all — that path was covered only by the gated Corpus-1 harness.
 *
 * <p>Same modelkit-dogfooded fixture: a {@code storefront} including a {@code pricelist} (mounted at
 * {@code /Storefront/Prices}) and using a {@code Sku} string typedef. The adapter loads it with the
 * resolver, and read must round-trip (render → parse → convert → render is the identity) — proving
 * an included/typedef field resolves the same on the read path as on the author path (name-path, §10).
 */
class IncludesTypedefsReadTest {

    private static final DslRenderer RENDER = new DslRenderer(ConditionLanguage.EN);
    private static final IDocumentModelSerializer SERIALIZER =
            new DocumentModelServiceFactory().createDocumentModelSerializer();

    private static final String TYPEDEF_FIELD = "/Storefront/ProductSku";
    private static final String INCLUDED_FIELD = "/Storefront/Prices/ListPrice";

    private static DocumentModelDraft pricelist() {
        return DocumentModelDraft.named("pricelist").locale("en_US")
                .rootGroup("Pricelist", p -> p
                        .numberField("ListPrice", n -> n.fractionDigits(2))
                        .field("Currency", FieldKind.STRING))
                .build();
    }

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

    private static IDocumentModelReferenceResolver resolver() {
        String includedJson = pricelist().toJson();
        return reference -> {
            if ("pricelist".equals(reference)) {
                try {
                    return SERIALIZER.deserialize(new StringReader(includedJson));
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }
            throw new IllegalStateException("unknown reference: " + reference);
        };
    }

    /** Build the storefront with three rules; return (the model-with-rules, the full-name → condition map). */
    private static BoundModel withRules(Map<String, Condition> cases) {
        BoundModel m = BoundModel.ofJson(storefront().toJson(), resolver());
        // (a) a rule on the typedef field (behaves as STRING)
        Condition typedefC = m.str(TYPEDEF_FIELD).filled().and(m.str(TYPEDEF_FIELD).violates("[A-Z]{3}.*"));
        // (b) a rule on the included field (from the mounted pricelist)
        Condition includedC = m.num(INCLUDED_FIELD).filled().and(m.num(INCLUDED_FIELD).gt(100000));
        // (c) a rule spanning both
        Condition spanC = m.num(INCLUDED_FIELD).filled().and(m.str(TYPEDEF_FIELD).notFilled());

        BoundModel withRules = m
                .add(m.rule(m.group("/Storefront")).name("SkuFormatRule").errorField(m.str(TYPEDEF_FIELD))
                        .when(typedefC).code("SKU_FMT").severity(Severity.ERROR)
                        .message(en("The product SKU must start with three letters.")).build())
                .add(m.rule(m.group("/Storefront")).name("PriceCapRule").errorField(m.num(INCLUDED_FIELD).field())
                        .when(includedC).code("PRICE_CAP").severity(Severity.ERROR)
                        .message(en("The list price exceeds the cap.")).build())
                .add(m.rule(m.group("/Storefront")).name("PricedNeedsSkuRule").errorField(m.str(TYPEDEF_FIELD))
                        .when(spanC).code("PRICED_NEEDS_SKU").severity(Severity.ERROR)
                        .message(en("A priced product must carry a SKU.")).build());

        cases.put("/Storefront/SkuFormatRule", typedefC);
        cases.put("/Storefront/PriceCapRule", includedC);
        cases.put("/Storefront/PricedNeedsSkuRule", spanC);
        return withRules;
    }

    @Test
    void readsRulesReferencingIncludedAndTypedefFieldsAsAnIdentityRoundTrip() {
        Map<String, Condition> cases = new LinkedHashMap<>();
        KernelAdapter adapter = KernelAdapter.ofJson(withRules(cases).toJson(), resolver());

        cases.forEach((fullName, original) ->
                assertEquals(RENDER.render(original), RENDER.render(converted(adapter.read(fullName))),
                        () -> "read round-trip mismatch for " + fullName));
    }

    @Test
    void readAllTypesEveryRuleOverAnIncludeAndTypedefModel() {
        KernelAdapter adapter = KernelAdapter.ofJson(withRules(new LinkedHashMap<>()).toJson(), resolver());
        Map<String, ReadResult> all = adapter.readAll();
        // every rule converts — none of the included/typedef references degrades to Opaque
        all.forEach((fullName, read) -> assertInstanceOf(ReadResult.Converted.class, read,
                () -> "expected a typed read for " + fullName + ", got: " + read));
    }

    @Test
    void whereUsedFindsRulesReferencingIncludedAndTypedefFields() {
        // the find leg (F9a) over the composed model: whereUsed resolves an included / typedef field
        // to the rules that reference it (in condition or as error field).
        KernelAdapter adapter = KernelAdapter.ofJson(withRules(new LinkedHashMap<>()).toJson(), resolver());

        Set<String> usingIncluded = adapter.whereUsed(INCLUDED_FIELD);
        assertTrue(usingIncluded.containsAll(Set.of("/Storefront/PriceCapRule", "/Storefront/PricedNeedsSkuRule")),
                () -> "the included field is used by the cap + spanning rules, got: " + usingIncluded);

        Set<String> usingTypedef = adapter.whereUsed(TYPEDEF_FIELD);
        assertTrue(usingTypedef.containsAll(Set.of("/Storefront/SkuFormatRule", "/Storefront/PricedNeedsSkuRule")),
                () -> "the typedef field is used by the format + spanning rules, got: " + usingTypedef);
    }

    /** Unwrap a {@link ReadResult.Converted}, asserting the read converted (not opaque). */
    private static Condition converted(ReadResult result) {
        return assertInstanceOf(ReadResult.Converted.class, result,
                () -> "expected a converted read, got: " + result).condition();
    }
}
