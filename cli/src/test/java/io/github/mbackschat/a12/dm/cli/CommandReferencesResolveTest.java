package io.github.mbackschat.a12.dm.cli;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

import picocli.CommandLine;

/**
 * The <b>positive</b> drift guard — the rename-proof companion to {@link NoStaleCommandSyntaxTest}. Where
 * the pattern guard forbids a fixed list of <i>known-bad</i> forms, this one asserts every command example
 * an agent reads <b>resolves to a verb that actually exists</b> in the live picocli tree. So when a verb is
 * renamed/removed (or the tool itself renamed), the stale example fails here — no need to teach a pattern
 * guard the new bad form after the fact.
 *
 * <p>It is derived entirely from the live command tree ({@link Cli#TOOL_NAME} via {@link RootCommand}), so
 * it can't drift: the set of valid verbs and the tool name both recompute from the same model that drives
 * parsing. Two reference shapes are checked across the authored user-facing text ({@code src/main/java}
 * help + diagnostics, the bundled {@code schema/*.json} + {@code verb-examples.json}, the operator catalog):
 *
 * <ul>
 *   <li><b>Full invocations</b> — {@code dmtool [-m <model>] <target> <op> …}: the leading token must be
 *       the live tool name, and the verb path must descend the live tree.</li>
 *   <li><b>Two-token shorthand</b> — a backtick {@code `target op`} whose first token is a real top-level
 *       subcommand (the diagnostic hints, e.g. {@code `rule read`}); the pair must resolve.</li>
 * </ul>
 *
 * <p>The <b>tool-rename trip-wire:</b> the scan keys off the <i>live</i> tool name, so renaming
 * {@link Cli#TOOL_NAME} without updating the example text drops the invocation count to ~0 — the
 * {@link #MIN_INVOCATIONS} floor then fails loudly, pointing at the un-updated examples.
 */
class CommandReferencesResolveTest {

    /** Authored user-facing text: CLI source (help + diagnostics) + bundled schema/catalog resources. */
    private static final List<Path> ROOTS = List.of(
            Path.of("src/main/java"),
            Path.of("src/main/resources"),
            Path.of("../src/main/resources/catalog"));

    /** A path/shorthand segment: a lowercase verb token (targets/ops are all lowercase, hyphens allowed). */
    private static final Pattern BAREWORD = Pattern.compile("[a-z][a-z0-9-]*");
    /** A backtick span (for the two-token shorthand check). */
    private static final Pattern BACKTICK = Pattern.compile("`([^`\\n]+)`");

    /** Sanity floor: the rich help text carries dozens of {@code dmtool …} examples. A rename that leaves
     *  the literal old name in the text makes the live-name scan find ~0 — this floor catches that. */
    private static final int MIN_INVOCATIONS = 20;

    @Test
    void everyCommandReferenceResolvesToALiveVerb() throws IOException {
        CommandLine root = new CommandLine(new RootCommand());
        String toolName = root.getCommandName();
        // a full invocation reference: the tool name, then the rest up to a delimiter (`, }, ", newline).
        Pattern invocation = Pattern.compile("\\b" + Pattern.quote(toolName) + "\\b([^`}\"\\n]*)");

        List<String> violations = new ArrayList<>();
        int[] invocationCount = {0};
        for (Path rootDir : ROOTS) {
            if (!Files.isDirectory(rootDir)) {
                continue;
            }
            try (Stream<Path> files = Files.walk(rootDir)) {
                files.filter(p -> p.toString().endsWith(".java") || p.toString().endsWith(".json"))
                        .forEach(p -> scan(p, root, invocation, violations, invocationCount));
            }
        }

        assertTrue(invocationCount[0] >= MIN_INVOCATIONS,
                () -> "found only " + invocationCount[0] + " `" + toolName + " …` command examples (floor "
                        + MIN_INVOCATIONS + ") — did the tool get renamed without updating the example text?");
        assertTrue(violations.isEmpty(),
                () -> "command examples referencing verbs that don't exist in the live tree:\n"
                        + String.join("\n", violations));
    }

    private static void scan(Path file, CommandLine root, Pattern invocation,
            List<String> violations, int[] invocationCount) {
        List<String> lines;
        try {
            lines = Files.readAllLines(file);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        Map<String, CommandLine> topLevel = root.getSubcommands();
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            int lineNo = i + 1;

            Matcher m = invocation.matcher(line);
            while (m.find()) {
                List<String> tokens = tokenize(m.group(1));
                String problem = resolveInvocation(root, tokens);
                if (problem == PATH_PRESENT) {
                    invocationCount[0]++;
                } else if (problem != null) {
                    violations.add(file + ":" + lineNo + " — `" + (root.getCommandName() + m.group(1)).trim()
                            + "` — " + problem);
                }
            }

            Matcher b = BACKTICK.matcher(line);
            while (b.find()) {
                String[] words = b.group(1).trim().split("\\s+");
                if (words.length == 2 && BAREWORD.matcher(words[0]).matches()
                        && BAREWORD.matcher(words[1]).matches() && topLevel.containsKey(words[0])) {
                    String problem = resolvePath(root, List.of(words), 0);
                    if (problem != null && problem != PATH_PRESENT) {
                        violations.add(file + ":" + lineNo + " — `" + b.group(1) + "` — " + problem);
                    }
                }
            }
        }
    }

    /** Sentinel: a real, fully-resolved verb path was present (counts toward the invocation floor). */
    private static final String PATH_PRESENT = "<resolved>";

    /** Strip leading global options (e.g. {@code -m <model.json>}) before resolving the verb path. */
    private static String resolveInvocation(CommandLine root, List<String> tokens) {
        int i = 0;
        while (i < tokens.size() && tokens.get(i).startsWith("-")) {
            i++;                                                    // the option flag
            if (i < tokens.size() && tokens.get(i).startsWith("<")) {
                i++;                                                // its placeholder value
            }
        }
        return resolvePath(root, tokens, i);
    }

    /**
     * Descend the live tree along the leading barewords from {@code start}. Returns {@code null} when there
     * is no verb path to check (the ref is just the bare tool name or an options-only form), {@link
     * #PATH_PRESENT} when a real path resolved, or a human-readable problem when a bareword that should name
     * a target/op doesn't exist in the tree (the stale-rename case).
     */
    private static String resolvePath(CommandLine root, List<String> tokens, int start) {
        CommandLine node = root;
        CommandLine matched = null;
        String breakToken = null;
        for (int i = start; i < tokens.size(); i++) {
            String t = tokens.get(i);
            if (!BAREWORD.matcher(t).matches()) {
                break;                                              // placeholder/option/bracket — path ended cleanly
            }
            Map<String, CommandLine> subs = node.getSubcommands();
            if (subs.containsKey(t)) {
                matched = subs.get(t);
                node = matched;
            } else {
                breakToken = t;                                     // an unrecognized bareword
                break;
            }
        }
        if (matched == null) {
            // nothing descended: either no path at all (fine) or the very first verb token is bogus (stale).
            return breakToken == null ? null : "unknown verb `" + breakToken + "`";
        }
        if (breakToken != null && !matched.getSubcommands().isEmpty()) {
            // we landed on a target group, and the next bareword should have been one of its ops but isn't.
            return "`" + breakToken + "` is not an op of `" + matched.getCommandName() + "`";
        }
        return PATH_PRESENT;
    }

    private static List<String> tokenize(String rest) {
        List<String> out = new ArrayList<>();
        for (String t : rest.trim().split("\\s+")) {
            if (!t.isEmpty()) {
                out.add(t);
            }
        }
        return out;
    }
}
