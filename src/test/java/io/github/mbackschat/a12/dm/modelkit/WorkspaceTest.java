package io.github.mbackschat.a12.dm.modelkit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The kernel-free workspace scanner ({@code workspace list}'s engine): walk a directory of DM-JSON models
 * and build a per-model index — id, type, the super/subtype convention ({@code abstract}/{@code superTypes}/
 * {@code subTypes} header annotations), and every model reference (an {@code include} or a type-def import)
 * <b>cross-resolved to its file within the scan</b>, so an agent dropped into a folder learns what's there
 * and how to wire it without reading a byte of raw JSON. Purely textual (no kernel), so a workspace whose
 * references don't resolve still lists — an unresolved ref simply has a {@code null} resolved path.
 */
class WorkspaceTest {

    /** A small, coherent "product catalog" workspace: a typedef provider, an abstract base, a subtype, a shared lib. */
    private static Path workspace(@TempDir Path root) throws Exception {
        write(root, "CatalogTypes.dm.json",
                DocumentModelDraft.typeDefModel("CatalogTypes").locale("en_US")
                        .typeDef("Sku", t -> t.string(s -> { }))
                        .build().toJson());

        write(root, "Product_Base.dm.json",
                DocumentModelDraft.named("Product_Base").locale("en_US")
                        .label("en_US", "Product")
                        .annotation("abstract", "true")
                        .annotation("subTypes", "Product_Movie")
                        .importTypeDefs("CatalogTypes")
                        .rootGroup("Product", g -> g.field("Title", FieldKind.STRING))
                        .build().toJson());

        write(root, "Product_Movie.dm.json",
                DocumentModelDraft.named("Product_Movie").locale("en_US")
                        .annotation("superTypes", "Product_Base")
                        .rootGroup("Movie", g -> g.field("RuntimeMinutes", FieldKind.NUMBER)
                                // lives under lib/ — resolves only on a recursive scan
                                .mount("Shared", "Catalog_Shared"))
                        .build().toJson());

        Path lib = Files.createDirectory(root.resolve("lib"));
        write(lib, "Catalog_Shared.dm.json",
                DocumentModelDraft.named("Catalog_Shared").locale("en_US")
                        .rootGroup("Shared", g -> g.field("Note", FieldKind.STRING))
                        .build().toJson());
        return root;
    }

    @Test
    void aFlatScanIndexesTheTopLevelModelsWithTheirHeaderFacts(@TempDir Path root) throws Exception {
        List<Workspace.Model> models = Workspace.scan(workspace(root), false);

        assertEquals(3, models.size(), () -> "flat scan sees the three top-level models, not lib/: " + ids(models));
        // deterministic order — sorted by path: CatalogTypes, Product_Base, Product_Movie
        assertEquals(List.of("CatalogTypes", "Product_Base", "Product_Movie"),
                models.stream().map(Workspace.Model::id).toList());

        Workspace.Model base = byId(models, "Product_Base");
        assertEquals("document", base.modelType());
        assertEquals("Product_Base.dm.json", base.path());
        assertEquals("Product", base.label());
        assertTrue(base.isAbstract(), "the `abstract` annotation marks the base abstract");
        assertEquals(List.of("Product_Movie"), base.subTypes(), "subTypes is the comma-split annotation");
        assertTrue(base.superTypes().isEmpty());
        assertEquals(List.of("en_US"), base.locales());
        assertTrue(base.groups() >= 1 && base.fields() >= 1, "structural counts are surfaced");

        // the type-def import resolves to its provider file within the scan
        assertEquals(1, base.typedefImports().size());
        Workspace.Reference imp = base.typedefImports().get(0);
        assertEquals("CatalogTypes", imp.ref());
        assertEquals("CatalogTypes.dm.json", imp.resolvedPath());

        Workspace.Model types = byId(models, "CatalogTypes");
        assertEquals(List.of("Sku"), types.providesTypedefs(), "the provider model advertises its type-defs");
    }

    @Test
    void aSubtypeCarriesItsSuperTypesAndItsIncludeResolvesOnlyWhenInScope(@TempDir Path root) throws Exception {
        Path ws = workspace(root);

        Workspace.Model flatMovie = byId(Workspace.scan(ws, false), "Product_Movie");
        assertEquals(List.of("Product_Base"), flatMovie.superTypes());
        assertEquals(1, flatMovie.includes().size());
        Workspace.Reference flatInc = flatMovie.includes().get(0);
        assertEquals("Catalog_Shared", flatInc.ref());
        assertNull(flatInc.resolvedPath(), "Catalog_Shared lives under lib/ — out of scope for a flat scan");

        // a recursive scan brings lib/ into scope, so the same include now resolves to its file
        List<Workspace.Model> deep = Workspace.scan(ws, true);
        assertEquals(4, deep.size(), () -> "recursive scan also sees lib/: " + ids(deep));
        Workspace.Reference deepInc = byId(deep, "Product_Movie").includes().get(0);
        assertEquals("lib/Catalog_Shared.dm.json", deepInc.resolvedPath(),
                "the include cross-resolves to its file, sub-directory path normalized with '/'");
    }

    @Test
    void aFileThatIsNotADocumentModelIsSkipped(@TempDir Path root) throws Exception {
        Path ws = workspace(root);
        Files.writeString(ws.resolve("not-a-model.json"), "{\"hello\":\"world\"}");
        Files.writeString(ws.resolve("notes.txt"), "scratch");
        assertEquals(3, Workspace.scan(ws, false).size(), "non-model JSON and non-JSON files are ignored");
    }

    private static void write(Path dir, String name, String json) throws Exception {
        Files.writeString(dir.resolve(name), json);
    }

    private static Workspace.Model byId(List<Workspace.Model> models, String id) {
        Optional<Workspace.Model> m = models.stream().filter(x -> id.equals(x.id())).findFirst();
        assertFalse(m.isEmpty(), () -> "no model '" + id + "' in " + ids(models));
        return m.get();
    }

    private static List<String> ids(List<Workspace.Model> models) {
        return models.stream().map(Workspace.Model::id).toList();
    }
}
