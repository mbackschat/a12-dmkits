package io.github.mbackschat.a12.dm.cli.model;

import io.github.mbackschat.a12.dm.cli.BaseCommand;
import io.github.mbackschat.a12.dm.cli.ParamKind;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft;
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel;

import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

/**
 * {@code dmtool model new} — create a new, empty, kernel-valid document model <b>from scratch</b>. This is the
 * CLI's one model-<em>originating</em> verb: every other verb edits an existing {@code -m} model, so without
 * this an agent has no first step. It builds the skeleton (an {@code id}, a {@code locale}, and one empty
 * root group) via {@link DocumentModelDraft#named}, <b>kernel-checks it</b> (what we emit must be accepted,
 * not merely plausible — the project thesis), and prints the model JSON to stdout, or writes it to {@code -o}.
 * The agent's next step is {@code -m <that file> field add …} to populate it.
 *
 * <p>Like {@code export}, it produces a model <em>artifact</em> rather than the {@code result} envelope, so it
 * is classified in {@code SchemaCommand.CONTRACT_EXEMPT} (no directional contract).
 */
@Command(name = "new", mixinStandardHelpOptions = true,
        description = "Create a new empty kernel-valid model from scratch (--id + --locale + a root group); "
                + "prints the model JSON to stdout, or -o writes it to a file.")
final class ModelNewCommand extends BaseCommand {

    @ParamKind(ParamKind.Kind.NEW_NAME)
    @Option(names = "--id", required = true, paramLabel = "<id>",
            description = "the new model's header id you choose (e.g. orders)")
    String id;

    @ParamKind(ParamKind.Kind.FREEFORM)
    @Option(names = "--locale", required = true, paramLabel = "<locale>",
            description = "the condition + default locale, e.g. en_US or de_DE")
    String locale;

    @ParamKind(ParamKind.Kind.NEW_NAME)
    @Option(names = "--root", paramLabel = "<name>",
            description = "the root group's name you choose (default: the id, capitalized)")
    String root;

    @Option(names = {"--output", "-o"}, paramLabel = "<file>",
            description = "write the model here instead of printing it to stdout")
    String outputPath;

    @Override
    public Integer call() {
        String rootName = (root != null && !root.isBlank()) ? root : capitalize(id);
        String json;
        try {
            json = DocumentModelDraft.named(id).locale(locale).rootGroup(rootName, g -> { }).build().toJson();
            BoundModel.ofJson(json); // kernel-check the skeleton — a bad id/locale fails here, not at the agent's next edit
        } catch (RuntimeException e) {
            return fail("cannot create a valid model from --id '" + id + "' --locale '" + locale + "': " + e.getMessage());
        }
        if (outputPath != null) {
            try {
                Files.writeString(Path.of(outputPath), json);
            } catch (IOException e) {
                return fail("cannot write output: " + e.getMessage());
            }
            return 0;
        }
        out().print(json);
        return 0;
    }

    private static String capitalize(String s) {
        return (s == null || s.isEmpty()) ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }
}
