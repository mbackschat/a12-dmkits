package io.github.mbackschat.a12.dm.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * The workspace-root resolution precedence (the auto-resolving "workspace root"): a per-verb
 * {@code --include-dir} wins, then the {@code -w/--workspace} session root, then {@code $DMTOOL_WORKSPACE},
 * then the model's own directory. Tested as a pure function so every branch — env included — is locked
 * (in-process tests can't set real env, so the env source is exercised here, by value).
 */
class IncludeDirOptionTest {

    @Test
    void explicitIncludeDirWinsOverEverything() {
        assertEquals(List.of(Path.of("/x")),
                IncludeDirOption.roots(List.of(Path.of("/x")), "/w", "/e", "/m/model.json"));
    }

    @Test
    void workspaceFlagBeatsEnvAndModelDir() {
        assertEquals(List.of(Path.of("/w")),
                IncludeDirOption.roots(List.of(), "/w", "/e", "/m/model.json"));
    }

    @Test
    void envIsUsedWhenNoIncludeDirAndNoFlag() {
        assertEquals(List.of(Path.of("/e")),
                IncludeDirOption.roots(List.of(), null, "/e", "/m/model.json"));
    }

    @Test
    void blankFlagOrEnvIsIgnored() {
        assertEquals(List.of(Path.of("/e")),
                IncludeDirOption.roots(List.of(), "   ", "/e", "/m/model.json"));
    }

    @Test
    void defaultsToTheModelsOwnDirectory() {
        assertEquals(List.of(Path.of("/m/sub")),
                IncludeDirOption.roots(List.of(), null, null, "/m/sub/model.json"));
    }
}
