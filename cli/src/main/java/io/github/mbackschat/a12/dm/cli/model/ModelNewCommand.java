package io.github.mbackschat.a12.dm.cli.model;

import io.github.mbackschat.a12.dm.cli.BaseCommand;
import io.github.mbackschat.a12.dm.cli.ConsistencyEvidence;
import io.github.mbackschat.a12.dm.cli.ParamKind;
import io.github.mbackschat.a12.dm.cli.ResultEnvelope;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft;
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel;

import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

/**
 * {@code dmtool model new} — create a new, empty, kernel-valid document model <b>from scratch</b>. This is the
 * CLI's one model-<em>originating</em> verb: every other verb edits an existing {@code -m} model, so without
 * this an agent has no first step. It builds the skeleton (an {@code id}, a {@code locale}, and one empty
 * root group) via {@link DocumentModelDraft#named}, <b>kernel-checks it</b> (what we emit must be accepted,
 * not merely plausible — the project thesis), and prints the model JSON to stdout. With {@code -o} the model
 * goes to the file and stdout instead carries a machine-confirmable WRITE envelope (outcome:applied,
 * written:true, output:&lt;path&gt;) — so the one model-originating verb still gives an agent a success signal.
 * The agent's next step is {@code -m <that file> field add …} to populate it.
 *
 * <p>To stdout (no {@code -o}) it produces a model <em>artifact</em> rather than the {@code result} envelope,
 * so it stays classified in {@code SchemaCommand.CONTRACT_EXEMPT} (no directional contract on its primary,
 * artifact-emitting form); the {@code -o} confirmation envelope is an additive success signal, not a contract.
 */
@Command(name = "new", mixinStandardHelpOptions = true,
        description = "Create a new empty kernel-valid model from scratch (--id + --locale + a root group); "
                + "prints the model JSON to stdout, or -o writes it to a file.")
final class ModelNewCommand extends BaseCommand {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @ParamKind(ParamKind.Kind.NEW_NAME)
    @Option(names = "--id", required = true, paramLabel = "<id>",
            description = "the new model's header id you choose (e.g. Order_DM). A12 Tools requires the "
                    + "model's FILE BASENAME to equal this id and does not strip the suffix — so name the "
                    + "file to match: `--id Order_DM -o Order_DM.json` (the convention is `<Name>_DM` for a "
                    + "document model, `<Name>_TDM` for a type-definition model).")
    String id;

    @ParamKind(ParamKind.Kind.FREEFORM)
    @Option(names = "--locale", required = true, paramLabel = "<locale>",
            description = "the model's display locale — governs labels + messages, e.g. en_US or de_DE. "
                    + "Condition KEYWORDS are always English (FieldFilled/And/…) regardless of this; change "
                    + "that rare default with `config modify --condition-language`.")
    String locale;

    @ParamKind(ParamKind.Kind.NEW_NAME)
    @Option(names = "--root", paramLabel = "<name>",
            description = "the root group's name you choose (default: the id, capitalized)")
    String root;

    @Option(names = {"--output", "-o"}, paramLabel = "<file>",
            description = "write the model here instead of printing it to stdout; A12 Tools expects its "
                    + "basename to equal --id (e.g. Order_DM.json for --id Order_DM)")
    String outputPath;

    @Override
    public Integer call() {
        String rootName = (root != null && !root.isBlank()) ? root : capitalize(id);
        String json;
        BoundModel skeleton;
        try {
            json = DocumentModelDraft.named(id).locale(locale).rootGroup(rootName, g -> { }).build().toJson();
            // kernel-check the skeleton — a bad id/locale fails here, not at the agent's next edit
            skeleton = BoundModel.ofJson(json);
        } catch (RuntimeException e) {
            return fail("cannot create a valid model from --id '" + id + "' --locale '" + locale + "': " + e.getMessage());
        }
        if (outputPath != null) {
            try {
                Files.writeString(Path.of(outputPath), json);
            } catch (IOException e) {
                return fail("cannot write output: " + e.getMessage());
            }
            // -o writes the model to a file, so the model JSON does NOT go to stdout — emit a machine-confirmable
            // WRITE envelope instead (outcome:applied, written:true, output:<path>), so an agent can verify the
            // create succeeded without inspecting the file (the one mutating path that had no success signal).
            ObjectNode changed = MAPPER.createObjectNode();
            changed.put("id", id);
            changed.put("root", rootName);
            changed.put("locale", locale);
            ResultEnvelope env = ResultEnvelope.applied("model", "new", "created model " + id, changed, outputPath,
                    ConsistencyEvidence.of(skeleton));
            out().print(env.toJsonString());
            return env.exitCode();
        }
        out().print(json);
        return 0;
    }

    private static String capitalize(String s) {
        return (s == null || s.isEmpty()) ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }
}
