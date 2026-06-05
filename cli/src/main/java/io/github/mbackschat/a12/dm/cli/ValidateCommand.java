package io.github.mbackschat.a12.dm.cli;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.mgmtp.a12.model.notification.RankedNotification;

import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel;
import io.github.mbackschat.a12.dm.core.ModelConsistencyException;
import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Unmatched;

/**
 * The {@code validate} verb (CLI-SPEC §4) — runs the model through the real kernel (the F4
 * ground-truth step) and reports consistency as JSON: {@code {"valid":bool,"diagnostics":[...]}},
 * exit 0 if valid, 1 if not. Model-level today; rule-level validation arrives with the JSON rule-spec.
 */
@Command(name = "validate", mixinStandardHelpOptions = true,
        description = "Run the model through the real kernel; report {valid, diagnostics[]} (exit 0 valid, 1 not).")
final class ValidateCommand extends BaseCommand {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Pattern MVK = Pattern.compile("MVK_[A-Z0-9_]+");

    @Parameters(index = "0", paramLabel = "<model.json>",
            description = "the DM-JSON model file (re-checks its existing rules)")
    String model;

    @Unmatched
    String[] extra = new String[0];

    @Override
    public Integer call() {
        if (extra.length > 0) {
            // don't silently ignore extra args (N6 baseline finding) — point at the right verb
            return fail("validate takes only <model.json> (it re-checks the model's existing rules). "
                    + "To validate a candidate rule, use: check <model.json> --group … --field … --condition … --code …");
        }
        String json;
        try {
            json = Files.readString(Path.of(model));
        } catch (IOException e) {
            return fail("cannot read model: " + e.getMessage());
        }

        ObjectNode root = MAPPER.createObjectNode();
        ArrayNode diagnostics = MAPPER.createArrayNode();
        try {
            BoundModel.ofJson(json);
            root.put("valid", true);
            root.set("diagnostics", diagnostics);
            out().print(pretty(root));
            return 0;
        } catch (ModelConsistencyException e) {
            for (RankedNotification n : e.notifications()) {
                diagnostics.add(diagnostic(n.getSeverity().name(), n.getMessage()));
            }
            return invalid(root, diagnostics);
        } catch (RuntimeException e) {
            diagnostics.add(diagnostic("ERROR", String.valueOf(e.getMessage())));
            return invalid(root, diagnostics);
        }
    }

    private int invalid(ObjectNode root, ArrayNode diagnostics) {
        root.put("valid", false);
        root.set("diagnostics", diagnostics);
        out().print(pretty(root));
        return 1;
    }

    private static ObjectNode diagnostic(String severity, String message) {
        ObjectNode d = MAPPER.createObjectNode();
        d.put("severity", severity);
        d.put("source", "KERNEL");
        Matcher m = MVK.matcher(message == null ? "" : message);
        if (m.find()) {
            d.put("code", m.group());
        } else {
            d.putNull("code");
        }
        d.put("summary", message);
        return d;
    }

    private static String pretty(ObjectNode root) {
        try {
            return MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(root) + "\n";
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException("cannot serialize validation result", e);
        }
    }
}
