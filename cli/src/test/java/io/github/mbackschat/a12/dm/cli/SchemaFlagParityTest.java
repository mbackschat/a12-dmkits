package io.github.mbackschat.a12.dm.cli;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

import picocli.CommandLine;
import picocli.CommandLine.Model.OptionSpec;

/**
 * Anti-rot guard #1 (the schema↔command seam): a served I/O schema must not name a CLI flag the live command
 * doesn't have. {@code schema <target> <op>} serves hand-authored schema resources whose descriptive prose is
 * <em>not</em> derived from the tree, so a flag mentioned there can silently outlive the command — exactly how
 * the stale {@code rule read --rule/--all} survived: those modes were refactored to a positional path plus a
 * separate {@code model read}, but {@code read.schema.json} kept describing the old flags and {@code schema
 * rule read} served the lie. This closes the seam that the executable-examples no-rot lever (input schemas
 * only) and the verb-path ref-resolver ({@link CommandReferencesResolveTest}, verbs not flags) both miss:
 * every {@code --flag} in a served schema must be a real option of (one of) the verb(s) that schema documents,
 * derived live from the picocli tree.
 */
class SchemaFlagParityTest {

    private static final Pattern LONG_FLAG = Pattern.compile("--[a-z][a-z0-9-]*");

    @Test
    void everyServedSchemaNamesOnlyRealFlagsOfItsVerb() {
        CommandLine root = new CommandLine(new RootCommand());

        // schemaName -> the verbs that serve it (a schema may be shared, e.g. "add" by rule/computation add).
        Map<String, Set<String>> schemaVerbs = new HashMap<>();
        SchemaCommand.INPUT_OPS.forEach((v, s) -> schemaVerbs.computeIfAbsent(s, k -> new HashSet<>()).add(v));
        SchemaCommand.DOC_INPUT.forEach((v, s) -> schemaVerbs.computeIfAbsent(s, k -> new HashSet<>()).add(v));
        SchemaCommand.DATA_SCHEMA.forEach((v, s) -> schemaVerbs.computeIfAbsent(s, k -> new HashSet<>()).add(v));

        List<String> violations = new ArrayList<>();
        schemaVerbs.forEach((schema, verbs) -> {
            String text = resource("/schema/" + schema + ".schema.json");
            if (text == null) {
                violations.add("served schema resource missing: " + schema);
                return;
            }
            Set<String> allowed = new HashSet<>();
            for (String verb : verbs) {
                CommandLine leaf = leaf(root, verb);
                if (leaf == null) {
                    violations.add("served verb does not resolve in the live tree: " + verb);
                    continue;
                }
                allowed.addAll(longFlags(leaf));
            }
            Set<String> reported = new HashSet<>();
            Matcher m = LONG_FLAG.matcher(text);
            while (m.find()) {
                String flag = m.group();
                if (reported.add(flag) && !allowed.contains(flag)) {
                    violations.add(schema + ".schema.json names `" + flag + "` — not an option of " + verbs
                            + " (real long flags: " + new TreeSet<>(allowed) + ")");
                }
            }
        });

        assertTrue(violations.isEmpty(),
                () -> "served schemas naming flags the live command lacks:\n  " + String.join("\n  ", violations));
    }

    /** Does-fire: the parity check must FAIL on a phantom flag — a guard that only ever passes is worthless. */
    @Test
    void theGuardFiresOnAPhantomFlag() {
        CommandLine root = new CommandLine(new RootCommand());
        Set<String> allowed = longFlags(leaf(root, "rule read"));
        assertFalse(allowed.contains("--all"), "precondition: `rule read` has no --all option");
        String phantomSchema = "{ \"description\": \"With --rule: one rule; with --all: the whole model.\" }";
        boolean caught = false;
        Matcher m = LONG_FLAG.matcher(phantomSchema);
        while (m.find()) {
            if (!allowed.contains(m.group())) {
                caught = true;
            }
        }
        assertTrue(caught, "the parity extraction must flag a phantom `--all`/`--rule`");
    }

    /** Every {@code --long} flag of a command and its ancestors (so inherited/mixin globals are allowed). */
    private static Set<String> longFlags(CommandLine leaf) {
        Set<String> out = new HashSet<>();
        for (CommandLine c = leaf; c != null; c = c.getParent()) {
            for (OptionSpec o : c.getCommandSpec().options()) {
                for (String n : o.names()) {
                    if (n.startsWith("--")) {
                        out.add(n);
                    }
                }
            }
        }
        return out;
    }

    private static CommandLine leaf(CommandLine root, String verb) {
        CommandLine node = root;
        for (String token : verb.split(" ")) {
            node = node.getSubcommands().get(token);
            if (node == null) {
                return null;
            }
        }
        return node;
    }

    private static String resource(String path) {
        try (InputStream in = SchemaFlagParityTest.class.getResourceAsStream(path)) {
            return in == null ? null : new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
