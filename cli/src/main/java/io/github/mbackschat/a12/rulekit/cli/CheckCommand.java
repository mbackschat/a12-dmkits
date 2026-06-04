package io.github.mbackschat.a12.rulekit.cli;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import io.github.mbackschat.a12.rulekit.validate.DraftRule;
import io.github.mbackschat.a12.rulekit.validate.RuleValidator;
import io.github.mbackschat.a12.rulekit.validate.ValidationResult;

/**
 * The {@code check} verb (CLI-SPEC §4) — the authoring feedback loop: submit a candidate rule
 * (condition + error field + code, in a group) and get the real kernel's verdict + diagnostics, with
 * no need to persist anything. This is what lets a BA's agent author *and self-verify* a rule. Thin
 * wrapper over core {@link RuleValidator#check}.
 *
 * <p>Usage: {@code check <model.json> --group <path> --field <relPath> --condition "<DSL>" --code <id>
 * [--severity ERROR|WARNING] [--name <name>]}.
 */
final class CheckCommand {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String USAGE =
            "usage: check <model.json> --field <absolutePath> --condition \"<DSL>\" --code <id>"
            + " [--group <path>] [--severity ERROR|WARNING] [--name <name>]"
            + "\n  --field is the error field's absolute path; --group (the rule's iteration scope)"
            + " defaults to the field's parent group.";

    private CheckCommand() {
    }

    static int run(String[] args, Appendable out, Appendable err) {
        if (args.length == 0) {
            return fail(err, USAGE);
        }
        String model = args[0];
        String group = null;
        String field = null;
        String condition = null;
        String code = null;
        String severity = "ERROR";
        String name = "CheckRule";

        for (int i = 1; i < args.length; i++) {
            String flag = args[i];
            if (i + 1 >= args.length) {
                return fail(err, flag + " needs a value\n" + USAGE);
            }
            String value = args[++i];
            switch (flag) {
                case "--group" -> group = value;
                case "--field" -> field = value;
                case "--condition" -> condition = value;
                case "--code" -> code = value;
                case "--severity" -> severity = value;
                case "--name" -> name = value;
                default -> {
                    return fail(err, "unknown option: " + flag + "\n" + USAGE);
                }
            }
        }
        String missing = firstMissing(field, condition, code);
        if (missing != null) {
            return fail(err, "missing required option: " + missing + "\n" + USAGE);
        }
        // The rule's group defaults to the field's parent (the "rule on this field" case).
        if (group == null) {
            int slash = field.lastIndexOf('/');
            if (slash <= 0) {
                return fail(err, "--field must be an absolute path (e.g. /Group/Field)\n" + USAGE);
            }
            group = field.substring(0, slash);
        }
        if (!field.startsWith(group + "/")) {
            return fail(err, "--field " + field + " must be inside --group " + group + "\n" + USAGE);
        }
        // The kernel's error-entity relpath is fork-9 group-relative: "../" + path below the group.
        String errorEntityRelPath = "../" + field.substring(group.length() + 1);

        String json;
        try {
            json = Files.readString(Path.of(model));
        } catch (IOException e) {
            return fail(err, "cannot read model: " + e.getMessage());
        }
        try {
            DraftRule draft = new DraftRule(group, name, errorEntityRelPath, condition, code, severity);
            ValidationResult result = new RuleValidator().check(json, draft);
            ObjectNode root = MAPPER.createObjectNode();
            root.put("valid", result.ok());
            root.set("diagnostics", MAPPER.readTree(result.toJson()));
            append(out, pretty(root));
            return result.ok() ? 0 : 1;
        } catch (IOException e) {
            return fail(err, "check failed: " + e.getMessage());
        } catch (RuntimeException e) {
            // a candidate so malformed it can't even be injected — still "not valid"
            ObjectNode root = MAPPER.createObjectNode();
            root.put("valid", false);
            root.putArray("diagnostics").addObject()
                    .put("severity", "ERROR").put("source", "KERNEL")
                    .put("summary", String.valueOf(e.getMessage()));
            append(out, pretty(root));
            return 1;
        }
    }

    private static String firstMissing(String field, String condition, String code) {
        if (field == null) {
            return "--field";
        }
        if (condition == null) {
            return "--condition";
        }
        if (code == null) {
            return "--code";
        }
        return null;
    }

    private static String pretty(ObjectNode root) {
        try {
            return MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(root) + "\n";
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("cannot serialize check result", e);
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
