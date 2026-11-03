package io.github.mbackschat.a12.dm.adapter.review;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.adapter.review.ModelDiff.ChangeKind;
import io.github.mbackschat.a12.dm.core.ModelResolver;
import io.github.mbackschat.a12.dm.edit.EditSession;
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft;
import io.github.mbackschat.a12.dm.modelkit.FieldKind;

/**
 * F11/MR8 — {@link ModelDiff#between} can diff an <b>includes-bearing</b> model when handed a resolver (the
 * mirror of {@link ModelReport}'s resolver overloads). Probed 2026-07-10: the resolver-less entry point throws
 * {@code IllegalStateException} ("model references a separate model … supply a resolver") on a mounted include,
 * so a kernel-free {@link ModelResolver} overload was added; the constraint (resolver-less = self-contained
 * only) is locked here too.
 */
class ModelDiffIncludesTest {

    private static final ModelResolver RESOLVER = id -> "pricelist".equals(id)
            ? DocumentModelDraft.named("pricelist").locale("en_US")
                    .rootGroup("Pricelist", p -> p.field("Currency", FieldKind.STRING)).build().toJson()
            : null;

    /** A catalog that mounts the pricelist include under /Catalog/Retail. */
    private static String mountedCatalog() {
        EditSession s = EditSession.read(DocumentModelDraft.named("catalog").locale("en_US")
                .rootGroup("Catalog", c -> c.field("StoreName", FieldKind.STRING)).build().toJson(), RESOLVER);
        s.mount("/Catalog", "Retail", "pricelist");
        return s.toJson();
    }

    @Test
    void diffsIncludesBearingModelsThroughTheKernelFreeResolver() {
        String base = mountedCatalog();
        // head = base + a new field alongside the mount → a clean ADDED, but only if the include resolves
        EditSession s = EditSession.read(base, RESOLVER);
        s.group("/Catalog").addField("Region", FieldKind.STRING);
        String head = s.toJson();

        ModelDiff diff = ModelDiff.between(base, head, RESOLVER);   // dm.core.ModelResolver overload
        assertTrue(diff.changes().stream().anyMatch(c -> c.id().equals("/Catalog/Region")
                        && c.change() == ChangeKind.ADDED),
                () -> "the added field is diffed once the include resolves: " + diff.changes());
    }

    @Test
    void resolverlessBetweenStillRefusesAnIncludesBearingModel() {
        String mounted = mountedCatalog();
        assertThrows(IllegalStateException.class, () -> ModelDiff.between(mounted, mounted),
                "without a resolver, a mounted include cannot bind — the documented self-contained-only constraint");
    }
}
