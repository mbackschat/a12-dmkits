package io.github.mbackschat.a12.dm.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import picocli.CommandLine;
import picocli.CommandLine.Model.OptionSpec;

/**
 * The <b>positive</b> drift guard — the rename-proof companion to {@link NoStaleCommandSyntaxTest}. Where
 * the pattern guard forbids a fixed list of <i>known-bad</i> forms, this one asserts every command example
 * an agent reads <b>resolves to a verb that actually exists</b> in the live picocli tree. So when a verb is
 * renamed/removed (or the tool itself renamed), the stale example fails here — no need to teach a pattern
 * guard the new bad form after the fact.
 *
 * <p>It is derived entirely from the live command tree ({@link Cli#TOOL_NAME} via {@link RootCommand}), so
 * it can't drift: the set of valid verbs and the tool name both recompute from the same model that drives
 * parsing. Two reference shapes are checked across the authored user-facing text — the in-binary surface
 * ({@code src/main/java} help + diagnostics, the bundled {@code schema/*.json} + {@code verb-examples.json},
 * the operator catalog) <b>and the command-bearing markdown an agent or installer reads</b> (the source +
 * public READMEs, the canonical {@code skill/SKILL.md} — the N6 material-under-test, so a stale verb there
 * misleads the cold agent directly — and the Codex {@code AGENTS.md}). The {@code examples/} demos are
 * already guarded by Showboat (it <i>executes</i> them), and the skill's plugin copies by the
 * {@code sync-plugin-assets.sh --check} copy-equality gate, so guarding the canonical files suffices:
 *
 * <ul>
 *   <li><b>Full invocations</b> — {@code dmtool [-m <model>] <target> <op> …}: the leading token must be
 *       the live tool name, and the verb path must descend the live tree.</li>
 *   <li><b>Two-token shorthand</b> — a backtick {@code `target op`} whose first token is a real top-level
 *       subcommand (the diagnostic hints, e.g. {@code `rule read`}); the pair must resolve.</li>
 * </ul>
 *
 * <p><b>Markdown is scanned by code context, not prose:</b> inside a fenced code block the whole line is a
 * command; outside one, only inline-code spans (backtick) count — so a bare prose mention like "dmtool
 * guidance" is ignored, while {@code `rule read`} and a fenced {@code dmtool …} block are checked.
 * Illustrative placeholders the skill teaches with — {@code dmtool <target> <op>}, {@code operators <id>} —
 * resolve cleanly (an angle-bracket token ends the path without a violation).
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

    /** Command-bearing markdown an agent/installer reads (repo-root-relative from the {@code cli} module dir).
     *  The {@code examples/} demos are guarded by Showboat (it executes them); the skill's plugin copies by
     *  the copy-equality gate — so only these canonical files need command-resolution scanning here. */
    private static final List<Path> MD_FILES = List.of(
            Path.of("../README.md"),
            Path.of("../plugin/README.md"),
            Path.of("../skill/SKILL.md"),
            Path.of("../plugin-codex/AGENTS.md"));

    /** A path/shorthand segment: a lowercase verb token (targets/ops are all lowercase, hyphens allowed). */
    private static final Pattern BAREWORD = Pattern.compile("[a-z][a-z0-9-]*");
    /** A backtick span (for the two-token shorthand check). */
    private static final Pattern BACKTICK = Pattern.compile("`([^`\\n]+)`");
    /** A long-flag token, optionally with an {@code =value} ({@code --all}, {@code --field=/X}). */
    private static final Pattern FLAG_TOKEN = Pattern.compile("(--[a-z][a-z0-9-]*)(=.*)?");

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
        for (Path md : MD_FILES) {
            if (Files.isRegularFile(md)) {
                scanMarkdown(md, root, invocation, violations, invocationCount);
            }
        }

        assertTrue(invocationCount[0] >= MIN_INVOCATIONS,
                () -> "found only " + invocationCount[0] + " `" + toolName + " …` command examples (floor "
                        + MIN_INVOCATIONS + ") — did the tool get renamed without updating the example text?");
        assertTrue(violations.isEmpty(),
                () -> "command examples referencing verbs that don't exist in the live tree:\n"
                        + String.join("\n", violations));
    }

    /** Code-everywhere surfaces (.java/.json): every line is candidate text — full invocations + the
     *  two-token backtick shorthand. */
    /** Does-fire proof for the markdown path: a stale fenced command must be caught, while a bare prose
     *  mention and an illustrative {@code <id>} placeholder must not — the three behaviours the guard rests on. */
    @Test
    void theMarkdownGuardFiresOnAStaleCommandButNotOnProseOrPlaceholders(@TempDir Path dir) throws IOException {
        CommandLine root = new CommandLine(new RootCommand());
        Pattern invocation = Pattern.compile("\\b" + Pattern.quote(root.getCommandName()) + "\\b([^`}\"\\n]*)");
        Path md = dir.resolve("stale.md");
        Files.writeString(md, """
                Prose mentioning dmtool guidance must be ignored (not a command).

                ```sh
                dmtool -m order.dm.json rule frobnicate --field /X   # stale op after a concrete -m value
                ```

                Inline `dmtool operators <id>` is an illustrative placeholder and must pass.
                """);

        List<String> violations = new ArrayList<>();
        int[] count = {0};
        scanMarkdown(md, root, invocation, violations, count);

        assertEquals(1, violations.size(),
                () -> "expected exactly the stale `rule frobnicate` to fire, got: " + violations);
        assertTrue(violations.get(0).contains("frobnicate"), violations::toString);
    }

    /** Does-fire proof for the flag seam (anti-rot guard #2): a phantom flag after a real verb path must
     *  fire; a real option (here the {@code --workspace} mixin) must not. */
    @Test
    void theFlagGuardFiresOnAPhantomFlagButNotARealOne() {
        CommandLine root = new CommandLine(new RootCommand());

        List<String> phantom = new ArrayList<>();
        checkSpanFlags(List.of("rule", "read", "--bogus"), 0, root, Path.of("x"), 1, "rule read --bogus", phantom);
        assertEquals(1, phantom.size(), () -> "expected the phantom `--bogus` to fire, got: " + phantom);
        assertTrue(phantom.get(0).contains("--bogus"), phantom::toString);

        List<String> real = new ArrayList<>();
        checkSpanFlags(List.of("rule", "read", "--workspace"), 0, root, Path.of("x"), 1, "rule read --workspace", real);
        assertTrue(real.isEmpty(), () -> "a real option (`--workspace`) must not fire: " + real);
    }

    private static void scan(Path file, CommandLine root, Pattern invocation,
            List<String> violations, int[] invocationCount) {
        List<String> lines = readLines(file);
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            matchInvocations(line, file, i + 1, root, invocation, violations, invocationCount);
            Matcher b = BACKTICK.matcher(line);
            while (b.find()) {
                checkTwoTokenShorthand(b.group(1), file, i + 1, root, violations);
                checkSpanFlags(tokenize(b.group(1)), 0, root, file, i + 1, b.group(1), violations);
            }
        }
    }

    /** Markdown surfaces: scan by <b>code context</b>, not prose. Inside a fenced block the whole line is a
     *  command; outside one, only inline-code spans count — so a bare prose mention ("dmtool guidance") is
     *  ignored while {@code `rule read`} and fenced {@code dmtool …} lines are checked. */
    private static void scanMarkdown(Path file, CommandLine root, Pattern invocation,
            List<String> violations, int[] invocationCount) {
        List<String> lines = readLines(file);
        boolean inFence = false;
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            int lineNo = i + 1;
            if (line.stripLeading().startsWith("```")) {
                inFence = !inFence;
                continue;
            }
            if (inFence) {
                matchInvocations(line, file, lineNo, root, invocation, violations, invocationCount);
            } else {
                Matcher b = BACKTICK.matcher(line);
                while (b.find()) {
                    String span = b.group(1);
                    matchInvocations(span, file, lineNo, root, invocation, violations, invocationCount);
                    checkTwoTokenShorthand(span, file, lineNo, root, violations);
                    checkSpanFlags(tokenize(span), 0, root, file, lineNo, span, violations);
                }
            }
        }
    }

    private static List<String> readLines(Path file) {
        try {
            return Files.readAllLines(file);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Find every {@code dmtool …} invocation in {@code text}; count the resolved ones, record stale ones. */
    private static void matchInvocations(String text, Path file, int lineNo, CommandLine root,
            Pattern invocation, List<String> violations, int[] invocationCount) {
        Matcher m = invocation.matcher(text);
        while (m.find()) {
            List<String> toks = tokenize(m.group(1));
            String problem = resolveInvocation(root, toks);
            String display = (root.getCommandName() + m.group(1)).trim();
            if (problem == PATH_PRESENT) {
                invocationCount[0]++;
                checkSpanFlags(toks, firstVerbIndex(root, toks), root, file, lineNo, display, violations);
            } else if (problem != null) {
                violations.add(file + ":" + lineNo + " — `" + display + "` — " + problem);
            }
        }
    }

    /** The {@code `target op`} shorthand: a two-bareword span whose first word is a real top-level verb. */
    private static void checkTwoTokenShorthand(String span, Path file, int lineNo, CommandLine root,
            List<String> violations) {
        String[] words = span.trim().split("\\s+");
        if (words.length == 2 && BAREWORD.matcher(words[0]).matches()
                && BAREWORD.matcher(words[1]).matches() && root.getSubcommands().containsKey(words[0])) {
            String problem = resolvePath(root, List.of(words), 0);
            if (problem != null && problem != PATH_PRESENT) {
                violations.add(file + ":" + lineNo + " — `" + span + "` — " + problem);
            }
        }
    }

    /** Sentinel: a real, fully-resolved verb path was present (counts toward the invocation floor). */
    private static final String PATH_PRESENT = "<resolved>";

    private static String resolveInvocation(CommandLine root, List<String> tokens) {
        return resolvePath(root, tokens, firstVerbIndex(root, tokens));
    }

    /** Strip leading global options to find the first verb token — both the placeholder form
     *  ({@code -m <model.json>}) and the concrete form the READMEs/skill use ({@code -m order.dm.json}). The
     *  value is skipped only when it isn't itself a real top-level verb, so a boolean flag followed by a verb
     *  ({@code --dry-run rule modify}) still resolves the verb. */
    private static int firstVerbIndex(CommandLine root, List<String> tokens) {
        int i = 0;
        while (i < tokens.size() && tokens.get(i).startsWith("-")) {
            i++;                                                    // the option flag
            if (i < tokens.size() && !tokens.get(i).startsWith("-")
                    && !root.getSubcommands().containsKey(tokens.get(i))) {
                i++;                                                // its value (placeholder or concrete file)
            }
        }
        return i;
    }

    /**
     * Anti-rot guard #2 (the flag seam): once the leading barewords resolve to a leaf op, every trailing
     * {@code --long} flag must be a real option of that command (or an inherited/mixin global). This is what
     * the verb-path resolver above misses — {@code rule read --all} resolves at {@code rule read} (a real
     * verb) and the phantom {@code --all} rides through. Short flags are left alone (ambiguous with values);
     * a flag only fires once the path is a leaf, so non-command prose can't false-positive.
     */
    private static void checkSpanFlags(List<String> tokens, int start, CommandLine root, Path file, int lineNo,
            String display, List<String> violations) {
        CommandLine node = root;
        List<String> path = new ArrayList<>();
        int i = start;
        for (; i < tokens.size(); i++) {
            String t = tokens.get(i);
            if (!BAREWORD.matcher(t).matches()) {
                break;
            }
            CommandLine sub = node.getSubcommands().get(t);
            if (sub == null) {
                return;                                             // path doesn't resolve — resolvePath reports it
            }
            node = sub;
            path.add(t);
        }
        if (path.isEmpty() || !node.getSubcommands().isEmpty()) {
            return;                                                 // no leaf op resolved — nothing to flag-check
        }
        Set<String> allowed = longFlags(node);
        for (; i < tokens.size(); i++) {
            String t = tokens.get(i);
            if (t.startsWith("#")) {
                break;                                              // a shell comment ends the command
            }
            Matcher fm = FLAG_TOKEN.matcher(t);
            if (fm.matches() && !allowed.contains(fm.group(1))) {
                violations.add(file + ":" + lineNo + " — `" + display.trim() + "` — `" + fm.group(1)
                        + "` is not an option of `" + String.join(" ", path) + "`");
            }
        }
    }

    /** Every {@code --long} option of a command and its ancestors (so inherited/mixin globals are allowed). */
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
            if (t.startsWith("#")) {
                break;                                              // a shell comment ends the command
            }
            if (!t.isEmpty()) {
                out.add(t);
            }
        }
        return out;
    }
}
