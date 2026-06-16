package io.github.mbackschat.a12.dm.edit;

import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.core.ModelResolver;
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft;
import io.github.mbackschat.a12.dm.modelkit.FieldKind;
import io.github.mbackschat.a12.dm.modelkit.ModelDocument;

/**
 * The kernel-free {@link ModelResolver} ({@code id → DM-JSON}) resolves includes/type-def imports without the
 * caller importing {@code com.mgmtp.a12.kernel.*} (DEV-API-GAPS smaller edge). Note: this file imports no
 * kernel package — the whole multi-model loop runs through the dmkits surface.
 */
class ModelResolverTest {

    private static String pricelist() {
        return DocumentModelDraft.named("pricelist").locale("en_US")
                .rootGroup("Pricelist", p -> p.field("Currency", FieldKind.STRING))
                .build().toJson();
    }

    private static String catalog() {
        return DocumentModelDraft.named("catalog").locale("en_US")
                .rootGroup("Catalog", c -> c.field("StoreName", FieldKind.STRING))
                .build().toJson();
    }

    @Test
    void resolvesAnIncludeWithoutTouchingTheKernelApi() {
        ModelResolver resolver = id -> "pricelist".equals(id) ? pricelist() : null;

        EditSession s = EditSession.read(catalog(), resolver);     // ModelResolver overload — no kernel import
        s.addInclude("pricelist", "pricelist", "/Catalog", "Retail");
        s.validate();                                              // resolves the include through the SAM

        assertTrue(ModelDocument.read(s.toJson()).groupPaths().contains("/Catalog/Retail"),
                "the included model mounted, resolved by the kernel-free ModelResolver");
    }
}
