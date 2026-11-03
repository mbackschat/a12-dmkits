package io.github.mbackschat.a12.dm.edit;

import io.github.mbackschat.a12.dm.adapter.KernelExpansionFixture;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.StringReader;
import java.io.StringWriter;
import java.io.UncheckedIOException;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.BooleanNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import com.mgmtp.a12.kernel.md.facade.DocumentModelServiceFactory;
import com.mgmtp.a12.kernel.md.model.api.IDocumentModel;
import io.github.mbackschat.a12.dm.core.ModelResolver;
import com.mgmtp.a12.kernel.md.model.api.services.IDocumentModelSerializer;
import com.mgmtp.a12.kernel.md.model.api.services.IDocumentModelService;

import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft;
import io.github.mbackschat.a12.dm.modelkit.FieldKind;
import io.github.mbackschat.a12.dm.modelkit.ModelDocument;
import io.github.mbackschat.a12.dm.modelkit.MountOptions;
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl;
import io.github.mbackschat.a12.dm.rulekit.dsl.NumberRef;
import io.github.mbackschat.a12.dm.rulekit.dsl.Severity;

/**
 * Include <b>{@code excludeRules}</b> (FEATURE-GAPS) — a mount option that drops the <em>included</em>
 * model's own rules (and {@code excludeComputations}, its computations) when the kernel expands the mount.
 * The kernel ties the flag to a mount: {@code excludeRules} lives inside a mount's {@code includeConfig}
 * (metamodel rule {@code EXCLUDE_RULES_INVALID}), which a mount always is.
 *
 * <p>Verified behaviorally through the kernel's own <b>expand</b>: a plain mount carries the included
 * model's rule into the expanded model; the same mount with {@code excludeRules} does not, while its
 * <em>fields</em> still mount (only the rules are excluded).
 */
class IncludeExcludeRulesTest {

    private static final IDocumentModelSerializer SERIALIZER =
            new DocumentModelServiceFactory().createDocumentModelSerializer();

    private static final ObjectMapper MAPPER = new ObjectMapper();

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

    private static ModelResolver resolver(String pricelistJson) {
        return reference -> {
            if ("pricelist".equals(reference)) {
                return pricelistJson;
            }
            throw new IllegalStateException("unknown reference: " + reference);
        };
    }

    /** Serialize the model after the kernel has expanded the mount (rules inline into the expanded form). This
     *  is a kernel-EXPANSION oracle test, so it drives the kernel through the backend's own expansion port (the kits' loader is now
     *  kernel-free and no longer hands back the expanded {@code IDocumentModel}). */
    private static String expanded(String baseJson, ModelResolver resolver) {
        return KernelExpansionFixture.expandedJson(baseJson, resolver);
    }

    @Test
    void aPlainMountCarriesTheIncludedModelsRule() throws Exception {
        ModelResolver resolver = resolver(pricelist());
        String base = ModelDocument.read(catalogStructure())
                .mount("/Catalog", "Prices", "pricelist")
                .validate(resolver)
                .toJson();
        assertTrue(expanded(base, resolver).contains("LIST_PRICE_NEG"),
                "a plain mount carries the included price-list's own rule into the expanded model");
    }

    @Test
    void excludeRulesDropsTheIncludedModelsRuleButKeepsItsFields() throws Exception {
        ModelResolver resolver = resolver(pricelist());
        String base = ModelDocument.read(catalogStructure())
                .mount("/Catalog", "Prices", "pricelist", MountOptions.single().excludingRules())
                .validate(resolver)
                .toJson();
        String expanded = expanded(base, resolver);
        assertFalse(expanded.contains("LIST_PRICE_NEG"),
                "excludeRules drops the included model's rules when the kernel expands the mount");
        assertTrue(expanded.contains("ListPrice"),
                "only the rules are excluded — the included model's fields still mount");
    }

    /**
     * The authored state reports an exclusion exactly where A12 applies one. DM 29.4 binds the flag through A12's
     * shared boolean law ([KF651](../../../../../../../../../docs/KERNEL-FINDINGS.md#kf651)), so the JSON STRING {@code "True"} excludes as the
     * literal does, while {@code "FALSE"} keeps the rule; kernel 30.8.1 read only the literal.
     *
     * <p>The expand calls are the basis rather than decoration: they establish on the kernel itself what this
     * very model does with its included rule, so the authored state below is compared against A12's own reading.
     */
    @Test
    void theAuthoredStateReportsAnExclusionExactlyWhereA12AppliesOne() throws Exception {
        ModelResolver resolver = resolver(pricelist());
        String authored = ModelDocument.read(catalogStructure())
                .mount("/Catalog", "Prices", "pricelist")
                .validate(resolver)
                .toJson();

        String textualOn = withExcludeRules(authored, TextNode.valueOf("True"));
        assertFalse(expanded(textualOn, resolver).contains("LIST_PRICE_NEG"),
                "basis: A12 drops the included rule when the flag is the string \"True\"");
        assertTrue(excludesRules(textualOn), "the authored state reports the exclusion A12 applies");

        String textualOff = withExcludeRules(authored, TextNode.valueOf("FALSE"));
        assertTrue(expanded(textualOff, resolver).contains("LIST_PRICE_NEG"),
                "basis: A12 keeps the included rule when the flag is the string \"FALSE\"");
        assertFalse(excludesRules(textualOff), "and the authored state reports none");
    }

    /** What a consumer of the published {@code authoredState()} surface sees about the mount's rule exclusion. */
    private static boolean excludesRules(String json) {
        return ModelDocument.read(json).authoredState().groups().get("/Catalog/Prices")
                .structure().mount().orElseThrow().options().excludeRules();
    }

    /** The mount's {@code includeConfig} with its rule exclusion set, edited on the kit's own output rather than
     *  hand-authored. */
    private static String withExcludeRules(String json, JsonNode value) {
        try {
            ObjectNode tree = (ObjectNode) MAPPER.readTree(json);
            for (JsonNode element : tree.path("content").path("modelRoot").path("rootGroups").get(0)
                    .path("Group").path("elements")) {
                if ("Prices".equals(element.path("name").asText())) {
                    ((ObjectNode) element.path("Group").path("includeConfig")).set("excludeRules", value);
                }
            }
            return MAPPER.writeValueAsString(tree);
        } catch (IOException unreadable) {
            throw new UncheckedIOException("the kit's own output must be readable", unreadable);
        }
    }

    @Test
    void excludeRulesIsSettableThroughTheIncludeEditor() throws Exception {
        ModelResolver resolver = resolver(pricelist());
        EditSession s = EditSession.read(catalogStructure(), resolver);
        s.include("pricelist").mount("/Catalog", "Prices", MountOptions.single().excludingRules());
        s.validate();
        assertFalse(expanded(s.toJson(), resolver).contains("LIST_PRICE_NEG"),
                "the IncludeEditor mount carries excludeRules through to expand");
    }
}
