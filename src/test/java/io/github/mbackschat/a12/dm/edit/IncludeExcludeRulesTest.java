package io.github.mbackschat.a12.dm.edit;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.StringReader;
import java.io.StringWriter;

import org.junit.jupiter.api.Test;

import com.mgmtp.a12.kernel.md.facade.DocumentModelServiceFactory;
import com.mgmtp.a12.kernel.md.model.api.IDocumentModel;
import com.mgmtp.a12.kernel.md.model.api.services.IDocumentModelReferenceResolver;
import com.mgmtp.a12.kernel.md.model.api.services.IDocumentModelSerializer;

import io.github.mbackschat.a12.dm.core.ModelLoader;
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft;
import io.github.mbackschat.a12.dm.modelkit.FieldKind;
import io.github.mbackschat.a12.dm.modelkit.ModelDocument;
import io.github.mbackschat.a12.dm.modelkit.MountOptions;
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl;
import io.github.mbackschat.a12.dm.rulekit.dsl.NumberRef;
import io.github.mbackschat.a12.dm.rulekit.dsl.Severity;

/**
 * Include <b>{@code excludeRules}</b> (FEATURE-GAPS §3.9) — a mount option that drops the <em>included</em>
 * model's own rules (and {@code excludeComputations}, its computations) when the kernel expands the mount.
 * The kernel ties the flag to a mount: {@code excludeRules} is valid only where {@code modelAlias} is set
 * (metamodel rule {@code EXCLUDE_RULES_INVALID}), which a mount always is.
 *
 * <p>Verified behaviorally through the kernel's own <b>expand</b>: a plain mount carries the included
 * model's rule into the expanded model; the same mount with {@code excludeRules} does not, while its
 * <em>fields</em> still mount (only the rules are excluded).
 */
class IncludeExcludeRulesTest {

    private static final IDocumentModelSerializer SERIALIZER =
            new DocumentModelServiceFactory().createDocumentModelSerializer();

    /** The shared model — a price list that carries its OWN rule (error code {@code LIST_PRICE_NEG}). */
    private static String pricelist() {
        String structure = DocumentModelDraft.named("pricelist").locale("en_US")
                .rootGroup("Pricelist", p -> p.numberField("ListPrice", n -> n.fractionDigits(2)))
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

    private static String catalogStructure() {
        return DocumentModelDraft.named("catalog").locale("en_US")
                .rootGroup("Catalog", c -> c.field("StoreName", FieldKind.STRING))
                .build().toJson();
    }

    private static IDocumentModelReferenceResolver resolver(String pricelistJson) {
        return reference -> {
            if ("pricelist".equals(reference)) {
                try {
                    return SERIALIZER.deserialize(new StringReader(pricelistJson));
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }
            throw new IllegalStateException("unknown reference: " + reference);
        };
    }

    /** Serialize the model after the kernel has expanded the mount (rules inline into the expanded form). */
    private static String expanded(String baseJson, IDocumentModelReferenceResolver resolver) throws Exception {
        IDocumentModel model = ModelLoader.load(new StringReader(baseJson), resolver);
        StringWriter out = new StringWriter();
        SERIALIZER.serialize(model, out, n -> { });
        return out.toString();
    }

    @Test
    void aPlainMountCarriesTheIncludedModelsRule() throws Exception {
        IDocumentModelReferenceResolver resolver = resolver(pricelist());
        String base = ModelDocument.read(catalogStructure())
                .addInclude("pricelist", "pricelist")
                .mount("/Catalog", "Prices", "pricelist")
                .validate(resolver)
                .toJson();
        assertTrue(expanded(base, resolver).contains("LIST_PRICE_NEG"),
                "a plain mount carries the included price-list's own rule into the expanded model");
    }

    @Test
    void excludeRulesDropsTheIncludedModelsRuleButKeepsItsFields() throws Exception {
        IDocumentModelReferenceResolver resolver = resolver(pricelist());
        String base = ModelDocument.read(catalogStructure())
                .addInclude("pricelist", "pricelist")
                .mount("/Catalog", "Prices", "pricelist", MountOptions.single().excludingRules())
                .validate(resolver)
                .toJson();
        String expanded = expanded(base, resolver);
        assertFalse(expanded.contains("LIST_PRICE_NEG"),
                "excludeRules drops the included model's rules when the kernel expands the mount");
        assertTrue(expanded.contains("ListPrice"),
                "only the rules are excluded — the included model's fields still mount");
    }

    @Test
    void excludeRulesIsSettableThroughTheIncludeEditor() throws Exception {
        IDocumentModelReferenceResolver resolver = resolver(pricelist());
        EditSession s = EditSession.read(catalogStructure(), resolver);
        s.addInclude("pricelist", "pricelist");
        s.include("pricelist").mount("/Catalog", "Prices", MountOptions.single().excludingRules());
        s.validate();
        assertFalse(expanded(s.toJson(), resolver).contains("LIST_PRICE_NEG"),
                "the IncludeEditor mount carries excludeRules through to expand");
    }
}
