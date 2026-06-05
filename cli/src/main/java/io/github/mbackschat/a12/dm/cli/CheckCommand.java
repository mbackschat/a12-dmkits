package io.github.mbackschat.a12.dm.cli;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import io.github.mbackschat.a12.dm.rulekit.validate.Diagnostic;
import io.github.mbackschat.a12.dm.rulekit.validate.DraftRule;
import io.github.mbackschat.a12.dm.rulekit.validate.RuleValidator;
import io.github.mbackschat.a12.dm.rulekit.validate.ValidationResult;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

/**
 * The {@code check} verb (CLI-SPEC §4) — the authoring feedback loop: submit a candidate rule
 * (condition + error field + code, in a group) and get the real kernel's verdict + diagnostics, with
 * no need to persist anything. Wraps core {@link RuleValidator#check}; on a kernel-valid candidate it
 * also runs the best-effort {@link LintBackstop} so the silent inverted-polarity / unguarded-number
 * traps surface in the diagnostics here too, not only on {@code add}.
 */
@Command(name = "check", mixinStandardHelpOptions = true,
        description = "Validate a candidate rule against the real kernel (+ lint), without persisting.")
final class CheckCommand extends BaseCommand {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Parameters(index = "0", paramLabel = "<model.json>", description = "the DM-JSON model file")
    String model;

    @Option(names = "--field", required = true, paramLabel = "<absolutePath>",
            description = "the error field's absolute path, e.g. /Group/Field")
    String field;

    @Option(names = "--condition", required = true, paramLabel = "<DSL>",
            description = "the candidate condition in A12 DSL (true on a violation)")
    String condition;

    @Option(names = "--code", required = true, paramLabel = "<id>", description = "the rule's error code")
    String code;

    @Option(names = "--group", paramLabel = "<path>",
            description = "the rule's iteration scope; defaults to the field's parent group")
    String group;

    @Option(names = "--severity", defaultValue = "ERROR", paramLabel = "ERROR|WARNING",
            description = "rule severity (default: ${DEFAULT-VALUE})")
    String severity;

    @Option(names = "--name", defaultValue = "CheckRule", paramLabel = "<name>",
            description = "candidate rule name (default: ${DEFAULT-VALUE})")
    String name;

    @Override
    public Integer call() {
        String effectiveGroup = group;
        if (effectiveGroup == null) {
            int slash = field.lastIndexOf('/');
            if (slash <= 0) {
                return fail("--field must be an absolute path (e.g. /Group/Field)");
            }
            effectiveGroup = field.substring(0, slash);
        }
        if (!field.startsWith(effectiveGroup + "/")) {
            return fail("--field " + field + " must be inside --group " + effectiveGroup);
        }
        // The kernel's error-entity relpath is fork-9 group-relative: "../" + path below the group.
        String errorEntityRelPath = "../" + field.substring(effectiveGroup.length() + 1);

        String json;
        try {
            json = Files.readString(Path.of(model));
        } catch (IOException e) {
            return fail("cannot read model: " + e.getMessage());
        }
        try {
            DraftRule draft = new DraftRule(effectiveGroup, name, errorEntityRelPath, condition, code, severity);
            ValidationResult result = new RuleValidator().check(json, draft);
            List<Diagnostic> all = new ArrayList<>(result.diagnostics());
            if (result.ok()) {
                all.addAll(LintBackstop.lintCandidate(json, effectiveGroup, name, errorEntityRelPath,
                        code, condition, severity, field));
            }
            ValidationResult merged = ValidationResult.of(all);
            ObjectNode root = MAPPER.createObjectNode();
            root.put("valid", merged.ok());
            root.set("diagnostics", MAPPER.readTree(merged.toJson()));
            out().print(pretty(root));
            return merged.ok() ? 0 : 1;
        } catch (IOException e) {
            return fail("check failed: " + e.getMessage());
        } catch (RuntimeException e) {
            ObjectNode root = MAPPER.createObjectNode();
            root.put("valid", false);
            root.putArray("diagnostics").addObject()
                    .put("severity", "ERROR").put("source", "KERNEL")
                    .put("summary", String.valueOf(e.getMessage()));
            out().print(pretty(root));
            return 1;
        }
    }

    private static String pretty(ObjectNode root) {
        try {
            return MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(root) + "\n";
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("cannot serialize check result", e);
        }
    }
}
