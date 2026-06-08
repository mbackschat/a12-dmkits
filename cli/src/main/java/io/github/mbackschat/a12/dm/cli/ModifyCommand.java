package io.github.mbackschat.a12.dm.cli;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import io.github.mbackschat.a12.dm.rulekit.dsl.RenderedDiff;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

/**
 * The {@code modify} verb (CLI-SPEC §4) — the <b>write</b> end of the F8 modify-by-re-express loop:
 * re-express an existing rule's condition ({@code --rule --condition}) or a whole computation
 * ({@code --computation --spec}), validate it against the kernel, and <b>write the modified model back</b>
 * — <em>in place</em> by default (overwriting {@code <model.json>}), or to {@code -o <file>}. It always
 * prints the before/after diff to stdout (so the change is visible); {@code --dry-run} previews <em>only</em>
 * and writes nothing (the read-only {@code diff} behavior). An invalid re-expression is rejected by the
 * kernel before any write. The re-express/validate/render core is shared in {@link ReExpressCommand}.
 */
@Command(name = "modify", mixinStandardHelpOptions = true,
        description = "Re-express a rule (--rule --condition) or computation (--computation --spec) and WRITE the "
                + "modified model back (in place by default; -o redirects). --dry-run previews only (like `diff`).")
final class ModifyCommand extends ReExpressCommand {

    @Option(names = {"--output", "-o"}, paramLabel = "<file>",
            description = "write the modified model here instead of in place (overwriting <model.json>)")
    String outputPath;

    @Option(names = "--dry-run",
            description = "preview only — print the before/after diff and write NOTHING (the read-only `diff` behavior)")
    boolean dryRun;

    @Override
    protected Integer emit(RenderedDiff diff, String modifiedJson, String elementId) {
        out().print(diff.toJson() + "\n");
        if (dryRun) {
            err().println("(dry run — " + elementId + " not written)");
            return 0;
        }
        String target = outputPath != null ? outputPath : model;
        try {
            Files.writeString(Path.of(target), modifiedJson);
        } catch (IOException e) {
            return fail("cannot write output: " + e.getMessage());
        }
        err().println("modified " + elementId + " → " + target);
        return 0;
    }
}
