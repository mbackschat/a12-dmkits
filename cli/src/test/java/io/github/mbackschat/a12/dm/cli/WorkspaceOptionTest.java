package io.github.mbackschat.a12.dm.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * The workspace-resolution precedence (the auto-resolving workspace root): the {@code -w/--workspace}
 * root(s) win, then {@code $DMTOOL_WORKSPACE}, then the model's own directory. Tested as a pure function so
 * every branch — env included — is locked (in-process tests can't set real env, so it's exercised by value).
 */
class WorkspaceOptionTest {

    @Test
    void workspaceRootsWinOverEnvAndModelDir() {
        assertEquals(List.of(Path.of("/w")),
                WorkspaceOption.roots(List.of(Path.of("/w")), "/e", "/m/model.json"));
    }

    @Test
    void multipleWorkspaceRootsArePreserved() {
        assertEquals(List.of(Path.of("/a"), Path.of("/b")),
                WorkspaceOption.roots(List.of(Path.of("/a"), Path.of("/b")), "/e", "/m/x.json"));
    }

    @Test
    void envIsUsedWhenNoWorkspaceRoot() {
        assertEquals(List.of(Path.of("/e")),
                WorkspaceOption.roots(List.of(), "/e", "/m/model.json"));
    }

    @Test
    void blankEnvFallsThroughToTheModelsOwnDirectory() {
        assertEquals(List.of(Path.of("/m/sub")),
                WorkspaceOption.roots(List.of(), "   ", "/m/sub/model.json"));
    }

    @Test
    void defaultsToTheModelsOwnDirectory() {
        assertEquals(List.of(Path.of("/m/sub")),
                WorkspaceOption.roots(List.of(), null, "/m/sub/model.json"));
    }
}
