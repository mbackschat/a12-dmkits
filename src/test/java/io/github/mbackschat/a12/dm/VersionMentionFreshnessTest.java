package io.github.mbackschat.a12.dm;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

/**
 * No-drift guard for the version strings woven into prose / plugin metadata. The kernel pin and the A12 Tools
 * distribution label are single-sourced in {@code build.gradle} ({@code ext.kernelVersion} / {@code ext.a12Distribution})
 * and surfaced at runtime by the {@code --version} / {@code manifest.version} axes ({@code VersionInfo}). But the
 * READMEs, plugin manifests, and CHANGELOG state them in hand-written text that no compiler checks — exactly the kind
 * of value that goes stale on a kernel bump. This test reads the pins from {@code build.gradle} and fails the build if
 * any of those files no longer mentions both, so the prose can't lag the pin (the CLAUDE.md no-drift discipline,
 * mirroring {@code CliTourFreshnessTest} / {@code DocSnippetFreshnessTest}).
 */
class VersionMentionFreshnessTest {

    private static final Checkout CHECKOUT = Checkout.locate();

    /** Files that state the targeted versions in hand-written text and must track the build pins. */
    private static final List<String> VERSION_MENTIONING_FILES = List.of(
            "README.md",
            "plugin/README.md",
            "plugin/CHANGELOG.md",
            "plugin/.claude-plugin/plugin.json",
            "plugin-codex/.codex-plugin/plugin.json",
            // The public interpreter README states the semantics target its differential is run against, so a
            // kernel bump must not leave the published mirror advertising the previous kernel.
            "interpreter/public-release/README.md");

    @Test
    void everyVersionMentionMatchesTheBuildPins() throws IOException {
        String build = Files.readString(CHECKOUT.requireFile("build.gradle"));
        String kernel = pin(build, "kernelVersion");
        String a12 = pin(build, "a12Distribution");

        for (String relative : VERSION_MENTIONING_FILES) {
            String text = Files.readString(CHECKOUT.requireFile(relative));
            if (!text.contains(kernel)) {
                fail(relative + " must mention the pinned kernel version " + kernel
                        + " (single-sourced in build.gradle ext.kernelVersion) — it drifted");
            }
            if (!text.contains(a12)) {
                fail(relative + " must mention the pinned A12 Tools distribution " + a12
                        + " (single-sourced in build.gradle ext.a12Distribution) — it drifted");
            }
        }
    }

    /** Read {@code ext.<name> = '<value>'} from build.gradle. */
    private static String pin(String build, String name) {
        Matcher m = Pattern.compile("ext\\." + Pattern.quote(name) + "\\s*=\\s*'([^']+)'").matcher(build);
        assertTrue(m.find(), () -> "build.gradle must declare ext." + name);
        return m.group(1);
    }

    /** Locate a repo-root file regardless of the module working dir (mirrors {@code DocSnippetFreshnessTest}). */}
