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
 * No-drift guard for the plugin's <b>binary download pin</b>. The agent plugins fetch the native binary
 * <b>on demand</b> — the bundled skill runs {@code ensure-dmtool.sh} (canonical at {@code scripts/}, copy-synced
 * into each plugin's skill dir) the first time {@code dmtool} is needed. There is no session hook. The
 * installer's {@code VERSION="vX.Y.Z"} names the GitHub release tag to download from, and must equal the
 * <b>project</b> version (single-sourced in {@code build.gradle} {@code version = '…'}): the plugin shipped
 * at version X delivers the X binary, which targets the kernel X was built against.
 *
 * <p>This invariant silently broke once — {@code bump-version.sh} bumped {@code build.gradle} + the plugin
 * manifests release after release while the installer stayed pinned to {@code v0.1.0}, so every install through
 * v0.3.1 downloaded the <i>first</i> binary (predating the §3.4 native-reflection fix), and a long-fixed
 * native-image crash kept reaching users. This guard fails the build the instant the pin lags the project
 * version, so the stale-binary class can't recur. (Companion to {@link VersionMentionFreshnessTest}, which
 * guards the kernel pin in prose; this guards the project pin in the downloader.)
 */
class PluginInstallerVersionTest {

    private static final Checkout CHECKOUT = Checkout.locate();

    /** The canonical installer and its two copy-synced plugin instances — all three carry the download pin. */
    private static final List<String> INSTALLER_FILES = List.of(
            "scripts/ensure-dmtool.sh",
            "plugin/skills/a12-dmtool/ensure-dmtool.sh",
            "plugin-codex/skills/a12-dmtool/ensure-dmtool.sh");

    private static final Pattern INSTALLER_VERSION = Pattern.compile("^VERSION=\"v([^\"]+)\"", Pattern.MULTILINE);

    @Test
    void everyInstallerDownloadPinMatchesTheProjectVersion() throws IOException {
        String project = projectVersion();
        for (String relative : INSTALLER_FILES) {
            String text = Files.readString(CHECKOUT.requireFile(relative));
            Matcher m = INSTALLER_VERSION.matcher(text);
            if (!m.find()) {
                fail(relative + " must declare VERSION=\"vX.Y.Z\" (the release tag to download the binary from)");
            }
            String pin = m.group(1);
            if (!pin.equals(project)) {
                fail(relative + " downloads the v" + pin + " binary but the project is v" + project
                        + " — the plugin would ship a stale binary. Run ./scripts/bump-version.sh " + project
                        + " (it bumps the installer pin and re-syncs the copies), or sync by hand and re-run "
                        + "./scripts/sync-plugin-assets.sh.");
            }
        }
    }

    /** Read {@code version = 'X.Y.Z'} from build.gradle — the project's single source of truth. */
    private static String projectVersion() throws IOException {
        String build = Files.readString(CHECKOUT.requireFile("build.gradle"));
        Matcher m = Pattern.compile("^version = '([^']+)'", Pattern.MULTILINE).matcher(build);
        assertTrue(m.find(), "build.gradle must declare version = '…'");
        return m.group(1);
    }

    /** Locate a repo-root file regardless of the module working dir (mirrors {@link VersionMentionFreshnessTest}). */}
