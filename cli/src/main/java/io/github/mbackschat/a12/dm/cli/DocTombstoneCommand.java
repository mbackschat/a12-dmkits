package io.github.mbackschat.a12.dm.cli;

import java.util.ArrayList;
import java.util.List;

import picocli.CommandLine.Command;
import picocli.CommandLine.Unmatched;

/**
 * Tombstone for the renamed {@code doc} target (CLI-SPEC §5). The whole-document target was renamed to
 * {@code model} (the document <b>model</b> — vs {@code --doc}, the document <b>instance</b>), so the two
 * layers no longer share a word. This is a deliberate <b>fail-fast clean break, not a silent alias</b>:
 * {@code doc …} does <em>not</em> run; it exits non-zero with a corrective message naming the replacement,
 * so a stale caller is <em>revealed and fixed</em> rather than silently served. Hidden from the
 * manifest/{@code --help} (the canonical surface is {@code model}); removable after one release.
 *
 * <p>{@code Cli.run} marks this subcommand's unmatched arguments as allowed, so any trailing op/flags
 * ({@code doc eval --doc …}) are captured into {@link #rest} and echoed back as the corrected command
 * rather than triggering a generic parse error.
 */
@Command(name = "doc", hidden = true, description = "(removed — the whole-model target was renamed to `model`)")
final class DocTombstoneCommand extends BaseCommand {

    @Unmatched
    List<String> rest = new ArrayList<>();

    @Override
    public Integer call() {
        String tail = rest.isEmpty() ? "" : " " + String.join(" ", rest);
        return fail("the `doc` target was renamed to `model` (the document MODEL — vs `--doc`, the data "
                + "instance). Rerun with `model" + tail + "`.");
    }
}
