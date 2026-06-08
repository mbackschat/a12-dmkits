package io.github.mbackschat.a12.dm.adapter;

import static io.github.mbackschat.a12.dm.rulekit.dsl.Dsl.en;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.StringReader;

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
import io.github.mbackschat.a12.dm.rulekit.dsl.RenderedDiff;
import io.github.mbackschat.a12.dm.rulekit.dsl.Severity;

/**
 * The <b>full modify loop over a composed model</b> (2026-06-08) — the headline bidirectional path
 * (ingest → re-express → diff → write-back → re-ingest) run over a model that uses both an
 * <b>include</b> and a <b>typedef</b>, which {@code ModifyAndDiffTest} (plain model) doesn't reach.
 * It exercises the resolver-threaded write-back specifically: {@link BoundModel#replaceCondition}
 * re-binds with the same resolver → re-expands the include → {@code checkConsistency}, so a write that
 * broke the include would fail loudly here.
 *
 * <p>Same {@code storefront}/{@code pricelist} fixture as {@code IncludesTypedefsIngestTest}: a rule
 * capping the <em>included</em> {@code ListPrice} is ingested, then re-expressed to also require the
 * <em>typedef</em> {@code ProductSku} — so the modified rule spans both composition mechanisms.
 */
class ModifyIncludedRuleTest {

    private static final IDocumentModelSerializer SERIALIZER =
            new DocumentModelServiceFactory().createDocumentModelSerializer();
    private static final DslRenderer RENDER = new DslRenderer(ConditionLanguage.EN);

    private static final String TYPEDEF_FIELD = "/Storefront/ProductSku";
    private static final String INCLUDED_FIELD = "/Storefront/Prices/ListPrice";
    private static final String RULE = "/Storefront/PriceCapRule";

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

    @Test
    void ingestReExpressDiffWriteBackOverAnIncludeAndTypedefModel() {
        BoundModel base = BoundModel.ofJson(storefront().toJson(), resolver());
        Condition cap = base.num(INCLUDED_FIELD).filled().and(base.num(INCLUDED_FIELD).gt(100000));
        BoundModel model = base.add(base.rule(base.group("/Storefront")).name("PriceCapRule")
                .errorField(base.num(INCLUDED_FIELD).field()).when(cap)
                .code("PRICE_CAP").severity(Severity.ERROR).message(en("The list price exceeds the cap.")).build());
        KernelAdapter adapter = KernelAdapter.of(model);   // reuses the model's resolver

        // ingest the rule that references the included field, back to a rulekit AST
        Condition current = assertInstanceOf(Ingest.Converted.class, adapter.ingest(RULE)).condition();
        // re-express: also require the typedef SKU — the modified rule now spans include + typedef
        Condition modified = current.and(base.str(TYPEDEF_FIELD).filled());

        // diff for review (F8 #2)
        RenderedDiff diff = adapter.diff(RULE, modified);
        assertTrue(diff.changed(), () -> diff.unified());
        assertTrue(diff.before().contains("[/Storefront/Prices/ListPrice] > 100000"),
                () -> "before is the original cap on the included field: " + diff.before());
        assertTrue(diff.after().contains("FieldFilled(/Storefront/ProductSku)"),
                () -> "after adds the typedef-field guard: " + diff.after());

        // write-back (F8 #3) over an include model — replaceCondition re-binds with the resolver,
        // re-expands the include, and re-validates; a broken include would throw here
        BoundModel applied = model.replaceCondition(RULE, modified);
        assertTrue(applied.toJson().contains("\"modelAlias\" : \"pricelist\""),
                "the include reference survives a modify that now also touches a typedef field");
        assertFalse(applied.toJson().contains("/Pricelist/ListPrice"),
                "included content must not be inlined into the base on write-back");

        // round-trips: re-ingesting the applied rule yields the modified AST verbatim
        Condition reingested = assertInstanceOf(Ingest.Converted.class,
                KernelAdapter.of(applied).ingest(RULE)).condition();
        assertEquals(RENDER.render(modified), RENDER.render(reingested));
    }
}
