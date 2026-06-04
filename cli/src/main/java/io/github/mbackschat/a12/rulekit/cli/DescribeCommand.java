package io.github.mbackschat.a12.rulekit.cli;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import io.github.mbackschat.a12.rulekit.context.ModelContext;
import io.github.mbackschat.a12.rulekit.dsl.BoundModel;

/**
 * The {@code describe} verb (CLI-SPEC §4) — emits the F9a model card as JSON, so an agent can orient
 * on a model (fields + kinds + enum domains + group repeatability) without reading raw DM-JSON.
 *
 * <p>Usage: {@code describe <model.json>}.
 */
final class DescribeCommand {

    private DescribeCommand() {
    }

    static int run(String[] args, Appendable out, Appendable err) {
        if (args.length == 0) {
            return fail(err, "usage: describe <model.json>");
        }
        try {
            BoundModel model = BoundModel.ofJson(Files.readString(Path.of(args[0])));
            append(out, new ModelContext(model).describe().toJson());
            append(out, "\n");
            return 0;
        } catch (IOException e) {
            return fail(err, "cannot read model: " + e.getMessage());
        } catch (RuntimeException e) {
            return fail(err, "describe failed: " + e.getMessage());
        }
    }

    private static void append(Appendable out, String text) {
        try {
            out.append(text);
        } catch (IOException e) {
            throw new IllegalStateException("cannot write output", e);
        }
    }

    private static int fail(Appendable err, String message) {
        try {
            err.append(message).append('\n');
        } catch (IOException ignored) {
            // best-effort diagnostic
        }
        return 2;
    }
}
