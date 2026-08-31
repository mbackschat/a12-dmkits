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
 * the operator catalog), <b>the command-bearing markdown an agent or installer reads</b> (the source +
 * public READMEs, the canonical {@code skill/SKILL.md} — the N6 material-under-test, so a stale verb there
 * misleads the cold agent directly — and the Codex {@code AGENTS.md}), <b>and the command-bearing JS an
 * agent harness executes</b> (the eval workflow {@code benchmark.workflow.js}, whose {@code ${CLI} …}
 * command forms are exactly where the task-17 recheck drifted post-baseline — a positional rule passed as
 * {@code --rule}). The {@code examples/} demos are already guarded by Showboat (it <i>executes</i> them),
 * and the skill's plugin copies by the {@code sync-plugin-assets.sh --check} copy-equality gate, so
 * guarding the canonical files suffices:
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
            Path.of("../rulekit/src/main/resources/catalog"));

    /** Command-bearing markdown an agent/installer reads (repo-root-relative from the {@code cli} module dir).
     *  The {@code examples/} demos are guarded by Showboat (it executes them); the skill's plugin copies by
     *  the copy-equality gate — so only these canonical files need command-resolution scanning here. */
    private static final List<Path> MD_FILES = List.of(
            Path.of("../README.md"),
            Path.of("../plugin/README.md"),
            Path.of("../skill/SKILL.md"),
            Path.of("../plugin-codex/AGENTS.md"),
            // The project instructions, symlinked as the repo-root AGENTS.md — the one file EVERY session reads,
            // so a stale verb here misleads more often than anywhere else. It cites verbs to reach for when
            // verifying a claim (`dmtool manifest`, `rule check`, `model eval`, …); those must resolve.
            Path.of("../CLAUDE.md"));

    /**
     * Spec/design markdown scanned in its FENCED BLOCKS ONLY — a copyable example must resolve, while the prose
     * around it is left alone.
     *
     * <p><b>Why the split exists, measured 2026-08-31.</b> The verb contract itself was unscanned and shipped
     * `apply --ops <file>` for an unknown period — a phantom flag on a verb whose ops file is a positional. The
     * flag check that catches exactly that already existed and worked; the miss was purely the DENOMINATOR, so
     * the obvious repair was to add the file to {@link #MD_FILES}. Doing that produced <b>13 violations, of which
     * one was the real defect</b>: the other 12 are a spec doing its job — naming retired verbs
     * (`model validate`, `workspace validate`, `rule test`), a rejected rename (`doc` → `model`), a deferred verb
     * (`rule`/`computation move`), and a deliberate counter-example (`config modify --lang EN`, in a sentence
     * that calls it a category error). At that precision the guard fails honest content, and a guard that fails
     * honest content gets switched off — the estate's own recorded lesson.
     *
     * <p><b>The discriminator is the fence, and it was measured rather than assumed.</b> All 12 legitimate
     * mentions are prose; the real defect sat inside a fenced block presented as a copyable invocation. Restricting
     * this file to its fences takes 13 violations to 1, and that one was an ASCII diagram row whose `dmtool`
     * happened to be followed by a bareword — reworded in the same change rather than exempted, because a diagram
     * inside a fence really should not read as an invocation.
     */
    private static final List<Path> FENCED_ONLY_MD_FILES = List.of(Path.of("../docs/CLI-SPEC.md"));

    /** Command-bearing JS an agent harness EXECUTES: the eval workflow embeds {@code ${CLI} …} command
     *  forms ({@code ${CLI}} = its {@code bin/dmtool} handle) — the exact surface where the task-17 recheck
     *  drifted post-baseline (a positional rule passed as {@code --rule}, whose usage error the harness read
     *  as "kernel rejected", scoring a correct capability 0/3). The in-binary text + markdown above resolve
     *  against the live tree already; this closes the JS gap the runtime usage-error flag only backstops. */
    private static final List<Path> JS_FILES = List.of(Path.of("../eval/benchmark.workflow.js"));

    /** A path/shorthand segment: a lowercase verb token (targets/ops are all lowercase, hyphens allowed). */
    private static final Pattern BAREWORD = Pattern.compile("[a-z][a-z0-9-]*");
    /** A backtick span (for the two-token shorthand check). */
    private static final Pattern BACKTICK = Pattern.compile("`([^`\\n]+)`");
    /** A long-flag token, optionally with an {@code =value} ({@code --all}, {@code --field=/X}). */
    private static final Pattern FLAG_TOKEN = Pattern.compile("(--[a-z][a-z0-9-]*)(=.*)?");
    /** The eval workflow's CLI-handle marker (its {@code const CLI = '… bin/dmtool'}) — the literal prefix of
     *  every JS command form, the {@code ${CLI}} analogue of the tool-name token in the text surfaces. */
    private static final Pattern JS_CLI = Pattern.compile("\\$\\{CLI\\}");
    /** A {@code ${…}} template placeholder (no nested braces) — a dynamic value, neutralized to {@code <v>}
     *  so the arg stream resolves like a normal invocation carrying placeholders. */
    private static final Pattern JS_PLACEHOLDER = Pattern.compile("\\$\\{[^{}]*\\}");

    /** A <b>single-token</b> backtick span can name a verb no positive check reaches: one bareword could be
     *  anything — an op mentioned alone ({@code `explain`}), a flag value, a JSON key — so it cannot be
     *  resolved against the tree the way a {@code dmtool …} invocation or a {@code `target op`} shorthand
     *  can. Retired / never-existed verb tokens hide exactly there (the README demo table's stale
     *  {@code `validate`} survived every positive check). This curated deny-list closes that seam for the
     *  scanned markdown: each entry is a token that must NOT appear as a bare backticked word, mapped to
     *  the corrective to write instead. {@link #theRetiredTokenDenyListStaysRetiredInTheLiveTree()} keeps
     *  the list honest against the live tree, so a later real verb of that name forces the entry out. */
    private static final Map<String, String> RETIRED_VERB_TOKENS = Map.of(
            "validate", "the live verb is `check` (`model check` / `workspace check` / `rule check`)");

    /** Sanity floor: the rich help text carries dozens of {@code dmtool …} examples. A rename that leaves
     *  the literal old name in the text makes the live-name scan find ~0 — this floor catches that. */
    private static final int MIN_INVOCATIONS = 20;
    /** Sanity floor for the JS surface, counted SEPARATELY from the tool-name count: the JS keys off the
     *  {@code ${CLI}} variable, not the tool-name literal, so folding it into {@link #MIN_INVOCATIONS} would
     *  let JS commands prop up the rename floor and defeat its trip-wire. This floor instead catches the JS
     *  scan silently going dark (the handle renamed away from {@code ${CLI}}, the workflow moved/emptied).
     *  The live workflow resolves exactly 66 (re-derived 2026-08-16 through this guard's own scan); 30 leaves
     *  ample headroom for prompt-text churn while staying a real
     *  trip-wire. */
    private static final int MIN_JS_INVOCATIONS = 30;

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
                scanMarkdown(md, root, invocation, violations, invocationCount, false);
            }
        }
        for (Path md : FENCED_ONLY_MD_FILES) {
            if (Files.isRegularFile(md)) {
                scanMarkdown(md, root, invocation, violations, invocationCount, true);
            }
        }
        int[] jsInvocationCount = {0};
        for (Path js : JS_FILES) {
            if (Files.isRegularFile(js)) {
                scanJs(js, root, violations, jsInvocationCount);
            }
        }

        assertTrue(invocationCount[0] >= MIN_INVOCATIONS,
                () -> "found only " + invocationCount[0] + " `" + toolName + " …` command examples (floor "
                        + MIN_INVOCATIONS + ") — did the tool get renamed without updating the example text?");
        assertTrue(jsInvocationCount[0] >= MIN_JS_INVOCATIONS,
                () -> "found only " + jsInvocationCount[0] + " `${CLI} …` command forms in the eval workflow"
                        + " (floor " + MIN_JS_INVOCATIONS + ") — did the CLI handle rename or the workflow move?");
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
        scanMarkdown(md, root, invocation, violations, count, false);

        assertEquals(1, violations.size(),
                () -> "expected exactly the stale `rule frobnicate` to fire, got: " + violations);
        assertTrue(violations.get(0).contains("frobnicate"), violations::toString);
    }

    /**
     * Does-fire AND does-not-fire proof for {@link #FENCED_ONLY_MD_FILES}, on one fixture carrying both — the
     * phantom flag that shipped in a fence, and each kind of legitimate prose mention that made the full scan
     * unusable on a spec. Both halves are needed: the fenced-only mode is worthless if it stops catching the
     * defect, and it is worse than nothing if it resumes failing honest prose.
     */
    @Test
    void theFencedOnlyModeCatchesAPhantomFlagInACodeBlockAndLeavesSpecProseAlone(@TempDir Path dir)
            throws IOException {
        CommandLine root = new CommandLine(new RootCommand());
        Pattern invocation = Pattern.compile("\\b" + Pattern.quote(root.getCommandName()) + "\\b([^`}\"\\n]*)");
        Path md = dir.resolve("spec.md");
        Files.writeString(md, """
                A spec legitimately names retired verbs — `model validate` became `model check` — and a
                deferred one, `computation move`, and a rejected rename, `dmtool doc <op>`. It also states
                deliberate counter-examples: `config modify --lang EN` would be a category error.

                ```
                dmtool -m order.dm.json apply --ops edits.json
                ```
                """);

        List<String> violations = new ArrayList<>();
        int[] count = {0};
        scanMarkdown(md, root, invocation, violations, count, true);

        assertEquals(1, violations.size(),
                () -> "the fence must fire and the prose must not — got: " + violations);
        assertTrue(violations.get(0).contains("--ops"),
                () -> "the surviving violation must be the phantom flag: " + violations);

        List<String> full = new ArrayList<>();
        scanMarkdown(md, root, invocation, full, new int[] {0}, false);
        assertTrue(full.size() > violations.size(),
                () -> "positive control: the full scan must fire on this spec prose, else the fenced-only mode "
                        + "is not what is being measured and this test would pass without it: " + full);
    }

    /** Does-fire proof for the JS surface: a stale op and a phantom flag in a {@code ${CLI} …} command must
     *  fire (the {@code --rule} case IS the task-17 drift class), while a valid command — even piped into
     *  {@code jq --arg} (a foreign {@code --long} the pipe must cut off) — must not. */
    @Test
    void theJsGuardFiresOnAStaleOpOrPhantomFlagButNotOnAValidPipedCommand(@TempDir Path dir) throws IOException {
        CommandLine root = new CommandLine(new RootCommand());
        Path js = dir.resolve("x.workflow.js");
        Files.writeString(js, """
                const CLI = 'JAVA_HOME=… bin/dmtool'
                const ok      = `${CLI} -m ${MODEL_FILE(t)} rule read ${t.rule}`
                const piped   = `${CLI} -m ${out} model describe | jq -c --arg p '${gold.f}' '{a:1}'`
                const staleOp = `${CLI} -m ${m} rule frobnicate ${x} -o ${out}`
                const badFlag = `${CLI} -m ${m} rule modify ${r} --rule ${x} -o ${out}`  // the task-17 drift
                """);

        List<String> violations = new ArrayList<>();
        int[] count = {0};
        scanJs(js, root, violations, count);

        assertEquals(3, count[0],   // ok + piped + badFlag resolve a verb path (staleOp's op is bogus, not counted)
                () -> "every ${CLI} command with a real verb path should count: " + count[0]);
        assertEquals(2, violations.size(),
                () -> "expected exactly the stale op + the phantom flag to fire (not the piped jq --arg): " + violations);
        assertTrue(violations.stream().anyMatch(v -> v.contains("frobnicate")), violations::toString);
        assertTrue(violations.stream().anyMatch(v -> v.contains("--rule")), violations::toString);
    }

    /** Does-fire proof for the retired-token seam: a bare backticked {@code `validate`} must fire with the
     *  corrective, while the real {@code --validate} flag, the composite {@code workspace list --validate},
     *  and an undenied lone op name ({@code `usage`}) must not. */
    @Test
    void theRetiredTokenGuardFiresOnABareTokenButNotOnFlagsOrCompositeSpans(@TempDir Path dir) throws IOException {
        CommandLine root = new CommandLine(new RootCommand());
        Pattern invocation = Pattern.compile("\\b" + Pattern.quote(root.getCommandName()) + "\\b([^`}\"\\n]*)");
        Path md = dir.resolve("stale.md");
        Files.writeString(md, """
                A demo table cell like `model describe`/`usage`/`validate` names a retired verb bare.

                The flag `--validate` and the full `workspace list --validate` stay legal.
                """);

        List<String> violations = new ArrayList<>();
        int[] count = {0};
        scanMarkdown(md, root, invocation, violations, count, false);

        assertEquals(1, violations.size(),
                () -> "expected exactly the bare `validate` to fire, got: " + violations);
        assertTrue(violations.get(0).contains("retired verb token"), violations::toString);
    }

    /** The deny-list's own anti-rot lock: a token stays deny-listed only while NO command of that name
     *  exists anywhere in the live tree. If a real verb/op of that name ever lands, bare references become
     *  legitimate and this fails, naming the entry to remove. */
    @Test
    void theRetiredTokenDenyListStaysRetiredInTheLiveTree() {
        Set<String> live = new HashSet<>();
        collectCommandNames(new CommandLine(new RootCommand()), live);
        for (String token : RETIRED_VERB_TOKENS.keySet()) {
            assertTrue(!live.contains(token),
                    "`" + token + "` is on the retired-verb deny-list but now exists in the live command tree"
                            + " — remove it from RETIRED_VERB_TOKENS (bare `" + token + "` refs are legitimate again)");
        }
    }

    /** Every command name at every depth of the live tree. */
    private static void collectCommandNames(CommandLine node, Set<String> out) {
        for (Map.Entry<String, CommandLine> e : node.getSubcommands().entrySet()) {
            out.add(e.getKey());
            collectCommandNames(e.getValue(), out);
        }
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
            List<String> violations, int[] invocationCount, boolean fencedOnly) {
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
            } else if (!fencedOnly) {
                Matcher b = BACKTICK.matcher(line);
                while (b.find()) {
                    String span = b.group(1);
                    matchInvocations(span, file, lineNo, root, invocation, violations, invocationCount);
                    checkTwoTokenShorthand(span, file, lineNo, root, violations);
                    checkRetiredToken(span, file, lineNo, violations);
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
            checkInvocationArgs(m.group(1), root.getCommandName(), file, lineNo, root, violations, invocationCount);
        }
    }

    /** Resolve one invocation's argument string (the text after the CLI marker — the tool name in the text
     *  surfaces, {@code ${CLI}} in the JS): count it when the verb path is real, record a violation when a
     *  bareword that should name a verb/op is stale, and flag-check trailing {@code --long} options once a
     *  leaf op resolves. */
    private static void checkInvocationArgs(String args, String marker, Path file, int lineNo,
            CommandLine root, List<String> violations, int[] invocationCount) {
        List<String> toks = tokenize(args);
        String problem = resolveInvocation(root, toks);
        String display = (marker + args).trim();
        if (problem == PATH_PRESENT) {
            invocationCount[0]++;
            checkSpanFlags(toks, firstVerbIndex(root, toks), root, file, lineNo, display, violations);
        } else if (problem != null) {
            violations.add(file + ":" + lineNo + " — `" + display + "` — " + problem);
        }
    }

    /** JS workflow surfaces ({@code eval/*.workflow.js}): commands are template literals prefixed with the
     *  CLI handle {@code ${CLI}} (= "… bin/dmtool"), with {@code ${…}} placeholders for dynamic values and
     *  inline code delimited by (escaped) backticks. Key off {@code ${CLI}} — the literal command marker —
     *  take the args up to the command's end, neutralize each placeholder to {@code <v>}, and resolve +
     *  flag-check exactly as the text surfaces do (so a stale op or a phantom flag like the task-17
     *  {@code rule modify --rule} fires here, statically, not only via the runtime usage-error backstop). */
    private static void scanJs(Path file, CommandLine root, List<String> violations, int[] jsInvocationCount) {
        List<String> lines = readLines(file);
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            Matcher m = JS_CLI.matcher(line);
            while (m.find()) {
                checkInvocationArgs(jsCommandArgs(line.substring(m.end())), "${CLI}",
                        file, i + 1, root, violations, jsInvocationCount);
            }
        }
    }

    /** The argument string after a {@code ${CLI}} marker: up to the (escaped) backtick that closes the
     *  template literal / inline-code span, with each {@code ${…}} placeholder neutralized to {@code <v>}. */
    private static String jsCommandArgs(String afterCli) {
        int tick = afterCli.indexOf('`');
        String args = tick >= 0 ? afterCli.substring(0, tick) : afterCli;
        if (args.endsWith("\\")) {
            args = args.substring(0, args.length() - 1);   // drop the inline-code escape before the backtick
        }
        return JS_PLACEHOLDER.matcher(args).replaceAll("<v>");
    }

    /** A bare backticked span that IS a retired verb token (see {@link #RETIRED_VERB_TOKENS}): fires with
     *  the corrective. Only the exact single-token form is denied — {@code --validate} (a real flag) and
     *  composite spans keep riding the positive checks. */
    private static void checkRetiredToken(String span, Path file, int lineNo, List<String> violations) {
        String hint = RETIRED_VERB_TOKENS.get(span.trim());
        if (hint != null) {
            violations.add(file + ":" + lineNo + " — `" + span.trim() + "` — a retired verb token; " + hint);
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
            if (t.startsWith("#") || t.equals("|")) {
                break;                                              // a shell comment or a pipe ends the command
            }
            if (!t.isEmpty()) {
                out.add(t);
            }
        }
        return out;
    }
}
