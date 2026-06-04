package io.github.mbackschat.a12.rulekit.cli;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.mgmtp.a12.model.notification.RankedNotification;

import io.github.mbackschat.a12.rulekit.dsl.BoundModel;
import io.github.mbackschat.a12.rulekit.model.ModelConsistencyException;

/**
 * The {@code validate} verb (CLI-SPEC §4) — runs the model through the real kernel (the F4
 * ground-truth step) and reports consistency as JSON: {@code {"valid":bool,"diagnostics":[...]}},
 * exit 0 if valid, 1 if not. Model-level today; rule-level validation arrives with the JSON rule-spec.
 *
 * <p>Usage: {@code validate <model.json>}.
 */
final class ValidateCommand {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Pattern MVK = Pattern.compile("MVK_[A-Z0-9_]+");

    private ValidateCommand() {
    }

    static int run(String[] args, Appendable out, Appendable err) {
        if (args.length == 0) {
            return fail(err, "usage: validate <model.json>");
        }
        if (args.length > 1) {
            // don't silently ignore extra args (N6 baseline finding) — point at the right verb
            return fail(err, "validate takes only <model.json> (it re-checks the model's existing rules). "
                    + "To validate a candidate rule, use: check <model.json> --group … --field … --condition … --code …");
        }
        String json;
        try {
            json = Files.readString(Path.of(args[0]));
        } catch (IOException e) {
            return fail(err, "cannot read model: " + e.getMessage());
        }

        ObjectNode root = MAPPER.createObjectNode();
        ArrayNode diagnostics = MAPPER.createArrayNode();
        try {
            BoundModel.ofJson(json);
            root.put("valid", true);
            root.set("diagnostics", diagnostics);
            append(out, pretty(root));
            return 0;
        } catch (ModelConsistencyException e) {
            for (RankedNotification n : e.notifications()) {
                diagnostics.add(diagnostic(n.getSeverity().name(), n.getMessage()));
            }
            return invalid(out, root, diagnostics);
        } catch (RuntimeException e) {
            // deserialize / expand / parse failure — still "not a valid model"
            diagnostics.add(diagnostic("ERROR", String.valueOf(e.getMessage())));
            return invalid(out, root, diagnostics);
        }
    }

    private static int invalid(Appendable out, ObjectNode root, ArrayNode diagnostics) {
        root.put("valid", false);
        root.set("diagnostics", diagnostics);
        append(out, pretty(root));
        return 1;
    }

    /** One diagnostic: severity, the MVK code if the message carries one, and the message. */
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
