package io.github.mbackschat.a12.dm.cli;

import picocli.CommandLine.IVersionProvider;

/**
 * Feeds {@code dmtool --version} from the live {@link VersionInfo} (CLI-SPEC §1b) — so the human flag and the
 * machine-readable {@code manifest.version} report the same seven version axes from one source.
 */
final class VersionProvider implements IVersionProvider {

    @Override
    public String[] getVersion() {
        return VersionInfo.current().lines();
    }
}
