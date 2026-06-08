package io.github.mbackschat.a12.dm.cli;

import io.github.mbackschat.a12.dm.rulekit.dsl.RenderedDiff;
import picocli.CommandLine.Command;

/**
 * The {@code diff} verb (CLI-SPEC §4) — <b>preview only</b> for the F8 modify-by-re-express loop:
 * re-express an existing rule's condition ({@code --rule --condition}) or a whole computation
 * ({@code --computation --spec}) and show the <b>before/after</b> (with lossiness disclosed), <em>without
 * writing anything</em>. Use {@code diff} to inspect a candidate change; use {@link ModifyCommand}
 * ({@code modify}) to actually write it back. All the re-express/validate logic is shared in
 * {@link ReExpressCommand}; this verb's only job is to render the diff and stop.
 */
@Command(name = "diff", mixinStandardHelpOptions = true,
        description = "PREVIEW re-expressing a rule (--rule --condition) or computation (--computation --spec): "
                + "show the before/after diff (F8), read-only. To WRITE the change, use `modify`.")
final class DiffCommand extends ReExpressCommand {

    @Override
    protected Integer emit(RenderedDiff diff, String modifiedJson, String elementId) {
        return printDiff(diff);   // preview only — diff never writes (see `modify` to persist)
    }
}
