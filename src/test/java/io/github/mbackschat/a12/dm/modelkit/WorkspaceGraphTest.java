package io.github.mbackschat.a12.dm.modelkit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The model-inheritance edge set behind {@code workspace graph}: subtype→supertype edges derived from the
 * super/subtype <em>convention</em> ({@code superTypes}/{@code subTypes} header annotations), unioned across
 * both declaration directions and deduped, each flagged {@code resolved} iff both endpoints are scanned
 * models — so a hierarchy that references a model outside the scan surfaces as an unresolved edge rather than
 * vanishing. Kernel-free, like the rest of {@link Workspace}.
 */
class WorkspaceGraphTest {

    private static Path hierarchy(@TempDir Path root) throws Exception {
        // an abstract base that names two subtypes — but only ONE of them (Movie) is in the folder
        write(root, "Product_Base.dm.json",
                DocumentModelDraft.named("Product_Base").locale("en_US")
                        .annotation("abstract", "true")
                        .annotation("subTypes", "Product_Movie,Product_Book")
                        .rootGroup("Product", g -> g.field("Title", FieldKind.STRING)).build().toJson());
        // the subtype declares the relationship from its own side too (superTypes) — must NOT double the edge
        write(root, "Product_Movie.dm.json",
                DocumentModelDraft.named("Product_Movie").locale("en_US")
                        .annotation("superTypes", "Product_Base")
                        .rootGroup("Movie", g -> g.field("RuntimeMinutes", FieldKind.NUMBER)).build().toJson());
        return root;
    }

    @Test
    void edgesUnionBothConventionDirectionsDedupedAndFlagResolution(@TempDir Path root) throws Exception {
        List<Workspace.Edge> edges = Workspace.inheritanceEdges(Workspace.scan(hierarchy(root), false));

        // (Movie→Base) declared from BOTH sides collapses to one; (Book→Base) survives but unresolved
        assertEquals(2, edges.size(), () -> "deduped union of both directions: " + edges);
        assertTrue(edges.contains(new Workspace.Edge("Product_Movie", "Product_Base", true)),
                () -> "the in-scope subtype edge resolves: " + edges);
        assertTrue(edges.contains(new Workspace.Edge("Product_Book", "Product_Base", false)),
                () -> "a subtype declared by the base but absent from the folder is an unresolved edge: " + edges);
    }

    @Test
    void deterministicOrderBySubtypeThenSupertype(@TempDir Path root) throws Exception {
        List<Workspace.Edge> edges = Workspace.inheritanceEdges(Workspace.scan(hierarchy(root), false));
        assertEquals(List.of("Product_Book", "Product_Movie"), edges.stream().map(Workspace.Edge::subtype).toList());
    }

    private static void write(Path dir, String name, String json) throws Exception {
        Files.writeString(dir.resolve(name), json);
    }
}
