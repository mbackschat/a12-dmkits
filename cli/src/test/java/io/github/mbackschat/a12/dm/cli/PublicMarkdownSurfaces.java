package io.github.mbackschat.a12.dm.cli;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/** The live Markdown denominator served to users, agents, and maintainers. */
final class PublicMarkdownSurfaces {

    private PublicMarkdownSurfaces() { }

    static List<Path> live() throws Exception {
        List<Path> surfaces = new ArrayList<>(List.of(
                Path.of("../README.md"),
                Path.of("../plugin/README.md"),
                Path.of("../plugin-codex/AGENTS.md"),
                Path.of("../skill/SKILL.md")));
        addMarkdown(surfaces, Path.of("../examples"), null);
        // Archived docs preserve superseded claims deliberately; current product/design docs are live.
        addMarkdown(surfaces, Path.of("../docs"), Path.of("../docs/archived"));
        return surfaces.stream().sorted(Comparator.comparing(Path::toString)).toList();
    }

    private static void addMarkdown(List<Path> surfaces, Path root, Path excluded) throws Exception {
        try (Stream<Path> paths = Files.walk(root)) {
            paths.filter(Files::isRegularFile)
                    .filter(path -> path.toString().endsWith(".md"))
                    .filter(path -> excluded == null || !path.startsWith(excluded))
                    .forEach(surfaces::add);
        }
    }
}
