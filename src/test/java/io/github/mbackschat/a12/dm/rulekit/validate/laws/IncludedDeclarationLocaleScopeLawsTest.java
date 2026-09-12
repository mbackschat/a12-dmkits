package io.github.mbackschat.a12.dm.rulekit.validate.laws;

import static io.github.mbackschat.a12.dm.rulekit.dsl.Dsl.de;
import static io.github.mbackschat.a12.dm.rulekit.dsl.Dsl.en;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.StringReader;
import java.io.StringWriter;
import java.io.UncheckedIOException;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.mgmtp.a12.kernel.md.facade.DocumentModelServiceFactory;
import com.mgmtp.a12.kernel.md.model.api.IDocumentModel;
import com.mgmtp.a12.kernel.md.model.api.services.DocumentModelExpansionException;
import com.mgmtp.a12.kernel.md.model.api.services.IDocumentModelSerializer;
import com.mgmtp.a12.kernel.md.model.api.services.IDocumentModelService;

import io.github.mbackschat.a12.dm.core.ModelConsistencyException;
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft;
import io.github.mbackschat.a12.dm.modelkit.ModelDocument;
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel;
import io.github.mbackschat.a12.dm.rulekit.dsl.LocalizedText;
import io.github.mbackschat.a12.dm.rulekit.write.ModelWriter;

/**
 * Whose declared locales does a MOUNTED provider's rule answer to — its own model's, or the entry model's?
 *
 * <p>The message-coverage law (KERNEL-FINDINGS §13) is stated over "the declared locales", which is
 * unambiguous only while a model is self-contained. A mount makes two headers available and the law's
 * wording cannot say which one it means; a checker has to pick, and picking the provider's own would accept
 * a model A12 rejects — the direction no consumer can detect, since the Kernel is never asked about a
 * declaration the checker passed.
 *
 * <p><b>A12 answers one layer earlier than expected, and that is the finding.</b> A provider that does not
 * declare every locale of the entry model is refused at EXPANSION — {@code DocumentModelExpansionException},
 * before any rule is examined — so the question of a narrower provider locale set never arises: every model
 * that expands at all already covers the entry's locales, and the mounted rule answers to that set.
 *
 * <p>Own domain (a subscription catalogue mounting a price list).
 */
class IncludedDeclarationLocaleScopeLawsTest {

    private static final IDocumentModelSerializer SERIALIZER =
            new DocumentModelServiceFactory().createDocumentModelSerializer();

    /** The entry declares {@code en_US} and {@code de_DE}; every row varies only what the provider does. */
    private static final List<String> ENTRY_LOCALES = List.of("en_US", "de_DE");

    private static String pricelist(List<String> locales, List<LocalizedText> messages) {
        DocumentModelDraft.Builder draft = DocumentModelDraft.named("pricelist");
        locales.forEach(draft::locale);
        String structure = draft
                .rootGroup("Pricelist", p -> p.numberField("ListPrice", n -> n.fractionDigits(2)))
                .build().toJson();
        return ModelWriter.addRule(structure, "/Pricelist", "ListPriceNonNeg", "../ListPrice",
                "LIST_PRICE_NEG", "[ListPrice] < 0", "ERROR", messages);
    }

    private static String catalog() {
        DocumentModelDraft.Builder draft = DocumentModelDraft.named("catalog");
        ENTRY_LOCALES.forEach(draft::locale);
        String structure = draft
                .rootGroup("Catalog", c -> c.stringField("StoreName", s -> { }))
                .build().toJson();
        return ModelDocument.read(structure)
                .addInclude("pricelist", "pricelist")
                .mount("/Catalog", "Prices", "pricelist")
                .toJson();
    }

    /** Expand the catalogue over a price list built from [locales] and [messages], and serialize the result. */
    private static String expanded(List<String> locales, List<LocalizedText> messages) {
        try {
            IDocumentModelService service = new DocumentModelServiceFactory().createDocumentModelService();
            IDocumentModel model = SERIALIZER.deserialize(new StringReader(catalog()));
            service.expand(model, reference -> {
                if (!"pricelist".equals(reference)) {
                    throw new IllegalStateException("unknown reference: " + reference);
                }
                try {
                    return SERIALIZER.deserialize(new StringReader(pricelist(locales, messages)));
                } catch (IOException unreadable) {
                    throw new UncheckedIOException("the kits' own output must be readable", unreadable);
                }
            });
            StringWriter out = new StringWriter();
            SERIALIZER.serialize(model, out, n -> { });
            return out.toString();
        } catch (IOException unreadable) {
            throw new UncheckedIOException("the kits' own output must be readable", unreadable);
        }
    }

    /** The control: a provider matching the entry's locales, its rule covering both, mounts and is admitted. */
    @Test
    void aMountedRuleCoveringTheEntrysLocalesIsAdmitted() {
        assertNotNull(BoundModel.ofJson(expanded(ENTRY_LOCALES, List.of(en("x"), de("x")))));
    }

    /**
     * The row that answers the question: the mounted rule is judged against the ENTRY model's locale set.
     * Only the rule's message list moves against the control above.
     */
    @Test
    void aMountedRuleAnswersToTheEntryModelsDeclaredLocales() {
        ModelConsistencyException rejected = assertThrows(ModelConsistencyException.class,
                () -> BoundModel.ofJson(expanded(ENTRY_LOCALES, List.of(en("x")))));

        assertTrue(rejected.notifications().stream()
                        .anyMatch(n -> n.message().contains("MVK_ERROR_MESSAGE_FOR_LANGUAGE_MISSING")),
                () -> "expected the entry's de_DE to be demanded, kernel said: " + rejected.getMessage());
    }

    /**
     * …and the reason the question has only one answer: a provider declaring FEWER locales than the entry is
     * refused at expansion, before any declaration is read, so no mounted rule can ever face a narrower set.
     * The rule's messages are the control's, so the locale list is the only thing that moves.
     */
    @Test
    void aProviderDeclaringFewerLocalesThanTheEntryCannotBeMountedAtAll() {
        DocumentModelExpansionException refused = assertThrows(DocumentModelExpansionException.class,
                () -> expanded(List.of("en_US"), List.of(en("x"), de("x"))));

        assertTrue(refused.getMessage().contains("does not support all locales of the base model"),
                refused::getMessage);
    }

    /** The demand is coverage, not equality: a provider declaring MORE than the entry mounts and is admitted. */
    @Test
    void aProviderDeclaringMoreLocalesThanTheEntryMountsNormally() {
        assertNotNull(BoundModel.ofJson(
                expanded(List.of("en_US", "de_DE", "fr_FR"),
                        List.of(en("x"), de("x"), new LocalizedText("fr_FR", "x")))));
    }
}
